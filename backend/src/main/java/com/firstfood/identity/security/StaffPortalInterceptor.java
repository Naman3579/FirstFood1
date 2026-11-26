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
 * Everything provider-scoped is the staff portal's API. An account that never registered on the staff portal gets
 * 403 PORTAL_ACCESS_DENIED here, before any controller runs - so a customer-only account cannot create or manage a
 * provider even though the customer sign-in is a perfectly valid login.
 *
 * Runs after Spring Security, so the principal is already authenticated (an anonymous request was rejected with 401
 * long before this point).
 *
 * <p>This is a UI door, not a security boundary (F-12): staff sign-up is open to anyone with an OTP, so passing this
 * check proves nothing about the caller. Every provider-scoped service still checks the caller's role on that
 * provider, and answers 404 (same as "no such provider") otherwise. Do not add decisions here that belong to a role.
 */
@Component
public class StaffPortalInterceptor implements HandlerInterceptor {

    private final AccountLookupService accounts;

    public StaffPortalInterceptor(AccountLookupService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedAccount principal
                && !accounts.hasPortal(principal.accountId(), Portal.STAFF)) {
            throw new PortalAccessDeniedException(Portal.STAFF);
        }
        return true;
    }
}
