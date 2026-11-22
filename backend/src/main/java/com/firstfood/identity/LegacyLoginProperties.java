package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.auth.legacy-login.*} - F-12. The original portal-less {@code POST /api/v1/auth/otp/verify} predates the two
 * portals. The product no longer calls it (the frontend uses {@code /auth/{portal}/signin|signup}), but it is still
 * mapped.
 *
 * @param registerNewAccounts when true, a code that proves a number nobody has registered yet creates the account on
 *                            BOTH portals (the original behaviour; local development and the test suite rely on it as
 *                            a one-call login). When false (the default, and the production setting) the endpoint only
 *                            signs in numbers that already have an account; an unknown number gets 404
 *                            ACCOUNT_NOT_REGISTERED and its code is NOT spent, so the caller can still sign up on the
 *                            portal they actually want. A portal is only a door (docs/OPERATIONS.md, F-12), but there
 *                            is no reason to hand out both doors implicitly.
 */
@ConfigurationProperties(prefix = "app.auth.legacy-login")
public record LegacyLoginProperties(boolean registerNewAccounts) {
}
