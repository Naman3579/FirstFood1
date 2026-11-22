package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where a subscription stands on vacation: whether one can be started, the open one if any, and the history.
 * {@code blockedReason} is plain language for why {@code canStart} is false (null when it is true).
 */
public record VacationView(
        UUID subscriptionId,
        boolean canStart,
        String blockedReason,
        LocalDate baseExpiryDate,
        LocalDate effectiveExpiryDate,
        PauseView open,
        List<PauseView> history) {
}
