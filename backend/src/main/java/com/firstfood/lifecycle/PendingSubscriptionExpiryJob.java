package com.firstfood.lifecycle;

import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * F-06: closes subscription REQUESTS nobody confirmed within {@code app.subscription.pending-ttl}. A customer's own
 * purchase is stored ACTIVE while it waits for staff, so without this a request that is never looked at would hold its
 * dates and the provider's capacity indefinitely (a meal plan has no end date at all). The request becomes EXPIRED and the
 * subscription CANCELLED; the customer can ask again.
 *
 * This job is housekeeping, not the safeguard: the capacity count already ignores requests past the TTL, so a late or
 * missed run never lets a stale request block anyone. Scans pages of {@code app.jobs.batch-size} in id order; an item that
 * fails is recorded and skipped for the rest of the run, and picked up again by the next one. Idempotent: a second run
 * finds nothing left to do.
 */
@Component
public class PendingSubscriptionExpiryJob implements BackgroundJob {

    public static final String NAME = "pending-subscription-expiry";
    private static final Logger log = LoggerFactory.getLogger(PendingSubscriptionExpiryJob.class);
    private static final UUID FIRST = new UUID(0L, 0L);

    private final SubscriptionLifecycleService lifecycle;
    private final PendingSubscriptionProcessor processor;
    private final JobProperties properties;

    PendingSubscriptionExpiryJob(SubscriptionLifecycleService lifecycle, PendingSubscriptionProcessor processor,
            JobProperties properties) {
        this.lifecycle = lifecycle;
        this.processor = processor;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(JobRunContext context) {
        int expired = 0;
        UUID after = FIRST;
        while (true) {
            List<SubscriptionKey> page = lifecycle.findStalePending(after, properties.batchSize());
            if (page.isEmpty()) {
                break;
            }
            for (SubscriptionKey key : page) {
                if (!context.keepGoing()) {
                    return;
                }
                after = key.subscriptionId();
                try {
                    if (processor.process(key) == PendingSubscriptionProcessor.Result.EXPIRED) {
                        expired++;
                        context.recordChanged();
                    } else {
                        context.recordUnchanged();
                    }
                } catch (RuntimeException e) {
                    log.warn("job={} subscription={} could not be processed: {}", NAME, key.subscriptionId(),
                            e.getClass().getSimpleName());
                    context.recordFailure(key.subscriptionId().toString(), e);
                }
            }
        }
        if (expired > 0) {
            log.info("job={} expiredUnconfirmedRequests={}", NAME, expired);
        }
    }
}
