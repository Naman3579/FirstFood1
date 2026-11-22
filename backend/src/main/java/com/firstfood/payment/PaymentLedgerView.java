package com.firstfood.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A subscription's payments and where they leave the balance. Only VERIFIED money counts as paid:
 * {@code outstanding = price - confirmedPaid}, so a PENDING payment does not reduce it until staff verify it.
 * {@code pendingAmount} is shown separately so a customer can see what is waiting for verification.
 * {@code remainingToSubmit} is how much more can still be submitted (price minus verified and pending).
 */
public record PaymentLedgerView(
        UUID subscriptionId,
        String currency,
        BigDecimal price,
        BigDecimal confirmedPaid,
        BigDecimal pendingAmount,
        BigDecimal outstanding,
        BigDecimal remainingToSubmit,
        List<PaymentView> payments) {
}
