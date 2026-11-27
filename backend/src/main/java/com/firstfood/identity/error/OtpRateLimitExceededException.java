package com.firstfood.identity.error;

import com.firstfood.common.ratelimit.RateLimitedException;

/** 429 for too many OTP SMS requests for one phone number. Keeps its original, client-visible error code. */
public class OtpRateLimitExceededException extends RateLimitedException {

    public OtpRateLimitExceededException(long retryAfterSeconds) {
        super("OTP_RATE_LIMIT_EXCEEDED",
                "Too many OTP requests for this number. Please try again later.",
                retryAfterSeconds);
    }
}
