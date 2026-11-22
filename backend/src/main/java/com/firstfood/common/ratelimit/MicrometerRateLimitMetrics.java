package com.firstfood.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code firstfood.ratelimit.decisions{policy,outcome}}. Alert on a sustained non-zero
 * {@code outcome=STORE_ERROR_REJECTED} or {@code FALLBACK_*} rate (Redis trouble) and on spikes of {@code LIMITED}.
 * Tags carry only the policy name - never a phone number, IP or account id.
 */
@Component
public class MicrometerRateLimitMetrics implements RateLimitMetrics {

    private final MeterRegistry registry;

    public MicrometerRateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(String policy, Outcome outcome) {
        registry.counter("firstfood.ratelimit.decisions", "policy", policy, "outcome", outcome.name()).increment();
    }
}
