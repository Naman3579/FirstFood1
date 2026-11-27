package com.firstfood.common.ratelimit;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The one place that talks to the counter store. Adds, on top of {@link RateLimitStore}:
 * hashed keys, metrics, and the explicit outage behaviour ({@link FailureMode}).
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final long OUTAGE_LOG_INTERVAL_MILLIS = 30_000;

    /**
     * Policies counted with a SLIDING window instead of a fixed one: everything that guards OTP sending and guessing.
     * A fixed window lets an attacker spend a full budget just before the boundary and another just after it (2x in a
     * short span); a sliding window allows at most {@code limit} in any window-long span. Chosen by policy name so
     * configuration binding stays unchanged. Renaming a policy still resets its counters.
     */
    static final Set<String> SLIDING_WINDOW_POLICIES = Set.of(
            RateLimitPolicies.OTP_REQUEST_PHONE,
            RateLimitPolicies.OTP_REQUEST_IP_BURST,
            RateLimitPolicies.OTP_REQUEST_IP_HOURLY,
            RateLimitPolicies.OTP_VERIFY_IP,
            RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP,
            RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE,
            // F-05: the site-wide hourly SMS budget. Sliding, so a denied hit is not recorded and a blocked burst
            // cannot keep the circuit open longer than the burst itself. (The daily budget is a fixed window keyed by date.)
            RateLimitPolicies.SMS_GLOBAL_HOURLY);

    private final RateLimitStore store;
    private final RateLimitStore localFallback;
    private final RateLimitMetrics metrics;
    private final String keyPrefix;
    private final AtomicLong lastOutageLogMillis = new AtomicLong(0);

    @Autowired
    public RateLimitService(RateLimitStore store, RateLimitMetrics metrics, RateLimitProperties properties) {
        this(store, new InMemoryRateLimitStore(), metrics, properties.keyPrefix());
    }

    RateLimitService(RateLimitStore store, RateLimitStore localFallback, RateLimitMetrics metrics, String keyPrefix) {
        this.store = store;
        this.localFallback = localFallback;
        this.metrics = metrics;
        this.keyPrefix = keyPrefix;
    }

    /** Counts one hit against {@code subject} under {@code policy}. Never throws for being over the limit - inspect the result. */
    public RateLimitDecision hit(String policy, String subject, RateLimitRule rule, FailureMode mode) {
        String key = RateLimitKeys.key(keyPrefix, policy, subject);
        try {
            RateLimitDecision decision = applyHit(store, policy, key, rule);
            metrics.record(policy, decision.allowed() ? RateLimitMetrics.Outcome.ALLOWED : RateLimitMetrics.Outcome.LIMITED);
            return decision;
        } catch (RateLimitStoreException e) {
            noteOutage(policy, mode, e);
            if (mode == FailureMode.FAIL_CLOSED) {
                metrics.record(policy, RateLimitMetrics.Outcome.STORE_ERROR_REJECTED);
                throw new RateLimitUnavailableException();
            }
            RateLimitDecision decision = applyHit(localFallback, policy, key, rule);
            metrics.record(policy, decision.allowed()
                    ? RateLimitMetrics.Outcome.FALLBACK_ALLOWED : RateLimitMetrics.Outcome.FALLBACK_LIMITED);
            return decision;
        }
    }

    /** Like {@link #hit} but does not count: "is this subject already over the limit?". */
    public RateLimitDecision peek(String policy, String subject, RateLimitRule rule, FailureMode mode) {
        String key = RateLimitKeys.key(keyPrefix, policy, subject);
        try {
            return applyPeek(store, policy, key, rule);
        } catch (RateLimitStoreException e) {
            noteOutage(policy, mode, e);
            if (mode == FailureMode.FAIL_CLOSED) {
                metrics.record(policy, RateLimitMetrics.Outcome.STORE_ERROR_REJECTED);
                throw new RateLimitUnavailableException();
            }
            return applyPeek(localFallback, policy, key, rule);
        }
    }

    /** Hit and throw {@link RateLimitedException} (429) if over the limit. */
    public void enforce(String policy, String subject, RateLimitRule rule, FailureMode mode) {
        RateLimitDecision decision = hit(policy, subject, rule, mode);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }

    /**
     * Gives back one hit on a SLIDING-window policy: the "reserve first, refund if it did not count" half of an
     * atomic increment-then-check. Best effort, like {@link #reset}: a failed refund only leaves the counter one
     * higher (the safe direction) and must never fail the request.
     */
    public void release(String policy, String subject) {
        if (!SLIDING_WINDOW_POLICIES.contains(policy)) {
            throw new IllegalArgumentException("release() needs a sliding-window policy: " + policy);
        }
        String key = RateLimitKeys.key(keyPrefix, policy, subject);
        try {
            store.releaseSliding(key);
        } catch (RateLimitStoreException e) {
            noteOutage(policy, FailureMode.LOCAL_FALLBACK, e);
            // The hit may have been counted by the local fallback during the outage; give it back there instead.
            localFallback.releaseSliding(key);
        }
    }

    /** Best effort: failing to clear a counter must never fail the request that triggered the clearing. */
    public void reset(String policy, String subject) {
        String key = RateLimitKeys.key(keyPrefix, policy, subject);
        localFallback.reset(key);
        try {
            store.reset(key);
        } catch (RateLimitStoreException e) {
            noteOutage(policy, FailureMode.LOCAL_FALLBACK, e);
        }
    }

    private static RateLimitDecision applyHit(RateLimitStore target, String policy, String key, RateLimitRule rule) {
        return SLIDING_WINDOW_POLICIES.contains(policy)
                ? target.hitSliding(key, rule.limit(), rule.window())
                : target.hit(key, rule.limit(), rule.window());
    }

    private static RateLimitDecision applyPeek(RateLimitStore target, String policy, String key, RateLimitRule rule) {
        return SLIDING_WINDOW_POLICIES.contains(policy)
                ? target.peekSliding(key, rule.limit(), rule.window())
                : target.peek(key, rule.limit(), rule.window());
    }

    private void noteOutage(String policy, FailureMode mode, RateLimitStoreException e) {
        long now = System.currentTimeMillis();
        long last = lastOutageLogMillis.get();
        if (now - last >= OUTAGE_LOG_INTERVAL_MILLIS && lastOutageLogMillis.compareAndSet(last, now)) {
            // No subject here on purpose: it would be a phone number or an IP.
            log.warn("Rate-limit store unavailable (policy={}, mode={}): {}", policy, mode, e.getMessage());
        }
    }
}
