package com.firstfood.identity.security;

import com.firstfood.common.ratelimit.FailureMode;
import com.firstfood.common.ratelimit.RateLimitDecision;
import com.firstfood.common.ratelimit.RateLimitPolicies;
import com.firstfood.common.ratelimit.RateLimitProperties;
import com.firstfood.common.ratelimit.RateLimitResponses;
import com.firstfood.common.ratelimit.RateLimitService;
import com.firstfood.common.ratelimit.RateLimitUnavailableException;
import com.firstfood.common.ratelimit.RouteClass;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-ACCOUNT budgets for authenticated traffic: reads and writes are limited separately, so a busy dashboard cannot
 * starve edits and a runaway client cannot hammer mutations. Keyed on the account, not the IP, so users behind a
 * shared NAT do not compete with each other.
 *
 * Runs right after {@link JwtAuthenticationFilter} (see SecurityConfig) because it needs the authenticated principal.
 * Unauthenticated requests pass straight through; the per-IP filter already covered them.
 */
@Component
public class AccountRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    public AccountRateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!properties.enabled() || authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedAccount account)) {
            chain.doFilter(request, response);
            return;
        }

        String servletPath = request.getServletPath();
        String path = request.getPathInfo() == null ? servletPath : servletPath + request.getPathInfo();
        RouteClass routeClass = RouteClass.classify(request.getMethod(), path);
        if (routeClass == RouteClass.EXEMPT) {
            chain.doFilter(request, response);
            return;
        }

        boolean read = routeClass == RouteClass.READ;
        try {
            RateLimitDecision decision = rateLimitService.hit(
                    read ? RateLimitPolicies.ACCOUNT_READ : RateLimitPolicies.ACCOUNT_WRITE,
                    account.accountId().toString(),
                    read ? properties.accountRead() : properties.accountWrite(),
                    FailureMode.LOCAL_FALLBACK);
            if (!decision.allowed()) {
                RateLimitResponses.tooManyRequests(response, decision.retryAfterSeconds());
                return;
            }
        } catch (RateLimitUnavailableException e) {
            RateLimitResponses.unavailable(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
