package com.firstfood.identity;

/**
 * Requests and verifies OTP challenges. This is the identity module's
 * public application-service surface for OTP handling - other modules
 * (and controllers) should depend on this interface, not on
 * {@link OtpVerificationRepository} or {@link OtpSender} directly.
 */
public interface OtpService {

    /**
     * Issues a new OTP for the given phone/purpose and sends it via the
     * configured {@link OtpSender}. Throws
     * {@link com.firstfood.identity.error.OtpRateLimitExceededException}
     * if the phone number has requested too many OTPs recently
     * (rules.md Rule 17.1).
     */
    void requestOtp(String phone, OtpPurpose purpose);

    /**
     * Verifies a code for the given phone/purpose. On success, marks the
     * OTP consumed and returns normally. On failure, records the failed
     * attempt (rules.md Rule 17.1 verification-attempt limiting) and
     * throws
     * {@link com.firstfood.identity.error.InvalidOtpException} or
     * {@link com.firstfood.identity.error.OtpExpiredException}.
     */
    void verifyOtp(String phone, OtpPurpose purpose, String code);

    /**
     * Like {@link #requestOtp} but the code is emailed (through {@link EmailOtpSender}) to {@code email}, which must
     * already be lower-cased. Shares the per-recipient hourly request budget.
     */
    void requestEmailOtp(String email, OtpPurpose purpose);

    /**
     * Checks a code exactly like {@link #verifyOtp} (wrong codes count as failed attempts, expired codes are
     * rejected) but leaves a correct code PENDING so the caller can still refuse the request for another reason
     * before spending it. Call {@link #verifyOtp} afterwards to consume.
     */
    void checkOtp(String phone, OtpPurpose purpose, String code);
}
