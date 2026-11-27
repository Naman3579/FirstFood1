package com.firstfood.subscription;

import com.firstfood.subscription.PauseRules.DateRange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ClosureCreditServiceImpl implements ClosureCreditService {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionTermSnapshotRepository snapshots;
    private final SubscriptionPauseRepository pauses;
    private final SubscriptionExpiryAdjustmentRepository adjustments;

    ClosureCreditServiceImpl(SubscriptionRepository subscriptions, SubscriptionTermSnapshotRepository snapshots,
            SubscriptionPauseRepository pauses, SubscriptionExpiryAdjustmentRepository adjustments) {
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.pauses = pauses;
        this.adjustments = adjustments;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClosureCredit> preview(UUID providerId, LocalDate from, LocalDate to) {
        List<ClosureCredit> out = new ArrayList<>();
        for (Subscription s : subscriptions.findClosureCandidates(providerId, from, to)) {
            int days = daysFor(s, from, to);
            if (days > 0) {
                out.add(new ClosureCredit(s.getId(), s.getMembershipId(), planName(s), s.getEffectiveExpiryDate(),
                        s.getEffectiveExpiryDate().plusDays(days), days, false));
            }
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClosureCredit> apply(UUID providerId, UUID closureId, LocalDate from, LocalDate to, UUID actorAccountId) {
        List<ClosureCredit> out = new ArrayList<>();
        // The candidate list is unlocked and ordered by id; each row is then locked (in that order, so two closures
        // or a closure and a vacation can never deadlock) and re-checked, because it may have changed meanwhile.
        for (Subscription candidate : subscriptions.findClosureCandidates(providerId, from, to)) {
            Subscription s = subscriptions.findByIdAndProviderIdForUpdate(candidate.getId(), providerId).orElse(null);
            if (s == null || s.getStatus() != SubscriptionStatus.ACTIVE
                    || adjustments.existsByClosureIdAndSubscriptionId(closureId, s.getId())
                    || subscriptions.existsByRenewedFromSubscriptionId(s.getId())) {
                continue;
            }
            DateRange window = PauseRules.closureWindow(from, to, s.getStartDate(), s.getEffectiveExpiryDate());
            int days = PauseRules.closureCredit(window, resumedVacations(s.getId()));
            if (days == 0) {
                continue;
            }
            SubscriptionExpiryAdjustment adjustment = SubscriptionExpiryAdjustment.forClosure(s.getId(),
                    s.getProviderId(), closureId, window.from(), window.to(), days, s.getEffectiveExpiryDate(),
                    actorAccountId);
            try {
                // The adjustment first (V25's guard checks it starts from the stored expiry), then the expiry moves.
                adjustments.saveAndFlush(adjustment);
                s.extendTo(adjustment.getNewExpiryDate());
                subscriptions.saveAndFlush(s);
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("subscription_no_overlapping_active")) {
                    throw SubscriptionException.vacationNotAllowed("Extending " + planName(s)
                            + " would overlap another active subscription of the same customer. Cancel or move the "
                            + "later one, then declare the closure again.");
                }
                throw e;
            }
            out.add(new ClosureCredit(s.getId(), s.getMembershipId(), planName(s), adjustment.getPreviousExpiryDate(),
                    adjustment.getNewExpiryDate(), days, true));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClosureCredit> appliedBy(UUID closureId) {
        List<ClosureCredit> out = new ArrayList<>();
        for (SubscriptionExpiryAdjustment a : adjustments.findByClosureIdOrderByCreatedAtAsc(closureId)) {
            Subscription s = subscriptions.findById(a.getSubscriptionId()).orElse(null);
            if (s != null) {
                out.add(new ClosureCredit(s.getId(), s.getMembershipId(), planName(s), a.getPreviousExpiryDate(),
                        a.getNewExpiryDate(), a.getDays(), true));
            }
        }
        return out;
    }

    private int daysFor(Subscription s, LocalDate from, LocalDate to) {
        DateRange window = PauseRules.closureWindow(from, to, s.getStartDate(), s.getEffectiveExpiryDate());
        return PauseRules.closureCredit(window, resumedVacations(s.getId()));
    }

    /** The days already-resumed vacations withheld: a closure must not pay for them a second time. */
    private List<DateRange> resumedVacations(UUID subscriptionId) {
        List<DateRange> ranges = new ArrayList<>();
        for (SubscriptionPause p : pauses.findBySubscriptionIdAndStatusOrderByStartDateAsc(
                subscriptionId, PauseStatus.RESUMED)) {
            DateRange withheld = PauseRules.withheldRange(p.getStartDate(), p.getResumedOn());
            if (withheld != null) {
                ranges.add(withheld);
            }
        }
        return ranges;
    }

    private String planName(Subscription s) {
        return snapshots.findBySubscriptionIdIn(List.of(s.getId())).stream().findFirst()
                .map(SubscriptionTermSnapshot::getPlanName).orElse("(unknown plan)");
    }
}
