package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Selling and managing subscriptions. Provider-side methods authorize through the provider's roles
 * (SUBSCRIPTION_VIEW / SUBSCRIPTION_MANAGE); customer-side methods are scoped to the caller's own
 * Persons. Attendance, absence, extension and payment are later phases and are not here.
 */
public interface SubscriptionService {

    /** Sells {@code planId} to the membership: subscription + frozen term snapshot, atomically. */
    SubscriptionView create(UUID actorAccountId, UUID providerId, UUID membershipId, UUID planId, LocalDate startDate);

    /** Optionally filtered by membership and/or stored status; newest first. */
    List<SubscriptionView> list(UUID actorAccountId, UUID providerId, UUID membershipId, SubscriptionStatus status);

    SubscriptionView get(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    SubscriptionView cancel(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason);

    /**
     * A new commercial event following an ended subscription: new subscription, new snapshot at
     * today's price and policy. {@code planId} and {@code startDate} may be null (same plan, today).
     */
    SubscriptionView renew(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID planId,
            LocalDate startDate);

    /**
     * Staff confirm a subscription the customer bought or renewed for themselves (SUBSCRIPTION_MANAGE). Only a
     * PENDING one; not allowed on one's own subscription.
     */
    SubscriptionView verify(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    /**
     * Staff refuse a PENDING subscription (SUBSCRIPTION_MANAGE): it is cancelled, never becomes entitlement, and the
     * reason is shown to the customer.
     */
    SubscriptionView reject(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason);

    /**
     * Self-service renewal (customer portal): the caller renews their OWN ended subscription as a new sale at today's
     * price and policy. Exactly the provider-side renewal rules (ended, not already renewed, no pending extension days,
     * active membership, capacity, overlap); only the authorization differs. Someone else's subscription reads as
     * "not found". Takes the provider row lock, so it must run inside the caller's transaction.
     */
    MySubscriptionView renewSelf(UUID accountId, UUID subscriptionId, UUID planId, LocalDate startDate);

    List<MySubscriptionView> listMine(UUID accountId);

    MySubscriptionView getMine(UUID accountId, UUID subscriptionId);

    /**
     * Self-service sale (customer portal): the caller buys {@code planId} on their OWN membership ({@code membershipId}
     * must belong to one of the caller's persons, else it is reported as not found). Same rules, capacity and overlap
     * checks as a provider-side sale; price and terms come from the plan, never from the caller. Takes the provider
     * row lock, so it must run inside the caller's transaction.
     */
    MySubscriptionView subscribeSelf(UUID accountId, UUID providerId, UUID membershipId, UUID planId,
            LocalDate startDate);
}
