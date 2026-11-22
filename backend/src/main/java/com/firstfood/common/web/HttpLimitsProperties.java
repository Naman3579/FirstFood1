package com.firstfood.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxRequestBodyBytes upper bound for any request body. This API only exchanges small JSON documents (there
 *                            are no uploads); 64 KiB is far above any legitimate request and far below anything
 *                            that could pressure memory. Raise it deliberately if an upload endpoint is ever added.
 */
@ConfigurationProperties(prefix = "app.http")
public record HttpLimitsProperties(long maxRequestBodyBytes) {

    public HttpLimitsProperties {
        if (maxRequestBodyBytes < 1) {
            throw new IllegalArgumentException("app.http.max-request-body-bytes must be >= 1");
        }
    }
}
