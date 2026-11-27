package com.firstfood.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Coarse, identity-blind abuse protection by client address, run before authentication so a flood of junk or
 * unauthenticated requests is turned away before it costs a database read. Identity-aware limits (per phone, per
 * account) live in the services/filters that know the identity.
 *
 * Budgets are deliberately generous for ordinary API traffic - one public IP can be a whole college, hostel or
 * carrier NAT - and strict only where each request costs money or is a guessing attempt (OTP, token endpoints).
 */
@Component
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final RequestClientIp clientIp;

    public IpRateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties, RequestClientIp clientIp) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.clientIp = clientIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        RouteClass routeClass = RouteClass.classify(request.getMethod(), pathOf(request));
        if (routeClass == RouteClass.EXEMPT) {
            chain.doFilter(request, response);
            return;
        }

        String subject = clientIp.bucket(request);
        try {
            RateLimitDecision denied = firstDenial(routeClass, subject);
            if (denied != null) {
                RateLimitResponses.tooManyRequests(response, denied.retryAfterSeconds());
                return;
            }
        } catch (RateLimitUnavailableException e) {
            RateLimitResponses.unavailable(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Evaluates the class's budgets in order and stops at the first one that is exhausted (so a blocked burst does not also burn the hourly budget). */
    private RateLimitDecision firstDenial(RouteClass routeClass, String subject) {
        RateLimitDecision d;
        switch (routeClass) {
            case OTP_REQUEST -> {
                d = rateLimitService.hit(RateLimitPolicies.OTP_REQUEST_IP_BURST, subject,
                        properties.otpRequestIpBurst(), FailureMode.FAIL_CLOSED);
                if (!d.allowed()) {
                    return d;
                }
                d = rateLimitService.hit(RateLimitPolicies.OTP_REQUEST_IP_HOURLY, subject,
                        properties.otpRequestIpHourly(), FailureMode.FAIL_CLOSED);
            }
            case OTP_VERIFY -> d = rateLimitService.hit(RateLimitPolicies.OTP_VERIFY_IP, subject,
                    properties.otpVerifyIp(), FailureMode.FAIL_CLOSED);
            case AUTH_TOKEN -> d = rateLimitService.hit(RateLimitPolicies.AUTH_IP, subject,
                    properties.authIp(), FailureMode.FAIL_CLOSED);
            default -> d = rateLimitService.hit(RateLimitPolicies.API_IP, subject,
                    properties.apiIp(), FailureMode.LOCAL_FALLBACK);
        }
        return d.allowed() ? null : d;
    }

    static String pathOf(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
    }
}
