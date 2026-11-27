package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.membership.MembershipRef;
import com.firstfood.plan.ConsumptionType;
import com.firstfood.plan.PlanLookupService;
import com.firstfood.plan.PlanOffer;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    /** Stands in for "no end" when checking overlap of a subscription without a calendar window. */
    private static final LocalDate OPEN_END = LocalDate.of(9999, 12, 31);

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final PlanLookupService planLookup;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionTermSnapshotRepository snapshots;
    private final SubscriptionVerificationRepository verifications;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final List<RenewalGuard> renewalGuards;
    private final SubscriptionProperties properties;
    private final SubscriptionExpiryAdjustmentRepository adjustments;

    public SubscriptionServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup,
            PlanLookupService planLookup,
            SubscriptionRepository subscriptions,
            SubscriptionTermSnapshotRepository snapshots,
            SubscriptionVerificationRepository verifications,
            BusinessCalendar calendar,
            Clock clock,
            List<RenewalGuard> renewalGuards,
            SubscriptionProperties properties,
            SubscriptionExpiryAdjustmentRepository adjustments) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.planLookup = planLookup;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.verifications = verifications;
        this.calendar = calendar;
        this.clock = clock;
        this.renewalGuards = renewalGuards;
        this.properties = properties;
        this.adjustments = adjustments;
    }

    @Override
    @Transactional
    public SubscriptionView create(
            UUID actorAccountId, UUID providerId, UUID membershipId, UUID planId, LocalDate startDate) {
        // Authorize BEFORE locking so outsiders can never hold or contend for a provider's row lock.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        ProviderIntake intake = lockOpen(providerId);

        MembershipRef membership = membershipLookup.find(providerId, membershipId)
                .orElseThrow(SubscriptionException::membershipNotFound);
        if (!membership.active()) {
            throw SubscriptionException.membershipNotActive();
        }
        PlanOffer offer = requireSellable(providerId, planId);
        return sell(actorAccountId, intake, membership, offer, startDate, null, false);
    }

    @Override
    @Transactional
    public MySubscriptionView subscribeSelf(
            UUID accountId, UUID providerId, UUID membershipId, UUID planId, LocalDate startDate) {
        ProviderIntake intake = lockOpen(providerId);

        Set<UUID> myPersons = membershipLookup.personIdsOf(accountId);
        // Someone else's membership is indistinguishable from a missing one.
        MembershipRef membership = membershipLookup.find(providerId, membershipId)
                .filter(m -> myPersons.contains(m.personId()))
                .orElseThrow(SubscriptionException::membershipNotFound);
        if (!membership.active()) {
            throw SubscriptionException.membershipNotActive();
        }
        PlanOffer offer = requireSellable(providerId, planId);
        SubscriptionView sold = sell(accountId, intake, membership, offer, startDate, null, true);
        Subscription created = subscriptions.findByIdAndPersonIdIn(sold.id(), myPersons)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toMyViews(List.of(created)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionView> list(
            UUID actorAccountId, UUID providerId, UUID membershipId, SubscriptionStatus status) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        List<Subscription> found;
        if (membershipId != null && status != null) {
            found = subscriptions.findByProviderIdAndMembershipIdAndStatusOrderByCreatedAtDesc(
                    providerId, membershipId, status);
        } else if (membershipId != null) {
            found = subscriptions.findByProviderIdAndMembershipIdOrderByCreatedAtDesc(providerId, membershipId);
        } else if (status != null) {
            found = subscriptions.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, status);
        } else {
            found = subscriptions.findByProviderIdOrderByCreatedAtDesc(providerId);
        }
        return toViews(found);
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionView get(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        Subscription s = subscriptions.findByIdAndProviderId(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toViews(List.of(s)).get(0);
    }

    @Override
    @Transactional
    public SubscriptionView cancel(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        lockOpen(providerId);
        // Phase 8: the subscription row lock too, so a cancel and an attendance change on the same subscription
        // are serialized (lock order everywhere: provider row, then subscription row).
        Subscription s = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        SubscriptionPhase phase = s.phaseOn(calendar.today());
        if (phase != SubscriptionPhase.RUNNING && phase != SubscriptionPhase.UPCOMING) {
            throw SubscriptionException.notActive();
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        s.cancel(actorAccountId, cleanReason, now());
        s = subscriptions.saveAndFlush(s);
        return toViews(List.of(s)).get(0);
    }

    @Override
    @Transactional
    public SubscriptionView renew(
            UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID planId, LocalDate startDate) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        ProviderIntake intake = lockOpen(providerId);

        // Phase 9: the subscription row lock too (lock order everywhere: provider row, then subscription row), so a
        // renewal and an extension of the same subscription are serialized and each re-reads the other's outcome.
        Subscription previous = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return renewLocked(actorAccountId, intake, providerId, previous, planId, startDate, false);
    }

    @Override
    @Transactional
    public MySubscriptionView renewSelf(UUID accountId, UUID subscriptionId, UUID planId, LocalDate startDate) {
        Set<UUID> myPersons = membershipLookup.personIdsOf(accountId);
        if (myPersons.isEmpty()) {
            throw SubscriptionException.subscriptionNotFound();
        }
        // Unlocked read only to learn the provider (someone else's subscription is indistinguishable from a missing
        // one); the real checks run under the same locks, in the same order, as a provider-side renewal.
        UUID providerId = subscriptions.findByIdAndPersonIdIn(subscriptionId, myPersons)
                .orElseThrow(SubscriptionException::subscriptionNotFound).getProviderId();
        ProviderIntake intake = lockOpen(providerId);
        Subscription previous = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .filter(s -> myPersons.contains(s.getPersonId()))
                .orElseThrow(SubscriptionException::subscriptionNotFound);

        SubscriptionView renewed = renewLocked(accountId, intake, providerId, previous, planId, startDate, true);
        Subscription created = subscriptions.findByIdAndPersonIdIn(renewed.id(), myPersons)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toMyViews(List.of(created)).get(0);
    }

    /** The renewal rules, shared by provider-side and self-service renewal. Caller holds the provider and subscription locks. */
    private SubscriptionView renewLocked(UUID actorAccountId, ProviderIntake intake, UUID providerId,
            Subscription previous, UUID planId, LocalDate startDate, boolean selfService) {
        LocalDate today = calendar.today();
        if (previous.phaseOn(today) != SubscriptionPhase.ENDED) {
            throw SubscriptionException.notRenewable();
        }
        if (subscriptions.existsByRenewedFromSubscriptionId(previous.getId())) {
            throw SubscriptionException.alreadyRenewed();
        }
        // Renewal "close to expiry" (rules.md Rule 21.1): days the customer earned but nobody has applied yet
        // would be lost once the successor starts, so a guard may refuse until they are applied.
        renewalGuards.forEach(guard -> guard.assertCanRenew(providerId, previous.getId()));
        MembershipRef membership = membershipLookup.find(providerId, previous.getMembershipId())
                .orElseThrow(SubscriptionException::membershipNotFound);
        if (!membership.active()) {
            throw SubscriptionException.membershipNotActive();
        }
        PlanOffer offer = requireSellable(providerId, planId != null ? planId : previous.getPlanId());

        LocalDate previousEnd = previous.getEffectiveExpiryDate();
        if (startDate != null && previousEnd != null && !startDate.isAfter(previousEnd)) {
            throw SubscriptionException.startInvalid("A renewal must start after the previous subscription ended ("
                    + previousEnd + ").");
        }
        // Lapsed by date but still stored ACTIVE (no background job yet): record that it has ended,
        // so it stops counting as an ACTIVE row for overlap and capacity.
        if (previous.getStatus() == SubscriptionStatus.ACTIVE) {
            previous.expire(now());
            subscriptions.saveAndFlush(previous);
        }
        return sell(actorAccountId, intake, membership, offer, startDate, previous.getId(), selfService);
    }

    @Override
    @Transactional
    public SubscriptionView verify(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        SubscriptionVerification v = lockPendingForDecision(actorAccountId, providerId, subscriptionId);
        v.verify(actorAccountId, now());
        verifications.saveAndFlush(v);
        return get(actorAccountId, providerId, subscriptionId);
    }

    @Override
    @Transactional
    public SubscriptionView reject(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.isEmpty()) {
            throw SubscriptionException.reasonRequired();
        }
        if (clean.length() > 500) {
            throw SubscriptionException.startInvalid("The reason can be at most 500 characters.");
        }
        SubscriptionVerification v = lockPendingForDecision(actorAccountId, providerId, subscriptionId);
        Instant at = now();
        v.reject(actorAccountId, clean, at);
        verifications.saveAndFlush(v);
        // A rejected subscription never becomes entitlement: cancel it so it frees its dates and capacity.
        Subscription s = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        if (s.getStatus() == SubscriptionStatus.ACTIVE) {
            s.cancel(actorAccountId, "Rejected: " + clean, at);
            subscriptions.saveAndFlush(s);
        }
        return get(actorAccountId, providerId, subscriptionId);
    }

    /** Authorize, lock provider then subscription then the verification row, and require it to be PENDING. */
    private SubscriptionVerification lockPendingForDecision(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        lockOpen(providerId);
        Subscription s = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        SubscriptionVerification v = verifications.findForDecision(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        if (membershipLookup.personIdsOf(actorAccountId).contains(s.getPersonId())) {
            throw SubscriptionException.ownSubscription();
        }
        if (!v.isPending()) {
            throw SubscriptionException.verificationNotPending();
        }
        return v;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MySubscriptionView> listMine(UUID accountId) {
        Set<UUID> personIds = membershipLookup.personIdsOf(accountId);
        if (personIds.isEmpty()) {
            return List.of();
        }
        return toMyViews(subscriptions.findByPersonIdInOrderByCreatedAtDesc(personIds));
    }

    @Override
    @Transactional(readOnly = true)
    public MySubscriptionView getMine(UUID accountId, UUID subscriptionId) {
        Set<UUID> personIds = membershipLookup.personIdsOf(accountId);
        if (personIds.isEmpty()) {
            throw SubscriptionException.subscriptionNotFound();
        }
        Subscription s = subscriptions.findByIdAndPersonIdIn(subscriptionId, personIds)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toMyViews(List.of(s)).get(0);
    }

    // ---- the one place a subscription is created ----

    /** Shared by sale and renewal: dates, capacity, overlap, then subscription + snapshot together. */
    private SubscriptionView sell(UUID actorAccountId, ProviderIntake intake, MembershipRef membership,
            PlanOffer offer, LocalDate requestedStart, UUID renewedFromId, boolean selfService) {
        LocalDate today = calendar.today();
        LocalDate start = requestedStart != null ? requestedStart : today;
        SubscriptionRules.validateStart(start, today);
        LocalDate baseExpiry = SubscriptionRules.baseExpiry(
                offer.consumptionType(), start, offer.durationDays(), offer.policy().maxCalendarWindowDays());
        SubscriptionRules.validateNotAlreadyEnded(baseExpiry, today);

        UUID providerId = membership.providerId();
        Instant pendingCutoff = now().minus(properties.pendingTtl());
        if (selfService) {
            // F-06: a customer may wait on only a few unconfirmed requests at once, across all providers. (Counted under
            // the provider lock only, so two requests to DIFFERENT providers racing can overshoot by one; the cap is a
            // brake on mass-squatting, not an exact quota.) Staff sales are never limited by this.
            if (verifications.countFreshPendingByRequester(actorAccountId, pendingCutoff)
                    >= properties.maxPendingPerAccount()) {
                throw SubscriptionException.tooManyPending(properties.maxPendingPerAccount());
            }
        }
        Integer max = intake.maxActiveSubscriptions();
        // F-06: only confirmed subscriptions and FRESH unconfirmed requests hold a seat.
        if (max != null && subscriptions.countLiveByProvider(providerId, today, pendingCutoff) >= max) {
            throw SubscriptionException.providerAtCapacity();
        }
        LocalDate end = baseExpiry != null ? baseExpiry : OPEN_END;
        if (subscriptions.countActiveOverlapping(membership.membershipId(), start, end) > 0) {
            throw SubscriptionException.overlap();
        }

        Integer meals = offer.consumptionType() == ConsumptionType.MEAL ? offer.mealQuantity() : null;
        Subscription subscription = new Subscription(providerId, membership.membershipId(), membership.personId(),
                offer.planId(), offer.consumptionType(), start, baseExpiry, meals, renewedFromId, actorAccountId);
        SubscriptionTermSnapshot terms = SubscriptionTermSnapshot.capture(subscription.getId(), offer, now());
        SubscriptionVerification verification;
        try {
            subscription = subscriptions.saveAndFlush(subscription);
            terms = snapshots.saveAndFlush(terms);
            // A customer's own purchase waits for staff; a sale made by staff is confirmed by the seller.
            Instant at = now();
            verification = verifications.saveAndFlush(selfService
                    ? SubscriptionVerification.pending(subscription.getId(), providerId, actorAccountId, at)
                    : SubscriptionVerification.verifiedBySeller(subscription.getId(), providerId, actorAccountId, at));
        } catch (DataIntegrityViolationException e) {
            // Backstops for the database constraints; the provider lock normally prevents reaching them.
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("subscription_no_overlapping_active")) {
                throw SubscriptionException.overlap();
            }
            if (detail.contains("uq_subscription_one_renewal")) {
                throw SubscriptionException.alreadyRenewed();
            }
            throw e;
        }
        MemberSummary member = membershipLookup.summarize(List.of(membership.membershipId()))
                .get(membership.membershipId());
        return SubscriptionView.from(subscription, terms, member, false, today, verification, properties.pendingTtl(), 0);
    }

    // ---- helpers ----

    private ProviderIntake lockOpen(UUID providerId) {
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw SubscriptionException.providerClosed();
        }
        return intake;
    }

    private PlanOffer requireSellable(UUID providerId, UUID planId) {
        PlanOffer offer = planLookup.findOffer(providerId, planId).orElseThrow(SubscriptionException::planNotFound);
        if (!offer.active()) {
            throw SubscriptionException.planNotActive();
        }
        return offer;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private List<SubscriptionView> toViews(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        LocalDate today = calendar.today();
        List<UUID> ids = found.stream().map(Subscription::getId).toList();
        Map<UUID, SubscriptionTermSnapshot> terms = termsBySubscription(ids);
        Map<UUID, MemberSummary> members = membershipLookup.summarize(
                found.stream().map(Subscription::getMembershipId).collect(Collectors.toSet()));
        Set<UUID> renewed = new HashSet<>(subscriptions.findRenewedIds(ids));
        Map<UUID, SubscriptionVerification> checks = verificationsBySubscription(ids);
        Map<UUID, Long> adjusted = new HashMap<>();
        adjustments.totalsFor(ids).forEach(t -> adjusted.put(t.subscriptionId(), t.days()));
        return found.stream()
                .map(s -> SubscriptionView.from(s, terms.get(s.getId()), members.get(s.getMembershipId()),
                        renewed.contains(s.getId()), today, checks.get(s.getId()), properties.pendingTtl(),
                        adjusted.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    private List<MySubscriptionView> toMyViews(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        LocalDate today = calendar.today();
        Map<UUID, SubscriptionTermSnapshot> terms = termsBySubscription(
                found.stream().map(Subscription::getId).toList());
        Map<UUID, String> names = providerLookup.findNames(
                found.stream().map(Subscription::getProviderId).collect(Collectors.toSet()));
        List<UUID> foundIds = found.stream().map(Subscription::getId).toList();
        Set<UUID> renewed = new HashSet<>(subscriptions.findRenewedIds(foundIds));
        Map<UUID, SubscriptionVerification> checks = verificationsBySubscription(foundIds);
        return found.stream()
                .map(s -> MySubscriptionView.from(s, terms.get(s.getId()),
                        names.getOrDefault(s.getProviderId(), "(unknown provider)"), renewed.contains(s.getId()), today,
                        checks.get(s.getId()), properties.pendingTtl()))
                .toList();
    }

    private Map<UUID, SubscriptionVerification> verificationsBySubscription(List<UUID> subscriptionIds) {
        Map<UUID, SubscriptionVerification> map = new HashMap<>();
        verifications.findBySubscriptionIdIn(subscriptionIds).forEach(v -> map.put(v.getSubscriptionId(), v));
        return map;
    }

    private Map<UUID, SubscriptionTermSnapshot> termsBySubscription(List<UUID> subscriptionIds) {
        Map<UUID, SubscriptionTermSnapshot> map = new HashMap<>();
        snapshots.findBySubscriptionIdIn(subscriptionIds).forEach(t -> map.put(t.getSubscriptionId(), t));
        return map;
    }
}
