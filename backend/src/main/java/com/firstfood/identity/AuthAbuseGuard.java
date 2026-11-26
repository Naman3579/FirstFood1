package com.firstfood.identity;

import com.firstfood.common.ratelimit.ClientIpResolver;
import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitKeys;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitProperties;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.ratelimit.RateLimitedException;
import com.firstfood.identity.error.InvalidOtpException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limits that need the request body (phone number, refresh token) and so cannot live in the per-IP filter.
 *
 * OTP guessing is bounded in three layers (audit F-02 - none of them lets a stranger lock a victim out):
 * <ol>
 *   <li>per challenge: {@code app.otp.max-attempts} wrong codes kill that one code (the real protection of the code);</li>
 *   <li>phone + IP: this address has failed too often against this number, so THIS address is blocked. A different
 *       address, including the real owner's, is unaffected;</li>
 *   <li>phone alone: only an ALERT signal (metric + log line when the allowance is spent). It never blocks, because a
 *       block keyed on the victim's number alone is a lock-out any stranger can trigger from anywhere.</li>
 * </ol>
 * Counting is increment-then-check ({@link #guardVerify}, audit F-09): the attempt is reserved atomically before the
 * code is examined, never peeked and counted afterwards, so parallel guesses cannot overshoot the budget.
 * Only a WRONG code against a pending challenge counts as a failure ({@link com.firstfood.identity.error.InvalidOtpException}).
 * "No pending code" and "expired" do not: an attacker can produce those at will with no OTP and no SIM. Both counters
 * use a sliding window ({@link RateLimitService#SLIDING_WINDOW_POLICIES}), and a fresh OTP request starts a new attempt
 * budget for that address ({@link #recordOtpRequested}).
 */
@Component
public class AuthAbuseGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthAbuseGuard.class);
    private static final String LOCKED_MESSAGE = "Too many incorrect attempts. Please try again later.";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    public AuthAbuseGuard(RateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * Runs one verification attempt under the phone+IP budget, as an atomic increment-then-check (audit F-09). The
     * attempt is RESERVED first - one atomic sliding-window hit, so parallel requests cannot all pass a "peek" and
     * overshoot the limit - and only then is the code examined:
     * <ul>
     *   <li>budget spent: 429 {@code OTP_VERIFY_RATE_LIMITED} (503 if Redis is down), the code is never examined;</li>
     *   <li>wrong code ({@link InvalidOtpException}): the reservation stays - that is the failure being counted;</li>
     *   <li>success: the counter for this address is cleared;</li>
     *   <li>anything else (no pending code, expired, wrong portal, ...): the reservation is given back. Those
     *       outcomes can be produced at will with no OTP and no SIM, so they must never eat an address's budget (F-02).</li>
     * </ul>
     * Failures from other addresses never block this one. The phone-wide counter is an alert signal only.
     */
    public <T> T guardVerify(String phone, String clientIp, Supplier<T> verification) {
        String pair = pairSubject(phone, clientIp);
        RateLimitDecision reservation = rateLimitService.hit(RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP, pair,
                properties.otpVerifyFailuresPhoneIp(), FailureMode.FAIL_CLOSED);
        if (!reservation.allowed()) {
            throw new RateLimitedException("OTP_VERIFY_RATE_LIMITED", LOCKED_MESSAGE, reservation.retryAfterSeconds());
        }

        T result;
        try {
            result = verification.get();
        } catch (InvalidOtpException e) {
            recordPhoneWideFailure(phone);
            throw e;
        } catch (RuntimeException | Error e) {
            rateLimitService.release(RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP, pair);
            throw e;
        }
        rateLimitService.reset(RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP, pair);
        return result;
    }

    /** {@link #guardVerify(String, String, Supplier)} for verifications with no result. */
    public void guardVerifyRun(String phone, String clientIp, Runnable verification) {
        guardVerify(phone, clientIp, () -> {
            verification.run();
            return null;
        });
    }

    private void recordPhoneWideFailure(String phone) {
        RateLimitDecision byPhone = rateLimitService.hit(RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE, phoneSubject(phone),
                properties.otpVerifyFailuresPhone(), FailureMode.LOCAL_FALLBACK);
        if (byPhone.count() == byPhone.limit()) {
            // Alert signal only. No subject in the log line: it would be a phone number.
            log.warn("OTP guessing alert: a phone number reached {} failed verifications within {} across addresses",
                    byPhone.limit(), properties.otpVerifyFailuresPhone().window());
        }
    }

    /**
     * A new code was sent: its attempts start from zero for this address. Bounded because each code still dies after
     * {@code app.otp.max-attempts} wrong guesses and OTP requests per phone and per IP are capped.
     */
    public void recordOtpRequested(String phone, String clientIp) {
        rateLimitService.reset(RateLimitPolicies.OTP_VERIFY_FAILURES_PHONE_IP, pairSubject(phone, clientIp));
    }

    /**
     * Per-session budget for refresh/logout. The raw token is hashed inside {@link RateLimitKeys#key}, so it never
     * reaches Redis. A client legitimately refreshes about once per access-token lifetime; a burst on one token is a
     * replay or a bug.
     */
    public void assertRefreshAllowed(String rawRefreshToken) {
        rateLimitService.enforce(RateLimitPolicies.REFRESH_PER_TOKEN, rawRefreshToken,
                properties.refreshPerToken(), FailureMode.FAIL_CLOSED);
    }

    private static String phoneSubject(String phone) {
        return RateLimitKeys.normalizePhone(phone);
    }

    private static String pairSubject(String phone, String clientIp) {
        return RateLimitKeys.normalizePhone(phone) + "|" + ClientIpResolver.bucket(clientIp);
    }

}
