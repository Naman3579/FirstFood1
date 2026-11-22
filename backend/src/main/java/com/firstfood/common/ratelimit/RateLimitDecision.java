package com.firstfood.common.ratelimit;

/**
 * Outcome of one counter operation.
 *
 * @param allowed           false once the counter has gone past the limit
 * @param count             the counter value after this operation
 * @param limit             the limit that was applied
 * @param retryAfterSeconds whole seconds until the window resets (always >= 1)
 */
public record RateLimitDecision(boolean allowed, long count, int limit, long retryAfterSeconds) {

    public static RateLimitDecision of(long count, int limit, long ttlMillis) {
        return new RateLimitDecision(count <= limit, count, limit, secondsCeil(ttlMillis));
    }

    static long secondsCeil(long millis) {
        long seconds = (millis + 999) / 1000;
        return Math.max(1, seconds);
    }
}
