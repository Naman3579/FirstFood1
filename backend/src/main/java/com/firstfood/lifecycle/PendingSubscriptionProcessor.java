package com.firstfood.lifecycle;

import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import com.firstfood.subscription.SubscriptionLookupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles ONE stale unconfirmed request (F-06) in ONE transaction: take the subscription row lock, then let the
 * subscription module re-check and close it (verification PENDING -> EXPIRED, subscription CANCELLED).
 *
 * Retry safety (Rule 22.1): the module re-checks everything under the lock, so a request that staff confirmed or rejected
 * between the scan and now, or one a parallel run already closed, is simply left alone. Lock order is the same as a
 * rejection by staff (subscription row, then verification row), so the two cannot deadlock; whichever gets the lock first
 * wins and the other finds it no longer pending.
 */
@Service
public class PendingSubscriptionProcessor {

    /** What happened to the request. */
    public enum Result {
        /** Confirmed, rejected, already closed, gone, or not old enough after all: nothing to do. */
        SKIPPED,
        /** Closed: the request is EXPIRED and its seat is free. */
        EXPIRED
    }

    private final SubscriptionLookupService lookup;
    private final SubscriptionLifecycleService lifecycle;

    PendingSubscriptionProcessor(SubscriptionLookupService lookup, SubscriptionLifecycleService lifecycle) {
        this.lookup = lookup;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Result process(SubscriptionKey key) {
        if (lookup.lock(key.providerId(), key.subscriptionId()).isEmpty()) {
            return Result.SKIPPED;
        }
        return lifecycle.expireStalePending(key.providerId(), key.subscriptionId()) ? Result.EXPIRED : Result.SKIPPED;
    }
}
