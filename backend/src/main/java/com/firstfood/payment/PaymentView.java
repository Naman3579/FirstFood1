package com.firstfood.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One payment. {@code paymentDate}/{@code paymentTime} are what the customer claimed; {@code submittedAt} is the
 * server's own timestamp. {@code verifiedByAccountId} is only filled for staff; a customer sees the outcome and,
 * for a rejection, the reason.
 */
public record PaymentView(
        UUID id,
        UUID subscriptionId,
        String subscriberName,
        String transactionReference,
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        LocalTime paymentTime,
        PaymentVerificationStatus verificationStatus,
        Instant submittedAt,
        Instant verifiedAt,
        UUID verifiedByAccountId,
        String rejectionReason) {

    static PaymentView from(SubscriptionPayment p, boolean forStaff) {
        return new PaymentView(p.getId(), p.getSubscriptionId(), p.getSubscriberNameSnapshot(),
                p.getTransactionReference(), p.getAmount(), p.getCurrency(), p.getPaymentMethod(),
                p.getCustomerPaymentDate(), p.getCustomerPaymentTime(), p.getVerificationStatus(), p.getSubmittedAt(),
                p.getVerifiedAt(), forStaff ? p.getVerifiedByAccountId() : null, p.getRejectionReason());
    }
}
