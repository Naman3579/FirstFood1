package com.firstfood.identity.web;

import com.firstfood.common.config.CorsProperties;
import com.firstfood.identity.JwtProperties;
import com.firstfood.identity.RefreshCookieProperties;
import com.firstfood.identity.error.ForbiddenOriginException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Optional HttpOnly-cookie transport for the refresh token (browser, same-origin proxy mode).
 *
 * A client opts in per request with {@code X-Refresh-Mode: cookie}. Everything else - direct mode, scripts, mobile
 * apps, every existing test - keeps receiving and sending the refresh token in the JSON body, unchanged.
 *
 * Why this is safe without turning CSRF protection back on globally: the cookie is consulted ONLY by the two
 * endpoints that accept it (refresh, logout) and nowhere else; every other endpoint authenticates with the
 * Authorization header, which a cross-site request cannot attach. For those two endpoints a forged request must get
 * past FOUR independent checks:
 * <ol>
 *   <li>{@code SameSite=Strict} - the browser does not send the cookie on cross-site requests at all;</li>
 *   <li>{@code Path=/api/v1/auth} - the cookie is not sent to any other endpoint;</li>
 *   <li>the custom {@code X-Refresh-Mode} header - a cross-origin page cannot send it without a CORS preflight,
 *       which the exact-origin allowlist refuses;</li>
 *   <li>an explicit {@code Origin} allowlist check here (browsers always send Origin on POST).</li>
 * </ol>
 * {@code HttpOnly} keeps the token away from page scripts, so an XSS bug cannot exfiltrate it.
 */
@Component
public class RefreshCookies {

    public static final String MODE_HEADER = "X-Refresh-Mode";
    public static final String MODE_COOKIE = "cookie";
    static final String COOKIE_PATH = "/api/v1/auth";

    private final RefreshCookieProperties properties;
    private final CorsProperties corsProperties;
    private final JwtProperties jwtProperties;

    public RefreshCookies(
            RefreshCookieProperties properties, CorsProperties corsProperties, JwtProperties jwtProperties) {
        this.properties = properties;
        this.corsProperties = corsProperties;
        this.jwtProperties = jwtProperties;
    }

    public boolean cookieModeRequested(HttpServletRequest request) {
        return MODE_COOKIE.equalsIgnoreCase(request.getHeader(MODE_HEADER));
    }

    /** Throws 403 unless the request's Origin is exactly one of the configured allowed origins. */
    public void assertAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || !corsProperties.allowedOrigins().contains(origin)) {
            throw new ForbiddenOriginException();
        }
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (properties.name().equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(Duration.ofDays(jwtProperties.refreshTokenTtlDays())).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }
}
