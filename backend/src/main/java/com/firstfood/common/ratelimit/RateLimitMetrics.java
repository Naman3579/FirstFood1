package com.firstfood.common.ratelimit;

/** Observability seam, kept free of any metrics library so the limiter core stays plain Java. */
public interface RateLimitMetrics {

    enum Outcome {
        ALLOWED, LIMITED, FALLBACK_ALLOWED, FALLBACK_LIMITED, STORE_ERROR_REJECTED
    }

    void record(String policy, Outcome outcome);

    RateLimitMetrics NOOP = (policy, outcome) -> {
    };
}
