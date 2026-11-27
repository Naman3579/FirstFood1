package com.firstfood.common.error;

/** Implemented by exceptions whose HTTP response should carry a {@code Retry-After} header (429 / 503). */
public interface RetryAfterAware {

    long retryAfterSeconds();
}
