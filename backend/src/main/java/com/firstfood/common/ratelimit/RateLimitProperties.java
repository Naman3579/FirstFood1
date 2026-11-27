package com.firstfood.common.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.rate-limit.*}. Every number is an initial guardrail for a small India-focused pilot, tuned from real
 * traffic - see docs/OPERATIONS.md. The per-phone OTP request budget lives in {@code app.otp.*} (unchanged).
 *
 * @param enabled                 master switch for the per-IP and per-account filters. The per-phone OTP and OTP
 *                                failure limits are enforced regardless.
 * @param keyPrefix               Redis key prefix; integration tests use a unique one per context so they never
 *                                share counters.
 * @param trustedProxies          CIDRs/IPs of reverse-proxy hops allowed to speak for the client in
 *                                {@code X-Forwarded-For}. EMPTY by default = the header is ignored.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        String keyPrefix,
        List<String> trustedProxies,
        RateLimitRule otpRequestIpBurst,
        RateLimitRule otpRequestIpHourly,
        RateLimitRule otpVerifyIp,
        RateLimitRule otpVerifyFailuresPhoneIp,
        RateLimitRule otpVerifyFailuresPhone,
        RateLimitRule authIp,
        RateLimitRule refreshPerToken,
        RateLimitRule apiIp,
        RateLimitRule accountRead,
        RateLimitRule accountWrite) {

    public RateLimitProperties {
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "rl" : keyPrefix;
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        require(otpRequestIpBurst, "otp-request-ip-burst");
        require(otpRequestIpHourly, "otp-request-ip-hourly");
        require(otpVerifyIp, "otp-verify-ip");
        require(otpVerifyFailuresPhoneIp, "otp-verify-failures-phone-ip");
        require(otpVerifyFailuresPhone, "otp-verify-failures-phone");
        require(authIp, "auth-ip");
        require(refreshPerToken, "refresh-per-token");
        require(apiIp, "api-ip");
        require(accountRead, "account-read");
        require(accountWrite, "account-write");
    }

    private static void require(RateLimitRule rule, String name) {
        if (rule == null) {
            throw new IllegalStateException("Missing required property app.rate-limit." + name);
        }
    }
}
