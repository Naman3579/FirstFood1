package com.firstfood.closure.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Closure dates (inclusive; one day = same start and end) and the owner's decision. {@code extendSubscriptions} has no
 * default on purpose: omitting it is a 400, so extension is never switched on or off by accident.
 */
public record DeclareClosureRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Size(max = 200) String reason,
        Boolean extendSubscriptions) {
}
