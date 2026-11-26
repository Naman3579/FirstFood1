package com.firstfood.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What a customer reports. {@code paymentDate}/{@code paymentTime} are the customer's claim in the business time
 * zone. {@code idempotencyKey} is optional: a retry with the same key returns the first payment.
 */
public record SubmitPaymentCommand(
        PaymentMethod method,
        String transactionReference,
        BigDecimal amount,
        LocalDate paymentDate,
        LocalTime paymentTime,
        String idempotencyKey) {
}
