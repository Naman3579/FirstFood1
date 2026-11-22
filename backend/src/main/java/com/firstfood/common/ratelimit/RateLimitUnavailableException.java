package com.firstfood.common.ratelimit;

import com.firstfood.common.error.DomainException;
import com.firstfood.common.error.RetryAfterAware;
import org.springframework.http.HttpStatus;

/**
 * HTTP 503: the limiter could not do its job and the route is configured to fail closed. Deliberately NOT a 429 -
 * the caller did nothing wrong, and clients/alerts must be able to tell "you are too fast" from "we are degraded".
 */
public class RateLimitUnavailableException extends DomainException implements RetryAfterAware {

    public static final String CODE = "SERVICE_UNAVAILABLE";
    public static final long RETRY_AFTER_SECONDS = 10;

    public RateLimitUnavailableException() {
        super(CODE, "The service is temporarily unavailable. Please try again shortly.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public long retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }
}
