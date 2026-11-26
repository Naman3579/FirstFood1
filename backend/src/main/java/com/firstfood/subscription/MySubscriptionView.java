package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A subscription as the customer who owns it sees it (any provider, current and past). */
public record MySubscriptionView(
        UUID id,
        UUID providerId,
        String providerName,
        UUID personId,
        UUID planId,
        ConsumptionType consumptionType,
        SubscriptionStatus status,
        SubscriptionPhase phase,
        LocalDate startDate,
        LocalDate effectiveExpiryDate,
        Integer remainingDays,
        Integer remainingMeals,
        Instant cancelledAt,
        boolean renewed,
        /** Ended and not yet renewed: the customer may renew it. The server still re-checks everything on renewal. */
        boolean renewable,
        /** Whether the provider has confirmed it yet; null only for rows that predate verification. */
        VerificationView verification,
        TermsView terms,
        /** V25: start of the open vacation (null = none; a future date = scheduled). */
        LocalDate pausedSince,
        /** V25: on vacation today (the stored expiry is not final until the customer resumes). */
        boolean onVacation,
        /** V25: whether a vacation can be started right now (DAY, confirmed, running or upcoming, not renewed, none open). */
        boolean canStartVacation) {

    static MySubscriptionView from(Subscription s, SubscriptionTermSnapshot terms, String providerName,
            boolean renewed, LocalDate today, SubscriptionVerification verification, java.time.Duration pendingTtl) {
        SubscriptionPhase phase = s.phaseOn(today);
        return new MySubscriptionView(s.getId(), s.getProviderId(), providerName, s.getPersonId(), s.getPlanId(),
                s.getConsumptionType(), s.getStatus(), phase, s.getStartDate(), s.getEffectiveExpiryDate(),
                s.remainingDaysOn(today),
                s.getRemainingMeals(), s.getCancelledAt(), renewed, phase == SubscriptionPhase.ENDED && !renewed,
                VerificationView.from(verification, pendingTtl), TermsView.from(terms), s.getPausedSince(),
                s.isPausedOn(today), canStartVacation(s, phase, verification, renewed));
    }

    /** A hint for the UI; the server re-checks every rule when the vacation is started. */
    private static boolean canStartVacation(Subscription s, SubscriptionPhase phase,
            SubscriptionVerification verification, boolean renewed) {
        return s.getConsumptionType() == ConsumptionType.DAY
                && s.getPausedSince() == null
                && !renewed
                && (phase == SubscriptionPhase.RUNNING || phase == SubscriptionPhase.UPCOMING)
                && (verification == null || verification.getStatus() == VerificationStatus.VERIFIED);
    }
}
