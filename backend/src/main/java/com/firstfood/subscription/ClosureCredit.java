package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a provider closure gives (or would give) one subscription: the days, and where the expiry moves from and to.
 * Carries no price or phone. {@code applied} is false in a preview and true once the adjustment has been recorded.
 */
public record ClosureCredit(
        UUID subscriptionId,
        UUID membershipId,
        String planName,
        LocalDate previousExpiryDate,
        LocalDate newExpiryDate,
        int days,
        boolean applied) {
}
