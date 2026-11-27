package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every OTP policy must use the sliding window; everything else keeps the fixed window. */
class SlidingWindowPolicySelectionTest {

    private static final RateLimitRule RULE = new RateLimitRule(3, Duration.ofMinutes(1));

    /** Records which algorithm each call used. */
    private static final class RecordingStore implements RateLimitStore {
        final List<String> calls = new ArrayList<>();

        @Override
        public RateLimitDecision hit(String key, int limit, Duration window) {
            calls.add("fixed-hit");
            return RateLimitDecision.of(1, limit, window.toMillis());
        }

        @Override
        public RateLimitDecision peek(String key, int limit, Duration window) {
            calls.add("fixed-peek");
            return RateLimitDecision.of(0, limit, window.toMillis());
        }

        @Override
        public RateLimitDecision hitSliding(String key, int limit, Duration window) {
            calls.add("sliding-hit");
            return RateLimitDecision.of(1, limit, window.toMillis());
        }

        @Override
        public RateLimitDecision peekSliding(String key, int limit, Duration window) {
            calls.add("sliding-peek");
            return RateLimitDecision.of(0, limit, window.toMillis());
        }

        @Override
        public void reset(String key) {
        }
    }

    private final RecordingStore store = new RecordingStore();
    private final RateLimitService service = new RateLimitService(
            store, new InMemoryRateLimitStore(), RateLimitMetrics.NOOP, "t");

    @Test
    void allOtpPoliciesUseTheSlidingWindow() {
        for (String policy : List.of(
                RateLimitPolicies.OTP_REQUEST_PHONE,
                RateLimitPolicies.OTP_REQUEST_IP_BURST,
                RateLimitPolicies.OTP_REQUEST_IP_HOURLY,
                RateLimitPolicies.OTP_VERIFY_IP,
                RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP,
                RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE)) {
            store.calls.clear();
            service.hit(policy, "s", RULE, FailureMode.FAIL_CLOSED);
            service.peek(policy, "s", RULE, FailureMode.FAIL_CLOSED);
            assertThat(store.calls).as(policy).containsExactly("sliding-hit", "sliding-peek");
        }
    }

    @Test
    void otherPoliciesKeepTheFixedWindow() {
        for (String policy : List.of(
                RateLimitPolicies.AUTH_IP, RateLimitPolicies.API_IP, RateLimitPolicies.REFRESH_PER_TOKEN,
                RateLimitPolicies.ACCOUNT_READ, RateLimitPolicies.ACCOUNT_WRITE)) {
            store.calls.clear();
            service.hit(policy, "s", RULE, FailureMode.FAIL_CLOSED);
            service.peek(policy, "s", RULE, FailureMode.FAIL_CLOSED);
            assertThat(store.calls).as(policy).containsExactly("fixed-hit", "fixed-peek");
        }
    }
}
