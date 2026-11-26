package com.firstfood.membership;

import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitRule;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.ratelimit.RateLimitedException;
import com.firstfood.identity.AccountLookupService;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipServiceImpl implements MembershipService {

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final AccountLookupService accountLookup;
    private final PersonProvisioner provisioner;
    private final PersonRepository personRepository;
    private final ProviderMembershipRepository membershipRepository;
    private final List<MembershipDeactivationGuard> deactivationGuards;
    private final MembershipInvitationRepository invitationRepository;
    private final RateLimitService rateLimitService;
    private final Duration invitationTtl;
    private final int maxInvitationsPerProviderPerDay;

    public MembershipServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            AccountLookupService accountLookup,
            PersonProvisioner provisioner,
            PersonRepository personRepository,
            ProviderMembershipRepository membershipRepository,
            List<MembershipDeactivationGuard> deactivationGuards,
            MembershipInvitationRepository invitationRepository,
            RateLimitService rateLimitService,
            @Value("${app.membership.invitation-ttl-days:7}") int invitationTtlDays,
            @Value("${app.membership.max-invitations-per-provider-per-day:100}") int maxInvitationsPerProviderPerDay) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.accountLookup = accountLookup;
        this.provisioner = provisioner;
        this.personRepository = personRepository;
        this.membershipRepository = membershipRepository;
        this.deactivationGuards = deactivationGuards;
        this.invitationRepository = invitationRepository;
        this.rateLimitService = rateLimitService;
        this.invitationTtl = Duration.ofDays(Math.max(1, invitationTtlDays));
        this.maxInvitationsPerProviderPerDay = Math.max(1, maxInvitationsPerProviderPerDay);
    }

    @Override
    @Transactional
    public InvitationView inviteCustomer(UUID actorAccountId, UUID providerId, String phone) {
        // Authorize BEFORE locking so outsiders can never hold or contend for a provider's row lock.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_MANAGE);
        // Serializes concurrent changes for this provider and gives a stable status/intake view.
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        if (!intake.acceptingNewCustomers()) {
            throw MembershipException.providerNotAccepting();
        }
        String canonical = PhoneKeys.canonical(phone);
        if (canonical == null) {
            throw MembershipException.invalidPhone();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // The one fact this may reveal: the number is ALREADY a customer of THIS provider, which the provider's own
        // customer list shows anyway. Nothing about accounts that are not members of this provider.
        if (isActiveMemberOfProvider(providerId, canonical)) {
            throw MembershipException.alreadyActive();
        }

        Optional<MembershipInvitation> open = invitationRepository
                .findByProviderIdAndInvitedPhoneAndStatus(providerId, canonical, InvitationStatus.PENDING);
        if (open.isPresent()) {
            MembershipInvitation existing = open.get();
            if (existing.isOpen(now)) {
                return InvitationView.from(existing, now); // idempotent re-invite
            }
            existing.expire(now);
            invitationRepository.saveAndFlush(existing); // frees the one-pending-per-phone slot
        }

        // Every NEW invitation probes a phone number, so new ones are budgeted per provider per day.
        RateLimitDecision budget = rateLimitService.hit(RateLimitPolicies.PROVIDER_INVITE, providerId.toString(),
                new RateLimitRule(maxInvitationsPerProviderPerDay, Duration.ofDays(1)), FailureMode.LOCAL_FALLBACK);
        if (!budget.allowed()) {
            throw new RateLimitedException("INVITATION_LIMIT_REACHED",
                    "This provider has sent too many invitations today. Please try again tomorrow.",
                    budget.retryAfterSeconds());
        }

        MembershipInvitation created = new MembershipInvitation(providerId, canonical, actorAccountId,
                now.plus(invitationTtl));
        try {
            created = invitationRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            // Backstop for the one-pending-per-phone index; the provider lock normally prevents reaching it.
            return invitationRepository
                    .findByProviderIdAndInvitedPhoneAndStatus(providerId, canonical, InvitationStatus.PENDING)
                    .map(i -> InvitationView.from(i, now))
                    .orElseThrow(() -> e);
        }
        return InvitationView.from(created, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationView> listInvitations(UUID actorAccountId, UUID providerId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_VIEW);
        Instant now = Instant.now();
        return invitationRepository.findByProviderIdOrderByCreatedAtDesc(providerId).stream()
                .map(i -> InvitationView.from(i, now))
                .toList();
    }

    @Override
    @Transactional
    public InvitationView withdrawInvitation(UUID actorAccountId, UUID providerId, UUID invitationId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_MANAGE);
        // Scoped by provider: another provider's invitation is reported exactly like a missing one.
        if (!invitationRepository.existsByIdAndProviderId(invitationId, providerId)) {
            throw MembershipException.invitationNotFound();
        }
        MembershipInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
                .orElseThrow(MembershipException::invitationNotFound);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw MembershipException.invitationNotPending();
        }
        if (invitation.isOpen(now)) {
            invitation.withdraw(actorAccountId, now);
        } else {
            invitation.expire(now); // nothing left to withdraw: just record that it lapsed
        }
        return InvitationView.from(invitationRepository.saveAndFlush(invitation), now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyInvitationView> listMyInvitations(UUID accountId) {
        String phone = PhoneKeys.canonical(phoneOfAccount(accountId));
        if (phone == null) {
            return List.of();
        }
        Instant now = Instant.now();
        List<MembershipInvitation> open = invitationRepository
                .findByInvitedPhoneAndStatusOrderByCreatedAtDesc(phone, InvitationStatus.PENDING).stream()
                .filter(i -> i.isOpen(now))
                .toList();
        Map<UUID, String> names = providerLookup.findNames(
                open.stream().map(MembershipInvitation::getProviderId).collect(Collectors.toSet()));
        return open.stream()
                .map(i -> new MyInvitationView(i.getId(), i.getProviderId(),
                        names.getOrDefault(i.getProviderId(), "(unknown provider)"), i.getCreatedAt(), i.getExpiresAt()))
                .toList();
    }

    /** noRollbackFor: lapsing an expired invitation must persist even though the caller is told it expired. */
    @Override
    @Transactional(noRollbackFor = MembershipException.class)
    public MyMembershipView acceptInvitation(UUID accountId, UUID invitationId) {
        MembershipInvitation invitation = requireMyInvitation(accountId, invitationId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        requireOpen(invitation, now);

        ProviderIntake intake = providerLookup.lockForIntake(invitation.getProviderId());
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        // Throws PERSON_NAME_REQUIRED before anything is written when the caller has no profile yet. The name is the
        // customer's own: a provider never supplies it.
        Person person = provisioner.ensurePrimary(accountId, null);

        ProviderMembership membership = membershipRepository
                .findByProviderIdAndPersonIdAndStatus(invitation.getProviderId(), person.getId(), MembershipStatus.ACTIVE)
                .orElse(null);
        if (membership == null) {
            if (!intake.acceptingNewCustomers()) {
                throw MembershipException.providerNotAccepting();
            }
            try {
                membership = membershipRepository.saveAndFlush(new ProviderMembership(
                        invitation.getProviderId(), person.getId(), invitation.getInvitedBy(), now));
            } catch (DataIntegrityViolationException e) {
                throw MembershipException.alreadyActive();
            }
        }
        invitation.accept(accountId, membership.getId(), now);
        invitationRepository.saveAndFlush(invitation);
        return myView(membership);
    }

    @Override
    @Transactional(noRollbackFor = MembershipException.class)
    public void declineInvitation(UUID accountId, UUID invitationId) {
        MembershipInvitation invitation = requireMyInvitation(accountId, invitationId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        requireOpen(invitation, now);
        invitation.decline(accountId, now);
        invitationRepository.saveAndFlush(invitation);
    }

    @Override
    @Transactional
    public MyMembershipView leave(UUID accountId, UUID membershipId) {
        Set<UUID> myPersons = personRepository.findIdsByUserAccountId(accountId);
        if (myPersons.isEmpty()) {
            throw MembershipException.membershipNotFound();
        }
        // Someone else's membership is reported exactly like a missing one.
        UUID providerId = membershipRepository.findProviderIdByIdAndPersonIdIn(membershipId, myPersons)
                .orElseThrow(MembershipException::membershipNotFound);
        // Same serialization as staff-side changes. NOT guarded on closed: a customer may always walk away.
        providerLookup.lockForIntake(providerId);
        ProviderMembership membership = membershipRepository.findByIdForUpdate(membershipId)
                .orElseThrow(MembershipException::membershipNotFound);
        if (!membership.isActive()) {
            throw MembershipException.alreadyInactive();
        }
        membership.deactivate(accountId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        return myView(membershipRepository.saveAndFlush(membership));
    }

    private MembershipInvitation requireMyInvitation(UUID accountId, UUID invitationId) {
        String myPhone = PhoneKeys.canonical(phoneOfAccount(accountId));
        // Someone else's invitation is reported exactly like a missing one.
        if (myPhone == null || !invitationRepository.existsByIdAndInvitedPhone(invitationId, myPhone)) {
            throw MembershipException.invitationNotFound();
        }
        // Read under a row lock so two parallel answers cannot both succeed.
        return invitationRepository.findByIdForUpdate(invitationId).orElseThrow(MembershipException::invitationNotFound);
    }

    private void requireOpen(MembershipInvitation invitation, Instant now) {
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw MembershipException.invitationNotPending();
        }
        if (!invitation.isOpen(now)) {
            invitation.expire(now);
            invitationRepository.saveAndFlush(invitation);
            throw MembershipException.invitationExpired();
        }
    }

    private boolean isActiveMemberOfProvider(UUID providerId, String canonicalPhone) {
        return accountLookup.findActiveAccountIdByPhone(canonicalPhone)
                .flatMap(provisioner::findPrimary)
                .flatMap(person -> membershipRepository.findByProviderIdAndPersonIdAndStatus(
                        providerId, person.getId(), MembershipStatus.ACTIVE))
                .isPresent();
    }

    private String phoneOfAccount(UUID accountId) {
        return accountLookup.findCustomerPhonesByIds(Set.of(accountId)).get(accountId);
    }

    private MyMembershipView myView(ProviderMembership m) {
        String providerName = providerLookup.findNames(Set.of(m.getProviderId()))
                .getOrDefault(m.getProviderId(), "(unknown provider)");
        return new MyMembershipView(m.getId(), m.getProviderId(), providerName, m.getPersonId(), m.getStatus(),
                m.getJoinedAt(), m.getLeftAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> listCustomers(UUID actorAccountId, UUID providerId, boolean includeInactive) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_VIEW);
        List<ProviderMembership> memberships = includeInactive
                ? membershipRepository.findByProviderIdOrderByJoinedAtDesc(providerId)
                : membershipRepository.findByProviderIdAndStatusOrderByJoinedAtDesc(providerId,
                        MembershipStatus.ACTIVE);
        return toViews(memberships);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerView getCustomer(UUID actorAccountId, UUID providerId, UUID membershipId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_VIEW);
        ProviderMembership membership = membershipRepository.findByIdAndProviderId(membershipId, providerId)
                .orElseThrow(MembershipException::membershipNotFound);
        return toViews(List.of(membership)).get(0);
    }

    @Override
    @Transactional
    public CustomerView deactivate(UUID actorAccountId, UUID providerId, UUID membershipId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.MEMBERSHIP_MANAGE);
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        ProviderMembership membership = membershipRepository.findByIdAndProviderId(membershipId, providerId)
                .orElseThrow(MembershipException::membershipNotFound);
        if (!membership.isActive()) {
            throw MembershipException.alreadyInactive();
        }
        // Other modules may veto (e.g. a customer with a running subscription must be cancelled first).
        deactivationGuards.forEach(guard -> guard.assertCanDeactivate(providerId, membershipId));
        Instant leftAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        membership.deactivate(actorAccountId, leftAt);
        membership = membershipRepository.saveAndFlush(membership);
        return toViews(List.of(membership)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyMembershipView> listMine(UUID accountId) {
        Set<UUID> personIds = personRepository.findIdsByUserAccountId(accountId);
        if (personIds.isEmpty()) {
            return List.of();
        }
        List<ProviderMembership> memberships = membershipRepository.findByPersonIdInOrderByJoinedAtDesc(personIds);
        Map<UUID, String> names = providerLookup.findNames(
                memberships.stream().map(ProviderMembership::getProviderId).collect(Collectors.toSet()));
        return memberships.stream()
                .map(m -> new MyMembershipView(m.getId(), m.getProviderId(),
                        names.getOrDefault(m.getProviderId(), "(unknown provider)"), m.getPersonId(), m.getStatus(),
                        m.getJoinedAt(), m.getLeftAt()))
                .toList();
    }

    private List<CustomerView> toViews(List<ProviderMembership> memberships) {
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<UUID, Person> persons = new HashMap<>();
        personRepository.findAllById(memberships.stream().map(ProviderMembership::getPersonId).collect(Collectors.toSet()))
                .forEach(p -> persons.put(p.getId(), p));
        Map<UUID, String> phones = accountLookup.findCustomerPhonesByIds(
                persons.values().stream().map(Person::getUserAccountId).collect(Collectors.toSet()));
        return memberships.stream()
                .map(m -> {
                    Person person = persons.get(m.getPersonId());
                    return toView(m, person, phones.get(person.getUserAccountId()));
                })
                .toList();
    }

    private CustomerView toView(ProviderMembership m, Person person, String phone) {
        return CustomerView.from(m, person, phone);
    }

    @Override
    @Transactional
    public MyMembershipView joinProvider(UUID accountId, UUID providerId) {
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw MembershipException.providerClosed();
        }
        Person person = provisioner.ensurePrimary(accountId, null);

        ProviderMembership membership = membershipRepository
                .findByProviderIdAndPersonIdAndStatus(providerId, person.getId(), MembershipStatus.ACTIVE)
                .orElse(null);
        if (membership == null) {
            if (!intake.acceptingNewCustomers()) {
                throw MembershipException.providerNotAccepting();
            }
            try {
                membership = membershipRepository.saveAndFlush(
                        new ProviderMembership(providerId, person.getId(), accountId, Instant.now()));
            } catch (DataIntegrityViolationException e) {
                // Backstop for the partial unique index; the provider lock normally prevents reaching it.
                throw MembershipException.alreadyActive();
            }
        }
        String providerName = providerLookup.findNames(Set.of(providerId))
                .getOrDefault(providerId, "(unknown provider)");
        return new MyMembershipView(membership.getId(), providerId, providerName, membership.getPersonId(),
                membership.getStatus(), membership.getJoinedAt(), membership.getLeftAt());
    }
}
