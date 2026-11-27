package com.firstfood.common.ratelimit;

/** The backing store could not be reached or returned something unusable. */
public class RateLimitStoreException extends RuntimeException {

    public RateLimitStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateLimitStoreException(String message) {
        super(message);
    }
}
