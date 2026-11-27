package com.firstfood.common.ratelimit;

import java.time.Duration;

/**
 * Shared counter storage behind the rate limiter. The production implementation is Redis
 * ({@link RedisRateLimitStore}) so every backend replica enforces one common budget; the in-memory
 * implementation is only a per-instance fallback and test double.
 *
 * Implementations MUST make "increment and (re)arm the expiry" a single atomic step: a counter that
 * can exist without a TTL is a permanent lock-out.
 */
public interface RateLimitStore {

    /** Increments the counter for {@code key} (starting a new window if none is running) and judges it against {@code limit}. */
    RateLimitDecision hit(String key, int limit, Duration window);

    /** Reads the counter WITHOUT incrementing it. A missing key reads as count 0. */
    RateLimitDecision peek(String key, int limit, Duration window);

    /**
     * Sliding-window variant of {@link #hit}: at most {@code limit} hits in ANY {@code window}-long span, so there is no
     * fixed-window boundary where an attacker gets 2x the budget. Denied hits are NOT recorded (storage stays bounded by
     * {@code limit} and a blocked client cannot extend its own lock); a denied result reports count {@code limit + 1}
     * and a retry-after equal to the time until the oldest recorded hit leaves the window.
     * The default falls back to the fixed window so simple test doubles keep working.
     */
    default RateLimitDecision hitSliding(String key, int limit, Duration window) {
        return hit(key, limit, window);
    }

    /** Sliding-window variant of {@link #peek}: counts the hits still inside the window, without recording one. */
    default RateLimitDecision peekSliding(String key, int limit, Duration window) {
        return peek(key, limit, window);
    }

    /**
     * Takes back the most recent hit recorded by {@link #hitSliding} (a reservation that turned out not to count).
     * Atomic. A no-op when nothing is recorded; the default is a no-op so simple test doubles keep working.
     */
    default void releaseSliding(String key) {
    }

    /** Forgets the counter (e.g. after a successful login clears the failure count). */
    void reset(String key);
}
