package com.firstfood.payment;

import java.util.UUID;

/**
 * Payments against a subscription. Customer-side methods are scoped to the caller's own subscriptions (someone
 * else's reads as "not found"); staff-side methods authorize through the provider's roles
 * (SUBSCRIPTION_VIEW to read, SUBSCRIPTION_MANAGE to verify or reject).
 */
public interface PaymentService {

    /**
     * The caller reports a payment on their own subscription: stored PENDING, never counted as paid until staff
     * verify it. Works at any time (before, during or after the subscription), any number of times, until verified and
     * pending payments together cover the price. Idempotent per {@code idempotencyKey}.
     */
    PaymentView submit(UUID accountId, UUID subscriptionId, SubmitPaymentCommand command);

    /** The caller's own subscription's payments and balance. */
    PaymentLedgerView ledgerMine(UUID accountId, UUID subscriptionId);

    /** Staff: the subscription's payments and balance (SUBSCRIPTION_VIEW). */
    PaymentLedgerView ledger(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    /** Staff: confirms the money arrived (SUBSCRIPTION_MANAGE). Not allowed on one's own payment. */
    PaymentView verify(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID paymentId);

    /** Staff: refuses the payment with a reason the customer will see (SUBSCRIPTION_MANAGE). */
    PaymentView reject(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID paymentId, String reason);
}
