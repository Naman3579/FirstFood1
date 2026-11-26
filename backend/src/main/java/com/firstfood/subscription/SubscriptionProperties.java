package com.firstfood.subscription;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.subscription.*} - F-06 (capacity squatting). A customer's own purchase is a REQUEST until staff confirm
 * it; these two knobs stop unconfirmed requests from holding a provider's capacity.
 *
 * @param pendingTtl             how long a request may wait for staff before it is closed (EXPIRED) and its seat freed.
 *                               It is also how long an unconfirmed request counts toward the provider's capacity: past it,
 *                               the request no longer blocks anyone even if the hourly job has not run yet. Default 48h:
 *                               long enough for a staff member to be away for a weekend day, short enough that squatting
 *                               must be repeated constantly.
 * @param maxPendingPerAccount   how many unconfirmed requests one customer account may have open at once, across all
 *                               providers. A single person has no reason to wait on more than a couple.
 */
@ConfigurationProperties(prefix = "app.subscription")
public record SubscriptionProperties(
        @DefaultValue("48h") Duration pendingTtl,
        @DefaultValue("3") int maxPendingPerAccount) {

    public SubscriptionProperties {
        if (pendingTtl.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("app.subscription.pending-ttl must be at least 1 hour, got " + pendingTtl);
        }
        if (maxPendingPerAccount < 1) {
            throw new IllegalArgumentException(
                    "app.subscription.max-pending-per-account must be at least 1, got " + maxPendingPerAccount);
        }
    }
}
