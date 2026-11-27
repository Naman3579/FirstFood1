package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.common.ratelimit.RateLimitedException;
import com.firstfood.identity.error.InvalidOtpException;
import com.firstfood.identity.error.OtpExpiredException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Audit F-09, limiter side: the phone+IP failure budget is an atomic increment-then-check, so parallel guesses from one
 * address cannot overshoot it. The test profile switches this limit off, so this class sets a tiny one under its own
 * key prefix. Phones are +9162001200xx (see also OtpAttemptRaceIntegrationTest).
 */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-f09-guard",
        "app.rate-limit.otp-verify-failures-phone-ip.limit=4",
        "app.rate-limit.otp-verify-failures-phone-ip.window=10m",
})
class AuthAbuseGuardRaceIntegrationTest extends AbstractIntegrationTest {

    private static final int LIMIT = 4;
    private static final int THREADS = 16;

    @Autowired
    AuthAbuseGuard abuseGuard;

    @Test
    void parallelGuardedAttemptsFromOneAddressStopAtTheLimit() throws Exception {
        String phone = "+919876650005";
        String ip = "203.0.113.50";
        int limit = LIMIT;

        AtomicInteger examined = new AtomicInteger();
        Outcome outcome = race(() -> abuseGuard.guardVerify(phone, ip, () -> {
            examined.incrementAndGet();
            throw new InvalidOtpException();
        }));

        assertThat(examined.get()).isEqualTo(limit);
        assertThat(outcome.invalid.get()).isEqualTo(limit);
        assertThat(outcome.limited.get()).isEqualTo(THREADS - limit);
    }

    @Test
    void outcomesThatAreNotAWrongCodeGiveTheAttemptBack() {
        String phone = "+919876650006";
        String ip = "203.0.113.51";

        // "No pending code" is something a stranger can produce at will: it must never use up this address's budget.
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> abuseGuard.guardVerify(phone, ip, () -> {
                throw new OtpExpiredException();
            })).isInstanceOf(OtpExpiredException.class);
        }

        // Still allowed to try: the guard did not lock.
        assertThatThrownBy(() -> abuseGuard.guardVerify(phone, ip, () -> {
            throw new InvalidOtpException();
        })).isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void aSuccessfulVerificationClearsTheAddressBudget() {
        String phone = "+919876650007";
        String ip = "203.0.113.52";

        for (int i = 0; i < LIMIT - 1; i++) {
            assertThatThrownBy(() -> abuseGuard.guardVerify(phone, ip, () -> {
                throw new InvalidOtpException();
            })).isInstanceOf(InvalidOtpException.class);
        }
        assertThat(abuseGuard.guardVerify(phone, ip, () -> "ok")).isEqualTo("ok");

        // Budget is back to full: LIMIT more wrong guesses are examined before the lock.
        for (int i = 0; i < LIMIT; i++) {
            assertThatThrownBy(() -> abuseGuard.guardVerify(phone, ip, () -> {
                throw new InvalidOtpException();
            })).isInstanceOf(InvalidOtpException.class);
        }
        assertThatThrownBy(() -> abuseGuard.guardVerify(phone, ip, () -> "never examined"))
                .isInstanceOf(RateLimitedException.class);
    }

    private Outcome race(Callable<?> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        Outcome outcome = new Outcome();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        action.call();
                        outcome.success.incrementAndGet();
                    } catch (InvalidOtpException e) {
                        outcome.invalid.incrementAndGet();
                    } catch (OtpExpiredException e) {
                        outcome.expired.incrementAndGet();
                    } catch (RateLimitedException e) {
                        outcome.limited.incrementAndGet();
                    } catch (Exception e) {
                        outcome.other.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return outcome;
    }

    private static final class Outcome {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger invalid = new AtomicInteger();
        final AtomicInteger expired = new AtomicInteger();
        final AtomicInteger limited = new AtomicInteger();
        final AtomicInteger other = new AtomicInteger();
    }
}
