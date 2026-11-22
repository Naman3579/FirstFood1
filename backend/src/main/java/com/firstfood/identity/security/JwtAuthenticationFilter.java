package com.firstfood.identity.security;

import com.firstfood.identity.AccountStatus;
import com.firstfood.identity.RefreshSessionService;
import com.firstfood.identity.TokenService;
import com.firstfood.identity.UserAccount;
import com.firstfood.identity.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <token>}, validates it via
 * {@link TokenService}, and populates the Spring Security context with an
 * {@link AuthenticatedAccount} principal - but only if the account still
 * exists AND is still {@link AccountStatus#ACTIVE}.
 *
 * F-11: the token's session ({@code sid}) must also still be alive, so logging out, changing the phone number, or a
 * detected token theft ends access tokens immediately, not after their 15 minutes. One more indexed read per request.
 *
 * A JWT being cryptographically valid and unexpired is NOT enough
 * (FirstFood_V2_Phase2_Final_Review.md #12/#19 - a suspended-after-issue
 * account must not keep working on its old access token until natural
 * expiry). This does mean one extra DB read per authenticated request;
 * acceptable for Phase 2 traffic, worth revisiting (e.g. a short-TTL
 * Redis cache of account status) if this becomes a hot path.
 *
 * A missing/invalid/suspended-account token all leave the request
 * unauthenticated rather than this filter itself rejecting it -
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()} plus its
 * 401 entry point is what actually rejects it.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final UserAccountRepository userAccountRepository;
    private final RefreshSessionService refreshSessions;

    public JwtAuthenticationFilter(
            TokenService tokenService,
            UserAccountRepository userAccountRepository,
            RefreshSessionService refreshSessions) {
        this.tokenService = tokenService;
        this.userAccountRepository = userAccountRepository;
        this.refreshSessions = refreshSessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        extractToken(request)
                .flatMap(tokenService::parse)
                // F-11: the session behind the token must still be alive (logout / revocation / theft handling).
                .filter(claims -> refreshSessions.isSessionLive(claims.sessionId()))
                .flatMap(claims -> activeAccount(claims.accountId()).map(id -> claims))
                .ifPresent(this::authenticate);

        filterChain.doFilter(request, response);
    }

    private Optional<UUID> activeAccount(UUID accountId) {
        return userAccountRepository.findById(accountId)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .map(UserAccount::getId);
    }

    private void authenticate(TokenService.AccessClaims claims) {
        var authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedAccount(claims.accountId(), claims.sessionId()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }
}
