package com.firstfood.identity.web;

import com.firstfood.common.ratelimit.RequestClientIp;
import com.firstfood.identity.AuthAbuseGuard;
import com.firstfood.identity.BotChallengeVerifier;
import com.firstfood.identity.SmsDestinationPolicy;
import com.firstfood.identity.AuthTokens;
import com.firstfood.identity.Portal;
import com.firstfood.identity.AuthenticationService;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.EmailOtpRequest;
import com.firstfood.identity.dto.EmailOtpVerifyRequest;
import com.firstfood.identity.dto.EmailSignUpRequest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.RefreshTokenRequest;
import com.firstfood.identity.error.InvalidPortalException;
import com.firstfood.identity.error.InvalidRefreshTokenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) auth endpoints - see SecurityConfig for the
 * /api/v1/auth/** permitAll rule. Implements phases.md Phase 2's exit
 * criteria: request OTP -> verify OTP -> authenticate -> refresh ->
 * logout.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final AuthAbuseGuard abuseGuard;
    private final RequestClientIp clientIp;
    private final RefreshCookies refreshCookies;
    private final SmsDestinationPolicy smsDestinationPolicy;
    private final BotChallengeVerifier botChallengeVerifier;

    public AuthController(
            AuthenticationService authenticationService,
            AuthAbuseGuard abuseGuard,
            RequestClientIp clientIp,
            RefreshCookies refreshCookies,
            SmsDestinationPolicy smsDestinationPolicy,
            BotChallengeVerifier botChallengeVerifier) {
        this.authenticationService = authenticationService;
        this.abuseGuard = abuseGuard;
        this.clientIp = clientIp;
        this.refreshCookies = refreshCookies;
        this.smsDestinationPolicy = smsDestinationPolicy;
        this.botChallengeVerifier = botChallengeVerifier;
    }

    /**
     * The one PUBLIC endpoint that turns an anonymous request into an SMS (F-05), so it is the one that needs a bot
     * challenge. Order: (1) refuse a destination outside the allow-list - free, and it must not spend the one-time
     * challenge token; (2) verify the challenge token ({@code X-Challenge-Token}, a no-op while the challenge is
     * disabled); (3) per-phone limit and the site-wide SMS budget inside the OTP service, which every phone OTP
     * (including phone change and owner step-up) goes through. The authenticated OTP routes need no challenge: the
     * caller already proved a session, and they share the same allow-list and budget.
     */
    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(
            @Valid @RequestBody OtpRequestRequest request,
            @RequestHeader(name = "X-Challenge-Token", required = false) String challengeToken,
            HttpServletRequest httpRequest) {
        String ip = clientIp.of(httpRequest);
        smsDestinationPolicy.assertAllowed(request.phone());
        botChallengeVerifier.verify(challengeToken, ip);
        authenticationService.requestLoginOtp(request.phone());
        abuseGuard.recordOtpRequested(request.phone(), ip);
        return ResponseEntity.accepted().build();
    }

    /**
     * The original portal-less login, kept for existing accounts. It no longer creates accounts unless
     * {@code app.auth.legacy-login.register-new-accounts} is on (local/test only) - see {@link com.firstfood.identity.LegacyLoginProperties}
     * (F-12). New users go through {@code /{portal}/signup}.
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<AuthTokensResponse> verifyOtp(
            @Valid @RequestBody OtpVerifyRequest request, HttpServletRequest httpRequest) {
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
        }
        String ip = clientIp.of(httpRequest);
        AuthTokens tokens = abuseGuard.guardVerify(request.phone(), ip,
                () -> authenticationService.verifyLoginOtp(request.phone(), request.code()));
        return toResponse(tokens, cookieMode);
    }

    /**
     * Portal sign-in ("customer" or "staff"): the code proves the number, the portal decides the door. 404
     * ACCOUNT_NOT_REGISTERED (code not spent) when the number has no registration on that portal.
     */
    @PostMapping("/{portal}/signin")
    public ResponseEntity<AuthTokensResponse> signIn(
            @PathVariable String portal, @Valid @RequestBody OtpVerifyRequest request, HttpServletRequest httpRequest) {
        Portal target = parsePortal(portal);
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
        }
        String ip = clientIp.of(httpRequest);
        AuthTokens tokens = abuseGuard.guardVerify(request.phone(), ip,
                () -> authenticationService.signIn(target, request.phone(), request.code()));
        return toResponse(tokens, cookieMode);
    }

    /** Portal sign-up: creates the account, or adds this portal to the number's existing account. */
    @PostMapping("/{portal}/signup")
    public ResponseEntity<AuthTokensResponse> signUp(
            @PathVariable String portal, @Valid @RequestBody PortalSignUpRequest request, HttpServletRequest httpRequest) {
        Portal target = parsePortal(portal);
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
        }
        String ip = clientIp.of(httpRequest);
        AuthTokens tokens = abuseGuard.guardVerify(request.phone(), ip,
                () -> authenticationService.signUp(target, request.phone(), request.code(), request.fullName()));
        return toResponse(tokens, cookieMode);
    }

    /**
     * Email twin of {@link #requestOtp}: the public endpoint that turns an anonymous request into an EMAIL. It needs the
     * same bot challenge (the sender's quota is a shared, finite resource) and is classed OTP_REQUEST for the per-IP
     * limiter; the per-address hourly limit lives in the OTP service. No SMS allow-list or SMS budget applies.
     */
    @PostMapping("/email/otp/request")
    public ResponseEntity<Void> requestEmailOtp(
            @Valid @RequestBody EmailOtpRequest request,
            @RequestHeader(name = "X-Challenge-Token", required = false) String challengeToken,
            HttpServletRequest httpRequest) {
        String ip = clientIp.of(httpRequest);
        botChallengeVerifier.verify(challengeToken, ip);
        authenticationService.requestEmailLoginOtp(request.email());
        abuseGuard.recordOtpRequested(request.email(), ip);
        return ResponseEntity.accepted().build();
    }

    /** Portal sign-in by email: the code proves the address, the portal decides the door. */
    @PostMapping("/{portal}/email/signin")
    public ResponseEntity<AuthTokensResponse> signInWithEmail(
            @PathVariable String portal, @Valid @RequestBody EmailOtpVerifyRequest request, HttpServletRequest httpRequest) {
        Portal target = parsePortal(portal);
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
        }
        String ip = clientIp.of(httpRequest);
        AuthTokens tokens = abuseGuard.guardVerify(request.email(), ip,
                () -> authenticationService.signInWithEmail(target, request.email(), request.code()));
        return toResponse(tokens, cookieMode);
    }

    /** Portal sign-up by email: creates an email-only account, or adds this portal to the address's existing account. */
    @PostMapping("/{portal}/email/signup")
    public ResponseEntity<AuthTokensResponse> signUpWithEmail(
            @PathVariable String portal, @Valid @RequestBody EmailSignUpRequest request, HttpServletRequest httpRequest) {
        Portal target = parsePortal(portal);
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
        }
        String ip = clientIp.of(httpRequest);
        AuthTokens tokens = abuseGuard.guardVerify(request.email(), ip,
                () -> authenticationService.signUpWithEmail(target, request.email(), request.code(), request.fullName()));
        return toResponse(tokens, cookieMode);
    }

    private static Portal parsePortal(String value) {
        return Portal.fromPath(value).orElseThrow(InvalidPortalException::new);
    }

    /**
     * Body mode (default): {"refreshToken": "..."} in, new pair out. Cookie mode ({@code X-Refresh-Mode: cookie}): the
     * refresh token comes from the HttpOnly cookie, the new one goes back as a cookie, and the body carries only the
     * access token. See {@link RefreshCookies} for why cookie mode is CSRF-safe.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokensResponse> refresh(
            @Valid @RequestBody(required = false) RefreshTokenRequest request, HttpServletRequest httpRequest) {
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        String refreshToken = resolveRefreshToken(request, httpRequest, cookieMode);
        abuseGuard.assertRefreshAllowed(refreshToken);
        AuthTokens tokens = authenticationService.refresh(refreshToken);
        return toResponse(tokens, cookieMode);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) RefreshTokenRequest request, HttpServletRequest httpRequest) {
        boolean cookieMode = refreshCookies.cookieModeRequested(httpRequest);
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
            // Idempotent: logging out with no (or an already-dead) cookie still succeeds and clears it.
            refreshCookies.read(httpRequest).ifPresent(authenticationService::logout);
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                    .build();
        }
        if (request == null) {
            throw new InvalidRefreshTokenException();
        }
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private String resolveRefreshToken(RefreshTokenRequest body, HttpServletRequest httpRequest, boolean cookieMode) {
        if (cookieMode) {
            refreshCookies.assertAllowedOrigin(httpRequest);
            return refreshCookies.read(httpRequest).orElseThrow(InvalidRefreshTokenException::new);
        }
        if (body == null) {
            throw new InvalidRefreshTokenException();
        }
        return body.refreshToken();
    }

    private ResponseEntity<AuthTokensResponse> toResponse(AuthTokens tokens, boolean cookieMode) {
        if (!cookieMode) {
            return ResponseEntity.ok(new AuthTokensResponse(tokens.accessToken(), tokens.refreshToken()));
        }
        // The refresh token travels ONLY in the HttpOnly cookie; page scripts never see it.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(tokens.refreshToken()).toString())
                .body(new AuthTokensResponse(tokens.accessToken(), null));
    }
}
