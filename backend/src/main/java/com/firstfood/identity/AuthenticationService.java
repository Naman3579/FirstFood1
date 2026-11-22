package com.firstfood.identity;

import java.util.UUID;

/**
 * The identity module's public application-service surface (see
 * com.firstfood.identity package-info: other modules should depend on
 * this interface, never on the entities/repositories directly).
 *
 * Covers phases.md Phase 2's full exit-criteria flow: request OTP ->
 * verify OTP -> authenticate -> access protected API -> refresh session
 * -> logout, plus the OTP-gated sensitive phone-number-change flow
 * required by rules.md Rule 3.3.
 */
public interface AuthenticationService {

    void requestLoginOtp(String phone);

    /**
     * The original portal-less login: verifies the login OTP and returns a fresh token pair for an EXISTING account.
     * The product uses {@link #signIn} / {@link #signUp} instead. For a number nobody has registered, behaviour depends
     * on {@code app.auth.legacy-login.register-new-accounts} (F-12): true (local/test) creates the account on both
     * portals; false (default, production) refuses with ACCOUNT_NOT_REGISTERED (404) after checking the code and
     * WITHOUT spending it.
     */
    AuthTokens verifyLoginOtp(String phone, String code);

    AuthTokens refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    /**
     * Starts the sensitive phone-number-change flow (rules.md Rule 3.3):
     * an OTP is sent to the NEW number to prove the caller controls it.
     * Requires an authenticated account - callers must already hold a
     * valid access token for the account being changed.
     */
    void requestPhoneChangeOtp(UUID accountId, String newPhone);

    /**
     * Applies the new number once its OTP checks out, and ends every OTHER session of the account (F-11);
     * {@code currentSessionId} is the caller's own session (the {@code sid} of its access token) and is kept.
     */
    void verifyPhoneChangeOtp(UUID accountId, UUID currentSessionId, String newPhone, String code);

    /**
     * Portal sign-in: the OTP proves the number, the portal decides the door. A number with no registration on the
     * portal is refused with ACCOUNT_NOT_REGISTERED (404) BEFORE the code is spent, so the caller can sign up with the
     * same code. A wrong or expired code is still rejected first (401), so this never reveals whether a number is
     * registered to someone who does not hold its code.
     */
    AuthTokens signIn(Portal portal, String phone, String code);

    /**
     * Portal sign-up: creates the account if the number is new, or adds the portal to an existing account. Refused
     * with ACCOUNT_ALREADY_REGISTERED (409) BEFORE the code is spent when the number is already registered there.
     * {@code fullName} is stored only if the account has none yet.
     */
    AuthTokens signUp(Portal portal, String phone, String code, String fullName);

    /**
     * Adds a phone number WITHOUT a code, for an account that has none or only an unproven one (launch mode while SMS is
     * not set up). Refused with PHONE_VERIFICATION_REQUIRED (409) when {@code app.phone.verification-required} is on, or
     * when the account already has a proven number (changing that needs a code), and PHONE_ALREADY_IN_USE (409) when a
     * different account has proven this number. The number stays unverified until the phone-change flow proves it.
     */
    void setUnverifiedPhone(UUID accountId, String phone);

    /** Step 1 of adding/changing an email: a code is emailed to the (lower-cased) address; nothing is stored. */
    void requestEmailChangeOtp(UUID accountId, String email);

    /** Step 2: the address plus the emailed code. Stores the email as verified. */
    void verifyEmailChangeOtp(UUID accountId, String email, String code);

    /**
     * Email sign-in/sign-up, step 1: a login code is emailed to the (lower-cased) address. Sent whether or not an
     * account exists, so this never reveals who is registered.
     */
    void requestEmailLoginOtp(String email);

    /** Like {@link #signIn} but the code proves an email address; only a VERIFIED email identifies an account. */
    AuthTokens signInWithEmail(Portal portal, String email, String code);

    /**
     * Like {@link #signUp} but by email: creates an email-only account, or adds the portal to the account whose
     * verified email this is. Refused with ACCOUNT_ALREADY_REGISTERED (409) BEFORE the code is spent.
     */
    AuthTokens signUpWithEmail(Portal portal, String email, String code, String fullName);
}
