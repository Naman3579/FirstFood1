package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.common.error.RetryAfterAware;
import org.springframework.http.HttpStatus;

/**
 * 503: the site-wide SMS budget (hourly or daily) is spent, so the circuit breaker is open and no more OTP texts are
 * sent until it resets (F-05). Deliberately says nothing about the cause: this is the response an SMS-pumping attack
 * produces, and detail would only help the attacker tune it.
 */
public class SmsBudgetExceededException extends DomainException implements RetryAfterAware {

    /** Never tell a client to wait longer than this, even when the daily budget resets much later. */
    private static final long MAX_RETRY_AFTER_SECONDS = 3600;

    private final long retryAfterSeconds;

    public SmsBudgetExceededException(long retryAfterSeconds) {
        super("SMS_TEMPORARILY_UNAVAILABLE",
                "We cannot send verification codes right now. Please try again later.",
                HttpStatus.SERVICE_UNAVAILABLE);
        this.retryAfterSeconds = Math.min(Math.max(1, retryAfterSeconds), MAX_RETRY_AFTER_SECONDS);
    }

    @Override
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
