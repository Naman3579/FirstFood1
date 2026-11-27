package com.firstfood.identity.web;

import com.firstfood.common.ratelimit.RequestClientIp;
import com.firstfood.identity.AuthAbuseGuard;
import com.firstfood.identity.AuthenticationService;
import com.firstfood.identity.UserAccount;
import com.firstfood.identity.UserAccountRepository;
import com.firstfood.identity.dto.AddPhoneRequest;
import com.firstfood.identity.dto.EmailChangeRequest;
import com.firstfood.identity.dto.EmailChangeVerifyRequest;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PhoneChangeVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.UpdateEmailRequest;
import com.firstfood.identity.error.AuthenticatedAccountNotFoundException;
import com.firstfood.identity.security.AuthenticatedAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected profile endpoints - everything here requires a valid Bearer
 * access token (SecurityConfig's anyRequest().authenticated() default).
 *
 * Email is a non-sensitive field: PATCH /me/email just needs a valid
 * token. Phone number is the authentication-critical identity (rules.md
 * Rule 3.3), so changing it goes through its own OTP-gated flow instead
 * of a plain PATCH.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserAccountRepository userAccountRepository;
    private final AuthenticationService authenticationService;
    private final AuthAbuseGuard abuseGuard;
    private final RequestClientIp clientIp;

    public MeController(
            UserAccountRepository userAccountRepository,
            AuthenticationService authenticationService,
            AuthAbuseGuard abuseGuard,
            RequestClientIp clientIp) {
        this.userAccountRepository = userAccountRepository;
        this.authenticationService = authenticationService;
        this.abuseGuard = abuseGuard;
        this.clientIp = clientIp;
    }

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedAccount principal) {
        UserAccount account = requireAccount(principal);
        return toResponse(account);
    }

    @PatchMapping("/email")
    public ProfileResponse updateEmail(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody UpdateEmailRequest request) {
        UserAccount account = requireAccount(principal);
        account.setEmail(request.email());
        userAccountRepository.save(account);
        return toResponse(account);
    }

    /** Step 1 of the verified email flow: a code is emailed to the address; nothing is stored yet. */
    @PostMapping("/email/otp/request")
    public ResponseEntity<Void> requestEmailChange(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody EmailChangeRequest request) {
        authenticationService.requestEmailChangeOtp(principal.accountId(), request.email());
        return ResponseEntity.accepted().build();
    }

    /** Step 2: the same address plus the emailed code; the email is then stored as verified. */
    @PostMapping("/email/otp/verify")
    public ProfileResponse verifyEmailChange(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody EmailChangeVerifyRequest request,
            HttpServletRequest httpRequest) {
        String ip = clientIp.of(httpRequest);
        String subject = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        abuseGuard.guardVerifyRun(subject, ip,
                () -> authenticationService.verifyEmailChangeOtp(principal.accountId(), request.email(), request.code()));
        return toResponse(requireAccount(principal));
    }

    /** Adds a number with no code (launch mode, see {@link com.firstfood.identity.PhoneProperties}); it stays unverified. */
    @PutMapping("/phone")
    public ProfileResponse addPhone(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody AddPhoneRequest request) {
        authenticationService.setUnverifiedPhone(principal.accountId(), request.phone());
        return toResponse(requireAccount(principal));
    }

    @PostMapping("/phone/otp/request")
    public ResponseEntity<Void> requestPhoneChange(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody PhoneChangeRequest request) {
        authenticationService.requestPhoneChangeOtp(principal.accountId(), request.newPhone());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/phone/otp/verify")
    public ProfileResponse verifyPhoneChange(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody PhoneChangeVerifyRequest request,
            HttpServletRequest httpRequest) {
        String ip = clientIp.of(httpRequest);
        abuseGuard.guardVerifyRun(request.newPhone(), ip,
                () -> authenticationService.verifyPhoneChangeOtp(
                        principal.accountId(), principal.sessionId(), request.newPhone(), request.code()));
        return toResponse(requireAccount(principal));
    }

    private UserAccount requireAccount(AuthenticatedAccount principal) {
        return userAccountRepository.findById(principal.accountId())
                .orElseThrow(AuthenticatedAccountNotFoundException::new);
    }

    private ProfileResponse toResponse(UserAccount account) {
        return new ProfileResponse(
                account.getId(),
                account.getPhone(),
                account.isPhoneVerified(),
                account.getEmail(),
                account.isEmailVerified(),
                account.getFullName(),
                account.getStatus().name(),
                account.portals().stream().map(Enum::name).toList(),
                account.getCreatedAt());
    }
}
