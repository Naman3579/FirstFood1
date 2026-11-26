package com.firstfood.closure;

import java.time.LocalDate;
import java.util.UUID;

/** One customer's subscription in a closure preview or result. No phone, no price. */
public record ClosureAffected(
        UUID subscriptionId,
        UUID membershipId,
        String customerName,
        String planName,
        LocalDate previousExpiryDate,
        LocalDate newExpiryDate,
        int days) {
}
