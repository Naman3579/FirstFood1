package com.firstfood.common.ratelimit;

/** Coarse traffic classes; each gets its own budget and failure behaviour. */
public enum RouteClass {
    /** Health and version probes: never limited here (the edge protects them). */
    EXEMPT,
    /** Anything that makes the server send an SMS. Costs real money, so the strictest class. */
    OTP_REQUEST,
    /** Guessing the 6-digit code. */
    OTP_VERIFY,
    /** Refresh / logout. */
    AUTH_TOKEN,
    /** Safe methods. */
    READ,
    /** Everything that mutates. */
    WRITE;

    public static RouteClass classify(String method, String path) {
        if (path == null) {
            return method != null && isSafe(method) ? READ : WRITE;
        }
        if (path.equals("/api/v1/version") || path.startsWith("/actuator/")) {
            return EXEMPT;
        }
        boolean post = "POST".equalsIgnoreCase(method);
        if (post && (path.equals("/api/v1/auth/otp/request") || path.equals("/api/v1/auth/email/otp/request") || path.equals("/api/v1/me/phone/otp/request")
                || path.equals("/api/v1/me/email/otp/request"))) {
            return OTP_REQUEST;
        }
        if (post && (path.equals("/api/v1/auth/otp/verify") || path.equals("/api/v1/me/phone/otp/verify")
                || path.equals("/api/v1/me/email/otp/verify") || isPortalCodeRoute(path))) {
            return OTP_VERIFY;
        }
        if (path.startsWith("/api/v1/auth/")) {
            return AUTH_TOKEN;
        }
        return method != null && isSafe(method) ? READ : WRITE;
    }

    /**
     * POST /api/v1/auth/{portal}/signin|signup and /api/v1/auth/{portal}/email/signin|signup all spend a 6-digit code,
     * so they share the verify budget.
     */
    private static boolean isPortalCodeRoute(String path) {
        String[] parts = path.split("/");
        if (parts.length < 6 || !parts[3].equals("auth")) {
            return false;
        }
        // ["", "api", "v1", "auth", "<portal>", "signin|signup"]
        if (parts.length == 6) {
            return parts[5].equals("signin") || parts[5].equals("signup");
        }
        // ["", "api", "v1", "auth", "<portal>", "email", "signin|signup"]
        return parts.length == 7 && parts[5].equals("email")
                && (parts[6].equals("signin") || parts[6].equals("signup"));
    }

    private static boolean isSafe(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }
}
