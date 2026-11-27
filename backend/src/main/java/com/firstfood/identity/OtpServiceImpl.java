package com.firstfood.identity;

import com.firstfood.identity.dto.PhonePattern;
import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitKeys;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitRule;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.identity.error.InvalidOtpException;
import com.firstfood.identity.error.OtpExpiredException;
import com.firstfood.identity.error.OtpRateLimitExceededException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link OtpService}. Rate limiting (rules.md Rule 17.1) uses
 * a Redis fixed-window counter rather than a database row, since it's a
 * high-frequency, short-lived check that doesn't need to be durable
 * business history. The counter itself lives in
 * {@link RateLimitService}: increment and expiry are one atomic Redis
 * script, the phone number is normalised and hashed before it becomes a
 * key, and if Redis is down this FAILS CLOSED (503) instead of sending
 * unlimited SMS.
 */
@Service
public class OtpServiceImpl implements OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);

    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpSender otpSender;
    private final EmailOtpSender emailOtpSender;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final OtpProperties otpProperties;
    private final SmsDestinationPolicy smsDestinationPolicy;
    private final SmsBudgetGuard smsBudgetGuard;

    public OtpServiceImpl(
            OtpVerificationRepository otpVerificationRepository,
            OtpSender otpSender,
            EmailOtpSender emailOtpSender,
            PasswordEncoder passwordEncoder,
            RateLimitService rateLimitService,
            OtpProperties otpProperties,
            SmsDestinationPolicy smsDestinationPolicy,
            SmsBudgetGuard smsBudgetGuard) {
        this.otpVerificationRepository = otpVerificationRepository;
        this.otpSender = otpSender;
        this.emailOtpSender = emailOtpSender;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.otpProperties = otpProperties;
        this.smsDestinationPolicy = smsDestinationPolicy;
        this.smsBudgetGuard = smsBudgetGuard;
    }

    /**
     * The ONE door every phone OTP goes through (login, phone change, owner step-up), so the F-05 checks cannot be
     * skipped by picking a different route. Order matters, cheapest and most specific first:
     * <ol>
     *   <li>destination allow-list - a pure function of the number, costs nothing, leaks nothing about accounts;</li>
     *   <li>per-phone limit - a flood aimed at one number stops here and never touches the shared budget;</li>
     *   <li>site-wide hourly/daily budget (circuit breaker) - the last line when numbers and IPs are both rotated.</li>
     * </ol>
     * Only a request that passed all three produces an SMS.
     */
    @Override
    @Transactional
    public void requestOtp(String rawPhone, OtpPurpose purpose) {
        String phone = PhonePattern.addPlus(rawPhone); // F-13: the challenge is stored under the canonical number
        smsDestinationPolicy.assertAllowed(phone);
        enforceRateLimit(phone);
        smsBudgetGuard.reserve();

        String code = generateSixDigitCode();
        String hash = passwordEncoder.encode(code);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(otpProperties.ttlMinutes()));

        otpVerificationRepository.save(new OtpVerification(phone, purpose, hash, expiresAt));
        otpSender.send(phone, code);
    }

    @Override
    @Transactional
    public void requestEmailOtp(String email, OtpPurpose purpose) {
        // Same hourly budget as SMS, in its own key space so an email can never eat a phone's allowance.
        enforceRateLimit("email:" + email);

        String code = generateSixDigitCode();
        String hash = passwordEncoder.encode(code);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(otpProperties.ttlMinutes()));

        otpVerificationRepository.save(new OtpVerification(email, purpose, hash, expiresAt));
        emailOtpSender.send(email, code);
    }

    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    @Override
    public void verifyOtp(String phone, OtpPurpose purpose, String code) {
        check(phone, purpose, code, true);
    }

    @Transactional(noRollbackFor = {InvalidOtpException.class, OtpExpiredException.class})
    @Override
    public void checkOtp(String phone, OtpPurpose purpose, String code) {
        check(phone, purpose, code, false);
    }

    private void check(String rawPhone, OtpPurpose purpose, String code, boolean consume) {
        String phone = PhonePattern.addPlus(rawPhone); // an email (EMAIL_CHANGE) is not bare digits and passes through
        OtpVerification otp = otpVerificationRepository
                .findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(phone, purpose, OtpStatus.PENDING)
                .orElseThrow(OtpExpiredException::new);

        if (otp.isExpired(Instant.now())) {
            otp.markExpired();
            otpVerificationRepository.save(otp);
            throw new OtpExpiredException();
        }

        // F-09: take a guess from the challenge's budget BEFORE comparing, in one atomic UPDATE. Parallel requests that
        // all passed the PENDING lookup above cannot all compare: the database grants at most max-attempts reservations.
        // A refused reservation means the budget is spent (or the challenge ended a moment ago) - same answer as any
        // dead code. Never save the entity after this point: its attempt counter is stale.
        int maxAttempts = otpProperties.maxAttempts();
        if (otpVerificationRepository.reserveAttempt(otp.getId(), Instant.now(), maxAttempts) != 1) {
            throw new OtpExpiredException();
        }

        if (!passwordEncoder.matches(code, otp.getOtpHash())) {
            otpVerificationRepository.expireIfExhausted(otp.getId(), maxAttempts);
            throw new InvalidOtpException();
        }

        if (consume) {
            // Atomic spend (audit F-01): a read-then-save here let two parallel requests both see PENDING and both
            // succeed. Only the request whose UPDATE actually flips the row may proceed; anyone else - a concurrent
            // replay of the same code - is told the code is no longer usable, exactly like a sequential replay.
            // Callers always verifyOtp() BEFORE issuing tokens or registering a portal, so the loser gets nothing.
            if (otpVerificationRepository.consumeIfPending(otp.getId(), Instant.now()) != 1) {
                throw new OtpExpiredException();
            }
        } else {
            // Right code, only checked: the guess is not held against the code (it is verified again right after).
            otpVerificationRepository.refundAttempt(otp.getId());
        }
    }

    private void enforceRateLimit(String phone) {
        RateLimitDecision decision = rateLimitService.hit(
                RateLimitPolicies.OTP_REQUEST_PHONE,
                RateLimitKeys.normalizePhone(phone),
                new RateLimitRule(otpProperties.requestRateLimitPerHour(), REQUEST_WINDOW),
                FailureMode.FAIL_CLOSED);
        if (!decision.allowed()) {
            throw new OtpRateLimitExceededException(decision.retryAfterSeconds());
        }
    }

    private String generateSixDigitCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }
}
