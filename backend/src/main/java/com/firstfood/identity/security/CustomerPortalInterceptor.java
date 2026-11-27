package com.firstfood.identity.security;

import com.firstfood.identity.AccountLookupService;
import com.firstfood.identity.Portal;
import com.firstfood.identity.error.PortalAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The customer portal's own API (discovery and self-service subscribe): 403 PORTAL_ACCESS_DENIED for an account that
 * never registered on the customer portal. The mirror of {@link StaffPortalInterceptor}, and just as little a security
 * boundary (F-12): every customer-side lookup is scoped to the caller's own person ids.
 */
@Component
public class CustomerPortalInterceptor implements HandlerInterceptor {

    private final AccountLookupService accounts;

    public CustomerPortalInterceptor(AccountLookupService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedAccount principal
                && !accounts.hasPortal(principal.accountId(), Portal.CUSTOMER)) {
            throw new PortalAccessDeniedException(Portal.CUSTOMER);
        }
        return true;
    }
}
