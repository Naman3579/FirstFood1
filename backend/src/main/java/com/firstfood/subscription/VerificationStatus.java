package com.firstfood.subscription;

/**
 * Whether the provider's staff have confirmed a new subscription. Separate from {@link SubscriptionStatus}: a
 * subscription is stored ACTIVE while PENDING (so it already holds its dates and capacity) but is not yet confirmed.
 * REJECTED and EXPIRED always go together with the subscription being CANCELLED (when it was still active).
 *
 * <ul>
 *   <li>REJECTED: staff refused it, with a reason the customer sees.</li>
 *   <li>EXPIRED (F-06): nobody confirmed it within {@code app.subscription.pending-ttl}; the system closed it so an
 *       unconfirmed request cannot hold the provider's capacity forever. No staff member decided it and there is no
 *       reason text. The customer can simply ask again.</li>
 * </ul>
 */
public enum VerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED,
    EXPIRED
}
