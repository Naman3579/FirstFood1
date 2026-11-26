package com.firstfood.closure;

import java.time.LocalDate;
import java.util.List;

/** What declaring a closure with extension would do right now. Nothing has been written. */
public record ClosurePreview(
        LocalDate startDate,
        LocalDate endDate,
        int closureDays,
        int affectedCount,
        int totalDays,
        List<ClosureAffected> affected) {
}
