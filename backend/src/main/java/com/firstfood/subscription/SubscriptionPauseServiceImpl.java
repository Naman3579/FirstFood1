package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.plan.ConsumptionType;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.subscription.PauseRules.DateRange;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case orchestration for vacation. The arithmetic is in {@link PauseRules}; this class decides who may do what, takes
 * the subscription row lock and keeps the pause row, {@code subscription.paused_since} and the expiry telling one story.
 *
 * <h3>Resume = one transaction</h3>
 * lock the subscription row -> find the OPEN vacation -> compute the days to give back -> insert the
 * {@link SubscriptionExpiryAdjustment} (its V25 guard checks it starts from the expiry the subscription really has) ->
 * move the expiry -> close the vacation. V25 checks again at commit that expiry = base + extension events + adjustments.
 *
 * <h3>Idempotency</h3>
 * The row lock serializes two resume requests; the second finds no OPEN vacation and returns the resolved one without
 * writing. If something raced past the lock, {@code uq_adjustment_pause} refuses the second adjustment and the caller
 * gets a clean 409.
 *
 * Lock order: only the subscription row (like attendance and extension), so a customer's vacation never queues behind a
 * provider-wide lock. A closure takes provider row then subscription rows, which cannot cycle with this.
 */
@Service
class SubscriptionPauseServiceImpl implements SubscriptionPauseService {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPauseRepository pauses;
    private final SubscriptionExpiryAdjustmentRepository adjustments;
    private final SubscriptionVerificationRepository verifications;
    private final MembershipLookupService membershipLookup;
    private final ProviderLookupService providerLookup;
    private final BusinessCalendar calendar;
    private final Clock clock;

    SubscriptionPauseServiceImpl(SubscriptionRepository subscriptions, SubscriptionPauseRepository pauses,
            SubscriptionExpiryAdjustmentRepository adjustments, SubscriptionVerificationRepository verifications,
            MembershipLookupService membershipLookup, ProviderLookupService providerLookup,
            BusinessCalendar calendar, Clock clock) {
        this.subscriptions = subscriptions;
        this.pauses = pauses;
        this.adjustments = adjustments;
        this.verifications = verifications;
        this.membershipLookup = membershipLookup;
        this.providerLookup = providerLookup;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public VacationView status(UUID accountId, UUID subscriptionId) {
        Set<UUID> persons = membershipLookup.personIdsOf(accountId);
        Subscription s = persons.isEmpty() ? null
                : subscriptions.findByIdAndPersonIdIn(subscriptionId, persons).orElse(null);
        if (s == null) {
            throw SubscriptionException.subscriptionNotFound();
        }
        LocalDate today = calendar.today();
        List<DateRange> closureCovered = closureCovered(s.getId());
        Optional<SubscriptionException> refusal = refusal(s, today);
        List<SubscriptionPause> all = pauses.findBySubscriptionIdOrderByStartDateDescCreatedAtDesc(s.getId());
        PauseView open = all.stream().filter(p -> p.getStatus() == PauseStatus.OPEN).findFirst()
                .map(p -> view(p, s, today, closureCovered)).orElse(null);
        List<PauseView> history = all.stream().filter(p -> p.getStatus() != PauseStatus.OPEN)
                .map(p -> view(p, s, today, closureCovered)).toList();
        boolean canStart = refusal.isEmpty() && open == null;
        String reason = open != null ? "A vacation is already open." : refusal.map(SubscriptionException::getMessage).orElse(null);
        return new VacationView(s.getId(), canStart, reason, s.getBaseExpiryDate(), s.getEffectiveExpiryDate(), open,
                history);
    }

    @Override
    @Transactional
    public PauseView start(UUID accountId, UUID subscriptionId, LocalDate startDate) {
        Subscription s = lockOwned(accountId, subscriptionId);
        LocalDate today = calendar.today();
        Optional<SubscriptionException> refusal = refusal(s, today);
        if (refusal.isPresent()) {
            throw refusal.get();
        }
        if (pauses.findBySubscriptionIdAndStatus(s.getId(), PauseStatus.OPEN).isPresent()) {
            throw SubscriptionException.vacationAlreadyOpen();
        }
        LocalDate start = startDate == null ? today : startDate;
        PauseRules.validateStart(start, today, s.getStartDate(), s.getEffectiveExpiryDate());

        SubscriptionPause pause = new SubscriptionPause(s.getId(), s.getProviderId(), start, accountId);
        s.markPaused(start);
        guarded(() -> {
            pauses.saveAndFlush(pause);
            subscriptions.saveAndFlush(s);
            return null;
        });
        return view(pause, s, today, closureCovered(s.getId()));
    }

    @Override
    @Transactional
    public PauseView resume(UUID accountId, UUID subscriptionId) {
        Subscription s = lockOwned(accountId, subscriptionId);
        LocalDate today = calendar.today();
        List<DateRange> closureCovered = closureCovered(s.getId());

        Optional<SubscriptionPause> open = pauses.findBySubscriptionIdAndStatus(s.getId(), PauseStatus.OPEN);
        if (open.isEmpty()) {
            // A repeated request (double tap, retry): report how the vacation was resolved, change nothing.
            SubscriptionPause latest = pauses.findBySubscriptionIdOrderByStartDateDescCreatedAtDesc(s.getId()).stream()
                    .findFirst().orElseThrow(SubscriptionException::noVacation);
            return view(latest, s, today, closureCovered);
        }
        SubscriptionPause pause = open.get();
        Instant now = clock.instant();
        if (s.getStatus() != SubscriptionStatus.ACTIVE) {
            throw SubscriptionException.vacationNotAllowed(
                    "This subscription has ended or been cancelled, so its vacation can no longer be changed.");
        }

        if (pause.getStartDate().isAfter(today)) {
            // Scheduled and not begun: calling it off withheld nothing.
            pause.cancel(accountId, now);
            s.clearPaused();
            guarded(() -> {
                pauses.saveAndFlush(pause);
                subscriptions.saveAndFlush(s);
                return null;
            });
            return view(pause, s, today, closureCovered);
        }

        int paused = PauseRules.pausedDays(pause.getStartDate(), today);
        int credit = PauseRules.vacationCredit(pause.getStartDate(), today, closureCovered);
        if (credit > 0) {
            SubscriptionExpiryAdjustment adjustment = SubscriptionExpiryAdjustment.forVacation(s.getId(),
                    s.getProviderId(), pause.getId(), pause.getStartDate(), today.minusDays(1), credit,
                    s.getEffectiveExpiryDate(), accountId);
            // The adjustment goes in BEFORE the expiry moves: V25's guard checks it starts from the stored expiry.
            guarded(() -> adjustments.saveAndFlush(adjustment));
            s.extendTo(adjustment.getNewExpiryDate());
        }
        pause.resume(today, paused, credit, accountId, now);
        s.clearPaused();
        guarded(() -> {
            pauses.saveAndFlush(pause);
            subscriptions.saveAndFlush(s);
            return null;
        });
        return view(pause, s, today, closureCovered);
    }

    // ================================================================ helpers

    private Subscription lockOwned(UUID accountId, UUID subscriptionId) {
        Set<UUID> persons = membershipLookup.personIdsOf(accountId);
        if (persons.isEmpty()) {
            throw SubscriptionException.subscriptionNotFound();
        }
        return subscriptions.findByIdAndPersonIdInForUpdate(subscriptionId, persons)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
    }

    /** Why a vacation cannot START on this subscription, or empty. */
    private Optional<SubscriptionException> refusal(Subscription s, LocalDate today) {
        if (s.getConsumptionType() != ConsumptionType.DAY) {
            return Optional.of(SubscriptionException.vacationNotSupportedForMeal());
        }
        if (s.getStatus() != SubscriptionStatus.ACTIVE || s.isLapsedOn(today)) {
            return Optional.of(SubscriptionException.vacationNotAllowed(
                    "This subscription has ended or been cancelled, so it cannot go on vacation."));
        }
        if (providerLookup.intakeOf(s.getProviderId()).closed()) {
            return Optional.of(SubscriptionException.providerClosed());
        }
        boolean verified = verifications.findBySubscriptionIdIn(List.of(s.getId())).stream()
                .allMatch(v -> v.getStatus() == VerificationStatus.VERIFIED);
        if (!verified) {
            return Optional.of(SubscriptionException.vacationNotAllowed(
                    "The provider has not confirmed this subscription yet. A vacation can start once it is confirmed."));
        }
        if (subscriptions.existsByRenewedFromSubscriptionId(s.getId())) {
            return Optional.of(SubscriptionException.vacationNotAllowed(
                    "This subscription has already been renewed, so its end date can no longer move."));
        }
        return Optional.empty();
    }

    private List<DateRange> closureCovered(UUID subscriptionId) {
        return adjustments.findBySubscriptionIdAndKindOrderByCreatedAtAsc(subscriptionId, AdjustmentKind.PROVIDER_CLOSURE)
                .stream().map(a -> new DateRange(a.getCoveredFrom(), a.getCoveredTo())).toList();
    }

    private static PauseView view(SubscriptionPause p, Subscription s, LocalDate today, List<DateRange> closureCovered) {
        boolean started = !p.getStartDate().isAfter(today);
        Integer projected = null;
        LocalDate projectedExpiry = null;
        if (p.getStatus() == PauseStatus.OPEN) {
            projected = started ? PauseRules.vacationCredit(p.getStartDate(), today, closureCovered) : 0;
            projectedExpiry = s.getEffectiveExpiryDate() == null ? null : s.getEffectiveExpiryDate().plusDays(projected);
        }
        return new PauseView(p.getId(), p.getSubscriptionId(), p.getStartDate(), p.getStatus(), started,
                p.getResumedOn(), p.getPausedDays(), p.getCreditedDays(), projected, projectedExpiry);
    }

    /** Backstop: a database refusal the row lock should make unreachable becomes a clean 409, not a 500. */
    private static <T> T guarded(Supplier<T> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("subscription_no_overlapping_active")) {
                throw SubscriptionException.vacationNotAllowed(
                        "Giving these days back would make this subscription overlap another active one of yours. "
                                + "Ask the provider to cancel or move the later subscription first.");
            }
            if (detail.contains("uq_pause_one_open") || detail.contains("pause_no_overlap")) {
                throw SubscriptionException.vacationAlreadyOpen();
            }
            if (detail.contains("uq_adjustment_pause") || detail.contains("currently expires on")
                    || detail.contains("can no longer change")) {
                throw SubscriptionException.vacationConcurrentChange();
            }
            if (detail.contains("has been renewed")) {
                throw SubscriptionException.vacationNotAllowed(
                        "This subscription has already been renewed, so its end date can no longer move.");
            }
            throw e;
        }
    }
}
