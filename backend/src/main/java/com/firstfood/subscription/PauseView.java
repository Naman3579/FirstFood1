package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One vacation as the customer sees it. {@code started} is whether its start date has been reached (a scheduled vacation
 * that has not begun yet can still be called off). For an OPEN vacation {@code projectedCreditDays} and
 * {@code projectedExpiryDate} say what resuming TODAY would give back, computed by the backend so no client re-derives it.
 */
public record PauseView(
        UUID id,
        UUID subscriptionId,
        LocalDate startDate,
        PauseStatus status,
        boolean started,
        LocalDate resumedOn,
        Integer pausedDays,
        Integer creditedDays,
        Integer projectedCreditDays,
        LocalDate projectedExpiryDate) {
}
