package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
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

/**
 * Audit F-09: the guess limits hold under parallel requests. Calls go straight to the services (no HTTP) so the per-IP
 * filter is not in the way. Phones use +91987665xxxx; the database is a shared singleton container, so do not reuse them.
 */
class OtpAttemptRaceIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    OtpService otpService;

    @Autowired
    OtpVerificationRepository otpRepository;

    @Autowired
    OtpProperties otpProperties;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void parallelWrongGuessesNeverGetMoreThanMaxAttemptsComparisons() throws Exception {
        String phone = "+919876650001";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String right = otpSender.lastOtpFor(phone);
        String wrong = right.equals("000000") ? "000001" : "000000";

        Outcome outcome = race(() -> {
            otpService.verifyOtp(phone, OtpPurpose.LOGIN, wrong);
            return null;
        });

        // Exactly max-attempts requests were allowed to compare; everyone else found the budget spent.
        assertThat(outcome.invalid.get()).isEqualTo(otpProperties.maxAttempts());
        assertThat(outcome.expired.get()).isEqualTo(THREADS - otpProperties.maxAttempts());
        assertThat(outcome.other.get()).isZero();

        // The challenge is dead: even the right code no longer works.
        assertThatThrownBy(() -> otpService.verifyOtp(phone, OtpPurpose.LOGIN, right))
                .isInstanceOf(OtpExpiredException.class);
    }

    @Test
    void theLastAllowedGuessCanStillBeTheRightOne() {
        String phone = "+919876650002";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String right = otpSender.lastOtpFor(phone);
        String wrong = right.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < otpProperties.maxAttempts() - 1; i++) {
            assertThatThrownBy(() -> otpService.verifyOtp(phone, OtpPurpose.LOGIN, wrong))
                    .isInstanceOf(InvalidOtpException.class);
        }

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, right); // the max-th guess: must succeed
    }

    @Test
    void checkingTheRightCodeDoesNotBurnAttempts() {
        String phone = "+919876650003";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String right = otpSender.lastOtpFor(phone);

        for (int i = 0; i < otpProperties.maxAttempts() * 2; i++) {
            otpService.checkOtp(phone, OtpPurpose.LOGIN, right);
        }

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, right);
    }

    @Test
    void aFailedAttemptSurvivesTheServiceTransaction() {
        String phone = "+919876650004";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String right = otpSender.lastOtpFor(phone);
        String wrong = right.equals("000000") ? "000001" : "000000";

        assertThatThrownBy(() -> otpService.verifyOtp(phone, OtpPurpose.LOGIN, wrong))
                .isInstanceOf(InvalidOtpException.class);

        OtpVerification stored = otpRepository
                .findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(phone, OtpPurpose.LOGIN, OtpStatus.PENDING)
                .orElseThrow();
        assertThat(stored.getAttemptCount()).isEqualTo(1);
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
        final AtomicInteger other = new AtomicInteger();
    }
}
