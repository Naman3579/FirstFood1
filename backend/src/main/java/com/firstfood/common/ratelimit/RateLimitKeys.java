package com.firstfood.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds Redis keys and normalises the identifiers they are made from. */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /**
     * Keys carry a truncated SHA-256 of the subject, never the raw phone number or IP: Redis dumps, MONITOR
     * output and provider dashboards then contain no personal data. 128 bits is ample to avoid collisions.
     */
    public static String key(String prefix, String policy, String subject) {
        return prefix + ":" + policy + ":" + hash(subject);
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /**
     * "+919876543210" and "919876543210" are the same SIM, so they must share one budget; otherwise an attacker
     * doubles their allowance just by toggling the "+". Only used for limiter keys; stored phone numbers are
     * canonical '+' numbers since F-13 (PhonePattern).
     */
    public static String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String trimmed = phone.trim();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }
}
