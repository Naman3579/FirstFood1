package com.firstfood.provideraccess;

import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitRule;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.ratelimit.RateLimitedException;
import com.firstfood.identity.AccountLookupService;
import com.firstfood.identity.SecurityNoticeService;
import com.firstfood.identity.StepUpService;
import com.firstfood.identity.error.StepUpException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency: every mutation first takes a row lock on the provider
 * ({@code select ... for update}), so concurrent role/ownership mutations on
 * one provider run one after another and each re-reads committed state after
 * acquiring the lock (READ COMMITTED). The partial unique indexes remain the
 * last line of defence. Audit rows are written in the same transaction.
 */
@Service
public class ProviderRoleServiceImpl implements ProviderRoleService {

    private static final String CLOSED = "CLOSED";

    private final ProviderRoleAssignmentRepository repository;
    private final ProviderRoleAuditRepository auditRepository;
    private final ProviderAccessService accessService;
    private final AccountLookupService accountLookup;
    private final RateLimitService rateLimitService;
    private final int maxPhoneLookupsPerDay;
    private final OwnershipOfferRepository offerRepository;
    private final StepUpService stepUp;
    private final SecurityNoticeService notices;
    private final Duration offerTtl;

    public ProviderRoleServiceImpl(ProviderRoleAssignmentRepository repository,
            ProviderRoleAuditRepository auditRepository, ProviderAccessService accessService,
            AccountLookupService accountLookup,
            RateLimitService rateLimitService,
            OwnershipOfferRepository offerRepository, StepUpService stepUp, SecurityNoticeService notices,
            @Value("${app.provider.ownership-offer-ttl-days:7}") int ownershipOfferTtlDays,
            @Value("${app.provider.max-phone-lookups-per-day:50}") int maxPhoneLookupsPerDay) {
        this.offerRepository = offerRepository;
        this.stepUp = stepUp;
        this.notices = notices;
        this.offerTtl = Duration.ofDays(Math.max(1, ownershipOfferTtlDays));
        this.rateLimitService = rateLimitService;
        this.maxPhoneLookupsPerDay = Math.max(1, maxPhoneLookupsPerDay);
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.accessService = accessService;
        this.accountLookup = accountLookup;
    }

    @Override
    @Transactional
    public RoleAssignmentView assignRole(UUID actorAccountId, UUID providerId, String targetPhone, ProviderRole role) {
        requireContext(actorAccountId, providerId);

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_ASSIGN);
        rejectIfClosed(providerStatus);

        if (role == ProviderRole.OWNER) {
            throw RoleManagementException.ownerAssignmentNotAllowed();
        }
        if (role == null) {
            throw RoleManagementException.invalidRole();
        }

        UUID targetAccountId = resolveTarget(providerId, targetPhone);

        // Covers "already has this role", "has another role" and "is the owner".
        if (repository.findByProviderIdAndAccountIdAndStatus(providerId, targetAccountId, RoleAssignmentStatus.ACTIVE)
                .isPresent()) {
            throw RoleManagementException.alreadyAssigned();
        }

        ProviderRoleAssignment saved;
        try {
            saved = repository.saveAndFlush(
                    new ProviderRoleAssignment(providerId, targetAccountId, role, actorAccountId));
        } catch (DataIntegrityViolationException e) {
            // The partial unique index is the last line of defence; the provider lock makes this unlikely.
            throw RoleManagementException.alreadyAssigned();
        }
        audit(RoleAuditAction.ROLE_ASSIGNED, providerId, actorAccountId, targetAccountId, null, role);
        return toViews(List.of(saved)).get(0);
    }

    @Override
    @Transactional
    public RoleAssignmentView revokeRole(UUID actorAccountId, UUID providerId, UUID assignmentId) {
        requireContext(actorAccountId, providerId);
        Objects.requireNonNull(assignmentId, "assignmentId");

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_REVOKE);
        rejectIfClosed(providerStatus);

        // Provider-scoped lookup: an assignment id from another provider is "not found".
        ProviderRoleAssignment assignment = repository.findByIdAndProviderId(assignmentId, providerId)
                .orElseThrow(RoleManagementException::assignmentNotFound);
        if (!assignment.isActive()) {
            throw RoleManagementException.alreadyRevoked();
        }
        if (assignment.getRole() == ProviderRole.OWNER) {
            throw RoleManagementException.ownerRevocationNotAllowed();
        }

        assignment.revoke(actorAccountId, Instant.now());
        ProviderRoleAssignment saved = repository.saveAndFlush(assignment);
        audit(RoleAuditAction.ROLE_REVOKED, providerId, actorAccountId, saved.getAccountId(), saved.getRole(), null);
        return toViews(List.of(saved)).get(0);
    }

    /** NOT readOnly: sending a code stores an OTP challenge row. A read-only transaction would silently drop it. */
    @Override
    @Transactional
    public void requestOwnerStepUp(UUID actorAccountId, UUID providerId) {
        requireContext(actorAccountId, providerId);
        // Only an owner may ask: otherwise anyone could make the system text an owner's phone.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.OWNER_TRANSFER);
        stepUp.requestCode(actorAccountId);
    }

    /** noRollbackFor: a wrong code must keep the failed-attempt count the OTP service just recorded. */
    @Override
    @Transactional(noRollbackFor = StepUpException.class)
    public OwnershipOfferView offerOwnership(UUID actorAccountId, UUID providerId, String targetPhone, String code) {
        requireContext(actorAccountId, providerId);

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.OWNER_TRANSFER);
        rejectIfClosed(providerStatus);

        String target = OfferPhones.canonical(targetPhone);
        if (target == null) {
            throw RoleManagementException.offerTargetInvalid();
        }
        String ownPhone = accountLookup.findPhonesByIds(List.of(actorAccountId)).get(actorAccountId);
        if (target.equals(OfferPhones.canonical(ownPhone))) {
            throw RoleManagementException.transferToSelf();
        }

        Instant now = Instant.now();
        Optional<OwnershipOffer> open = offerRepository.findByProviderIdAndStatus(providerId,
                OwnershipOfferStatus.PENDING);
        if (open.isPresent()) {
            if (open.get().isOpen(now)) {
                throw RoleManagementException.offerAlreadyPending();
            }
            open.get().expire(now); // lapsed: free the one-open-offer slot
            offerRepository.saveAndFlush(open.get());
        }

        // Last, once everything cheap has passed: spends the code (rolled back with the rest if anything below fails).
        stepUp.consume(actorAccountId, code);

        OwnershipOffer offer = offerRepository.saveAndFlush(
                new OwnershipOffer(providerId, actorAccountId, target, now.plus(offerTtl)));
        notices.notifyAfterCommit(actorAccountId, "FirstFood: you offered ownership of " + providerName(providerId)
                + " to " + OfferPhones.hint(target) + ". Nothing changes until they accept. Not you? Sign in and"
                + " withdraw the offer.");
        return OwnershipOfferView.from(offer, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipOfferView> listOwnershipOffers(UUID actorAccountId, UUID providerId) {
        requireContext(actorAccountId, providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.OWNER_TRANSFER);
        Instant now = Instant.now();
        return offerRepository.findTop20ByProviderIdOrderByCreatedAtDesc(providerId).stream()
                .map(o -> OwnershipOfferView.from(o, now)).toList();
    }

    @Override
    @Transactional
    public OwnershipOfferView withdrawOwnershipOffer(UUID actorAccountId, UUID providerId, UUID offerId) {
        requireContext(actorAccountId, providerId);
        Objects.requireNonNull(offerId, "offerId");

        lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.OWNER_TRANSFER);
        // Scoped by provider: another provider's offer is reported exactly like a missing one.
        if (!offerRepository.existsByIdAndProviderId(offerId, providerId)) {
            throw RoleManagementException.offerNotFound();
        }
        OwnershipOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(RoleManagementException::offerNotFound);
        if (offer.getStatus() != OwnershipOfferStatus.PENDING) {
            throw RoleManagementException.offerNotPending();
        }
        Instant now = Instant.now();
        if (offer.isOpen(now)) {
            offer.withdraw(actorAccountId, now);
        } else {
            offer.expire(now); // nothing left to withdraw: just record that it lapsed
        }
        return OwnershipOfferView.from(offerRepository.saveAndFlush(offer), now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyOwnershipOfferView> listMyOwnershipOffers(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must come from the authenticated principal");
        String phone = OfferPhones.canonical(accountLookup.findPhonesByIds(List.of(accountId)).get(accountId));
        if (phone == null) {
            return List.of();
        }
        Instant now = Instant.now();
        List<OwnershipOffer> open = offerRepository
                .findByTargetPhoneAndStatusOrderByCreatedAtDesc(phone, OwnershipOfferStatus.PENDING).stream()
                .filter(o -> o.isOpen(now)).toList();
        Map<UUID, String> ownerPhones =
                accountLookup.findPhonesByIds(open.stream().map(OwnershipOffer::getOfferedBy).distinct().toList());
        return open.stream().map(o -> new MyOwnershipOfferView(o.getId(), o.getProviderId(),
                providerName(o.getProviderId()),
                OfferPhones.hint(OfferPhones.canonical(ownerPhones.get(o.getOfferedBy()))),
                o.getCreatedAt(), o.getExpiresAt())).toList();
    }

    /** noRollbackFor: lapsing an expired offer must persist even though the caller is told it expired. */
    @Override
    @Transactional(noRollbackFor = RoleManagementException.class)
    public List<RoleAssignmentView> acceptOwnershipOffer(UUID acceptorAccountId, UUID offerId) {
        Objects.requireNonNull(acceptorAccountId, "accountId must come from the authenticated principal");
        UUID providerId = providerOfMyOffer(acceptorAccountId, offerId);

        // Same lock order as every other mutation: provider first, then the offer row.
        String providerStatus = lockProvider(providerId);
        OwnershipOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(RoleManagementException::offerNotFound);
        Instant now = Instant.now();
        requireOpen(offer, now);
        rejectIfClosed(providerStatus);

        ProviderRoleAssignment oldOwner = repository
                .findByProviderIdAndRoleAndStatus(providerId, ProviderRole.OWNER, RoleAssignmentStatus.ACTIVE)
                .orElseThrow(ProviderNotFoundException::new);
        if (!oldOwner.getAccountId().equals(offer.getOfferedBy())) {
            offer.expire(now);
            offerRepository.saveAndFlush(offer);
            throw RoleManagementException.offerStale();
        }
        if (oldOwner.getAccountId().equals(acceptorAccountId)) {
            throw RoleManagementException.transferToSelf();
        }
        Optional<ProviderRoleAssignment> targetCurrent = repository
                .findByProviderIdAndAccountIdAndStatus(providerId, acceptorAccountId, RoleAssignmentStatus.ACTIVE);
        ProviderRole targetOldRole = targetCurrent.map(ProviderRoleAssignment::getRole).orElse(null);

        // Order matters and every step is flushed immediately: Hibernate would otherwise run the INSERTs before the
        // UPDATEs, and the partial unique indexes (one ACTIVE OWNER; one ACTIVE role per account) are checked per
        // statement and cannot be deferred. The "zero owners" moment only exists inside this uncommitted transaction.
        UUID offeredBy = offer.getOfferedBy();
        oldOwner.revoke(acceptorAccountId, now);
        repository.saveAndFlush(oldOwner);
        if (targetCurrent.isPresent()) {
            ProviderRoleAssignment superseded = targetCurrent.get();
            superseded.revoke(acceptorAccountId, now);
            repository.saveAndFlush(superseded);
        }
        ProviderRoleAssignment newOwner = repository.saveAndFlush(
                new ProviderRoleAssignment(providerId, acceptorAccountId, ProviderRole.OWNER, offeredBy));
        ProviderRoleAssignment formerOwner = repository.saveAndFlush(
                new ProviderRoleAssignment(providerId, offeredBy, ProviderRole.MANAGER, offeredBy));
        offer.accept(acceptorAccountId, now);
        offerRepository.saveAndFlush(offer);

        // One audit row per account whose role changed. The actor is the ACCEPTOR: ownership moved because they
        // consented (the owner's side of it is the ownership_offer row: offered_by, created_at).
        audit(RoleAuditAction.OWNER_TRANSFERRED, providerId, acceptorAccountId, acceptorAccountId,
                targetOldRole, ProviderRole.OWNER);
        audit(RoleAuditAction.OWNER_TRANSFERRED, providerId, acceptorAccountId, offeredBy,
                ProviderRole.OWNER, ProviderRole.MANAGER);

        notices.notifyAfterCommit(offeredBy, "FirstFood: ownership of " + providerName(providerId)
                + " was transferred to a new owner who accepted your offer. You are now a manager. Not expected?"
                + " Contact support at once.");
        return toViews(List.of(newOwner, formerOwner));
    }

    @Override
    @Transactional(noRollbackFor = RoleManagementException.class)
    public void declineOwnershipOffer(UUID accountId, UUID offerId) {
        Objects.requireNonNull(accountId, "accountId must come from the authenticated principal");
        UUID providerId = providerOfMyOffer(accountId, offerId);
        lockProvider(providerId);
        OwnershipOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(RoleManagementException::offerNotFound);
        Instant now = Instant.now();
        requireOpen(offer, now);
        offer.decline(accountId, now);
        offerRepository.saveAndFlush(offer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleAssignmentView> findRoles(UUID actorAccountId, UUID providerId) {
        requireContext(actorAccountId, providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_VIEW);
        return toViews(repository.findByProviderIdAndStatusOrderByCreatedAtAsc(providerId, RoleAssignmentStatus.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRole(UUID providerId, UUID accountId, ProviderRole role) {
        return repository.findByProviderIdAndAccountIdAndStatus(providerId, accountId, RoleAssignmentStatus.ACTIVE)
                .map(a -> a.getRole() == role)
                .orElse(false);
    }

    private static void requireContext(UUID actorAccountId, UUID providerId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId must come from the authenticated principal");
        Objects.requireNonNull(providerId, "providerId");
    }

    /** Takes the provider row lock; a missing provider is indistinguishable from "no access". */
    private String lockProvider(UUID providerId) {
        return repository.lockProviderAndGetStatus(providerId).orElseThrow(ProviderNotFoundException::new);
    }

    private void rejectIfClosed(String providerStatus) {
        if (CLOSED.equals(providerStatus)) {
            throw RoleManagementException.providerClosed();
        }
    }

    /**
     * Turns a typed phone number into an account. The answer ("not registered" vs found) tells the caller whether a number
     * has an account, so lookups are budgeted per provider per day (F-03). Only role assignment still uses it: ownership
     * now moves through an offer keyed by phone (F-08), which has no such oracle.
     */
    private UUID resolveTarget(UUID providerId, String targetPhone) {
        RateLimitDecision budget = rateLimitService.hit(RateLimitPolicies.PROVIDER_PHONE_LOOKUP, providerId.toString(),
                new RateLimitRule(maxPhoneLookupsPerDay, Duration.ofDays(1)), FailureMode.LOCAL_FALLBACK);
        if (!budget.allowed()) {
            throw new RateLimitedException("LOOKUP_LIMIT_REACHED",
                    "Too many phone-number lookups for this provider today. Please try again tomorrow.",
                    budget.retryAfterSeconds());
        }
        return accountLookup.findActiveAccountIdByPhone(targetPhone)
                .orElseThrow(RoleManagementException::targetAccountNotFound);
    }

    /**
     * The provider of an offer addressed to the caller's phone. An offer for anyone else's phone is "not found", exactly
     * like a missing one.
     */
    private UUID providerOfMyOffer(UUID accountId, UUID offerId) {
        Objects.requireNonNull(offerId, "offerId");
        String phone = OfferPhones.canonical(accountLookup.findPhonesByIds(List.of(accountId)).get(accountId));
        if (phone == null || !offerRepository.existsByIdAndTargetPhone(offerId, phone)) {
            throw RoleManagementException.offerNotFound();
        }
        return offerRepository.findProviderIdById(offerId).orElseThrow(RoleManagementException::offerNotFound);
    }

    /** PENDING and unexpired, or the right error; a lapsed offer is recorded as EXPIRED first. */
    private void requireOpen(OwnershipOffer offer, Instant now) {
        if (offer.getStatus() != OwnershipOfferStatus.PENDING) {
            throw RoleManagementException.offerNotPending();
        }
        if (!offer.isOpen(now)) {
            offer.expire(now);
            offerRepository.saveAndFlush(offer);
            throw RoleManagementException.offerExpired();
        }
    }

    private String providerName(UUID providerId) {
        return repository.findProviderName(providerId).orElse("your provider");
    }

    private void audit(RoleAuditAction action, UUID providerId, UUID actor, UUID target, ProviderRole oldRole,
            ProviderRole newRole) {
        auditRepository.save(new ProviderRoleAudit(providerId, actor, target, action, oldRole, newRole));
    }

    private List<RoleAssignmentView> toViews(List<ProviderRoleAssignment> rows) {
        Map<UUID, String> phones = accountLookup.findPhonesByIds(
                rows.stream().map(ProviderRoleAssignment::getAccountId).distinct().toList());
        return rows.stream().map(a -> RoleAssignmentView.from(a, phones.get(a.getAccountId()))).toList();
    }
}