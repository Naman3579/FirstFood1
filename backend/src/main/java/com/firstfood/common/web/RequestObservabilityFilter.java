package com.firstfood.common.web;

import com.firstfood.common.ratelimit.RateLimitKeys;
import com.firstfood.common.ratelimit.RequestClientIp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives every request a correlation id and writes ONE structured access-log line per request.
 *
 * Runs first of all (before Spring Security), so requests rejected as 401/413/429/503 are logged and carry an id too.
 *
 * What is logged: id, method, ROUTE TEMPLATE (e.g. {@code /api/v1/providers/{id}}), status, latency, and a short
 * hash of the client address. What is never logged: query strings, request/response bodies, Authorization headers,
 * cookies, OTPs, phone numbers, raw IPs. Metrics for latency/status come from Micrometer's own
 * {@code http.server.requests}; this filter is for logs and correlation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger("com.firstfood.access");
    /** Only well-formed inbound ids are echoed; anything else (log-injection attempts, huge values) is replaced. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private final RequestClientIp clientIp;

    public RequestObservabilityFilter(RequestClientIp clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(REQUEST_ID_HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern instanceof String s ? s : "UNMATCHED";
            boolean probe = request.getServletPath() != null && request.getServletPath().startsWith("/actuator/");

            if (status >= 500) {
                log.warn("request method={} route={} status={} durationMs={} client={}",
                        request.getMethod(), route, status, durationMs, clientTag(request));
            } else if (probe) {
                log.debug("request method={} route={} status={} durationMs={}",
                        request.getMethod(), route, status, durationMs);
            } else {
                log.info("request method={} route={} status={} durationMs={} client={}",
                        request.getMethod(), route, status, durationMs, clientTag(request));
            }
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /** Enough to tell "one noisy client" from "many clients" in logs, without storing an address. */
    private String clientTag(HttpServletRequest request) {
        return RateLimitKeys.hash(clientIp.bucket(request)).substring(0, 8);
    }
}
