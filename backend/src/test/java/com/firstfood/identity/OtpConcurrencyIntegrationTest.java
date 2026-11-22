package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.error.AccountNotRegisteredException;
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
 * Audit F-01: an OTP is single-use even when the same correct code is submitted by many requests at the same
 * instant. Calls go straight to the services (no HTTP) so the per-IP limiters are not in the way and the race is
 * as tight as possible. Phones use +91987664xxxx; the database is a shared singleton container, so do not reuse them.
 */
class OtpConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 12;

    @Autowired
    OtpService otpService;

    @Autowired
    AuthenticationService authenticationService;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void parallelVerifyOfOneCodeSucceedsExactlyOnce() throws Exception {
        String phone = "+919876640001";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        Outcome outcome = race(() -> {
            otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);
            return null;
        });

        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.expired).isEqualTo(THREADS - 1);
        assertThat(outcome.other).isZero();
    }

    @Test
    void parallelSignUpOfOneCodeIssuesTokensExactlyOnce() throws Exception {
        String phone = "+919876640002";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        Outcome outcome = race(() -> authenticationService.signUp(Portal.CUSTOMER, phone, code, "Race Tester"));

        // A loser may be refused as "expired" or, if it slipped past checkOtp before the winner committed,
        // as "already registered" - either way it gets no tokens. Only the success count matters here.
        assertThat(outcome.successes).isEqualTo(1);
    }

    @Test
    void parallelSignInOfOneCodeIssuesTokensExactlyOnce() throws Exception {
        String phone = "+919876640003";
        // Create the account first with its own code, then race a fresh code.
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        authenticationService.signUp(Portal.CUSTOMER, phone, otpSender.lastOtpFor(phone), "Race Tester");

        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        Outcome outcome = race(() -> authenticationService.signIn(Portal.CUSTOMER, phone, code));

        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.other).isZero();
    }

    @Test
    void parallelLegacyLoginOfOneCodeIssuesTokensExactlyOnce() throws Exception {
        String phone = "+919876640004";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        Outcome outcome = race(() -> authenticationService.verifyLoginOtp(phone, code));

        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.other).isZero();
    }

    @Test
    void aConsumedCodeCannotBeReusedSequentially() {
        String phone = "+919876640005";
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);

        assertThatThrownBy(() -> otpService.verifyOtp(phone, OtpPurpose.LOGIN, code))
                .isInstanceOf(OtpExpiredException.class);
    }

    @Test
    void signInOnAnUnregisteredPortalDoesNotSpendTheCode() {
        String phone = "+919876640006";
        // Account exists on CUSTOMER only.
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        authenticationService.signUp(Portal.CUSTOMER, phone, otpSender.lastOtpFor(phone), "Portal Tester");

        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = otpSender.lastOtpFor(phone);

        // Refused for being on the wrong portal - and the code must survive that refusal...
        assertThatThrownBy(() -> authenticationService.signIn(Portal.STAFF, phone, code))
                .isInstanceOf(AccountNotRegisteredException.class);

        // ...so the same code can still register the number on the STAFF portal.
        assertThat(authenticationService.signUp(Portal.STAFF, phone, code, "Portal Tester")).isNotNull();
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
                        outcome.successCount.incrementAndGet();
                    } catch (OtpExpiredException e) {
                        outcome.expiredCount.incrementAndGet();
                    } catch (Exception e) {
                        outcome.otherCount.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return outcome.snapshot();
    }

    private static final class Outcome {
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger expiredCount = new AtomicInteger();
        final AtomicInteger otherCount = new AtomicInteger();
        int successes;
        int expired;
        int other;

        // Snapshot lazily so assertions read plain ints.
        Outcome snapshot() {
            successes = successCount.get();
            expired = expiredCount.get();
            other = otherCount.get();
            return this;
        }
    }
}
