package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The facts about a subscription that other modules (attendance) may rely on; no entity leaves this module.
 * Carries the absence and extension policy the subscription was SOLD under (from its term snapshot, never from the current
 * plan - rules.md Rule 15.2) but deliberately no price: staff who may only handle attendance must not receive
 * what the customer paid.
 */
public record SubscriptionRef(
        UUID subscriptionId,
        UUID providerId,
        UUID membershipId,
        UUID personId,
        ConsumptionType consumptionType,
        SubscriptionStatus status,
        LocalDate startDate,
        LocalDate baseExpiryDate,
        LocalDate effectiveExpiryDate,
        /** start + the policy's calendar window - 1 (counted from the ORIGINAL start); null = no window, no cap. */
        LocalDate maximumExpiryDate,
        Instant cancelledAt,
        String planName,
        boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime,
        /** The extension terms it was SOLD under (term snapshot), for the Phase 9 extension engine. */
        boolean extensionAllowed,
        Integer minConsecutiveAbsenceDays,
        /** Whether a renewal of this subscription exists (an extension would then overlap it). */
        boolean renewed,
        /** V25: start of the OPEN vacation (may be a future date), null when none. */
        LocalDate pausedSince,
        /** V25: every vacation that was not called off (open ones have a null end), for "was the customer away that day". */
        List<VacationSpan> vacations) {

    /**
     * One vacation: {@code from} is the first day NOT served; {@code resumedOn} the first day served again, null while the
     * vacation is still open (no return date).
     */
    public record VacationSpan(LocalDate from, LocalDate resumedOn) {

        public boolean covers(LocalDate day) {
            return !day.isBefore(from) && (resumedOn == null || day.isBefore(resumedOn));
        }
    }

    /** The pre-V25 shape: no vacations. */
    public SubscriptionRef(UUID subscriptionId, UUID providerId, UUID membershipId, UUID personId,
            ConsumptionType consumptionType, SubscriptionStatus status, LocalDate startDate, LocalDate baseExpiryDate,
            LocalDate effectiveExpiryDate, LocalDate maximumExpiryDate, Instant cancelledAt, String planName,
            boolean sameDayAbsenceAllowed, LocalTime absenceCutoffTime, boolean extensionAllowed,
            Integer minConsecutiveAbsenceDays, boolean renewed) {
        this(subscriptionId, providerId, membershipId, personId, consumptionType, status, startDate, baseExpiryDate,
                effectiveExpiryDate, maximumExpiryDate, cancelledAt, planName, sameDayAbsenceAllowed,
                absenceCutoffTime, extensionAllowed, minConsecutiveAbsenceDays, renewed, null, List.of());
    }

    /** Whether the customer was (or is scheduled to be) on vacation on {@code day}. */
    public boolean onVacation(LocalDate day) {
        return vacations.stream().anyMatch(v -> v.covers(day));
    }

    /** Where the subscription stands on {@code today} (same rule as every subscription view). */
    public SubscriptionPhase phaseOn(LocalDate today) {
        if (isPausedOn(today)) {
            return SubscriptionPhase.RUNNING;
        }
        return SubscriptionRules.phase(status, startDate, effectiveExpiryDate, today);
    }

    /** On vacation today: begun and not resumed. Its stored expiry is not final yet, so it is never "lapsed". */
    public boolean isPausedOn(LocalDate today) {
        return status == SubscriptionStatus.ACTIVE && pausedSince != null && !pausedSince.isAfter(today);
    }

    /** Stored ACTIVE but already over on {@code today}: the expiry job has not yet recorded it (Phase 10). */
    public boolean isLapsedOn(LocalDate today) {
        return !isPausedOn(today) && SubscriptionRules.isLapsed(status, effectiveExpiryDate, today);
    }
}
