package com.firstfood.common.ratelimit;

import java.time.Duration;

/** At most {@code limit} hits per fixed {@code window}. */
public record RateLimitRule(int limit, Duration window) {

    public RateLimitRule {
        if (limit < 1) {
            throw new IllegalArgumentException("rate limit must be >= 1, got " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("rate limit window must be positive, got " + window);
        }
    }
}
