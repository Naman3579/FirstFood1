package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the limiter does when its store is healthy - and, more importantly, when it is not. */
class RateLimitServiceTest {

    private static final RateLimitRule TWO_PER_MINUTE = new RateLimitRule(2, Duration.ofMinutes(1));

    /** A store that is always down. */
    private static final RateLimitStore DOWN = new RateLimitStore() {
        @Override
        public RateLimitDecision hit(String key, int limit, Duration window) {
            throw new RateLimitStoreException("down");
        }

        @Override
        public RateLimitDecision peek(String key, int limit, Duration window) {
            throw new RateLimitStoreException("down");
        }

        @Override
        public void reset(String key) {
            throw new RateLimitStoreException("down");
        }
    };

    private final List<String> metrics = new ArrayList<>();
    private final RateLimitMetrics recorder = (policy, outcome) -> metrics.add(policy + ":" + outcome);

    private RateLimitService service(RateLimitStore store) {
        return new RateLimitService(store, new InMemoryRateLimitStore(), recorder, "test");
    }

    @Test
    void enforceThrows429WithRetryAfterOnceOverTheLimit() {
        RateLimitService service = service(new InMemoryRateLimitStore());
        service.enforce("p", "subject", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED);
        service.enforce("p", "subject", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED);

        assertThatThrownBy(() -> service.enforce("p", "subject", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
        assertThat(metrics).contains("p:LIMITED");
    }

    @Test
    void subjectsAndPoliciesHaveIndependentBudgets() {
        RateLimitService service = service(new InMemoryRateLimitStore());
        service.hit("p", "a", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED);
        service.hit("p", "a", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED);

        assertThat(service.hit("p", "b", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED).allowed()).isTrue();
        assertThat(service.hit("q", "a", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED).allowed()).isTrue();
    }

    @Test
    void failClosedRoutesAnswer503WhenTheStoreIsDown() {
        RateLimitService service = service(DOWN);

        assertThatThrownBy(() -> service.hit("otp", "x", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED))
                .isInstanceOf(RateLimitUnavailableException.class);
        assertThatThrownBy(() -> service.peek("otp", "x", TWO_PER_MINUTE, FailureMode.FAIL_CLOSED))
                .isInstanceOf(RateLimitUnavailableException.class);
        assertThat(metrics).contains("otp:STORE_ERROR_REJECTED");
    }

    @Test
    void localFallbackKeepsServingButIsStillLimited() {
        RateLimitService service = service(DOWN);

        assertThat(service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK).allowed()).isTrue();
        assertThat(service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK).allowed()).isTrue();
        // An outage must not turn the API into an unlimited one.
        assertThat(service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK).allowed()).isFalse();
        assertThat(metrics).contains("api:FALLBACK_LIMITED");
    }

    @Test
    void resettingNeverFailsTheCallerEvenWhenTheStoreIsDown() {
        RateLimitService service = service(DOWN);
        service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK);
        service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK);

        service.reset("api", "x"); // must not throw

        assertThat(service.hit("api", "x", TWO_PER_MINUTE, FailureMode.LOCAL_FALLBACK).allowed()).isTrue();
    }

    @Test
    void keysNeverContainTheRawSubject() {
        assertThat(RateLimitKeys.key("rl", "otp", "+919876543210")).doesNotContain("9876");
        assertThat(RateLimitKeys.normalizePhone("+919876543210")).isEqualTo(RateLimitKeys.normalizePhone("919876543210"));
    }

    @Test
    void invalidRulesAreRejectedAtStartup() {
        assertThatThrownBy(() -> new RateLimitRule(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
