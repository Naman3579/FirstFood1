package com.firstfood.closure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A declared closure and what became of it. {@code processing}: NOT_REQUESTED (owner chose not to extend; no expiry was
 * touched), COMPLETED (extension was applied, see {@code affected}), CANCELLED. {@code affected} is filled on the single
 * closure and on the declare response; the history list carries counts only.
 */
public record ClosureView(
        UUID id,
        UUID providerId,
        LocalDate startDate,
        LocalDate endDate,
        int closureDays,
        String reason,
        boolean extendSubscriptions,
        ClosureStatus status,
        String processing,
        UUID createdBy,
        Instant createdAt,
        Instant cancelledAt,
        int affectedCount,
        int totalDaysApplied,
        List<ClosureAffected> affected) {
}
