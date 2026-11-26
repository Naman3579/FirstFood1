package com.firstfood.common.ratelimit;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Writes the same error body the {@code GlobalExceptionHandler} produces, for rejections made before Spring MVC runs. */
public final class RateLimitResponses {

    private RateLimitResponses() {
    }

    public static void tooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        write(response, 429, RateLimitedException.DEFAULT_CODE, RateLimitedException.DEFAULT_MESSAGE, retryAfterSeconds);
    }

    public static void unavailable(HttpServletResponse response) throws IOException {
        write(response, 503, RateLimitUnavailableException.CODE,
                "The service is temporarily unavailable. Please try again shortly.",
                RateLimitUnavailableException.RETRY_AFTER_SECONDS);
    }

    public static void write(HttpServletResponse response, int status, String code, String message, long retryAfterSeconds)
            throws IOException {
        response.setStatus(status);
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"timestamp\":\""
                + Instant.now() + "\"}");
    }
}
