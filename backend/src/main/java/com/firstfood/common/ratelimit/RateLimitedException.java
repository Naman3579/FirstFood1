package com.firstfood.common.ratelimit;

import com.firstfood.common.error.DomainException;
import com.firstfood.common.error.RetryAfterAware;
import org.springframework.http.HttpStatus;

/** HTTP 429. The handler adds {@code Retry-After}. */
public class RateLimitedException extends DomainException implements RetryAfterAware {

    public static final String DEFAULT_CODE = "RATE_LIMITED";
    public static final String DEFAULT_MESSAGE = "Too many requests. Please try again later.";

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        this(DEFAULT_CODE, DEFAULT_MESSAGE, retryAfterSeconds);
    }

    public RateLimitedException(String code, String message, long retryAfterSeconds) {
        super(code, message, HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    @Override
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
