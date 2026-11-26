package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.bot-challenge.*} - F-05. A bot challenge (Cloudflare Turnstile) in front of the PUBLIC OTP request, the one
 * endpoint an anonymous script can turn into SMS spend.
 *
 * @param enabled   when false no challenge is required (local/dev, or a deployment that has not set Turnstile up yet).
 * @param secretKey Turnstile secret key. Required when enabled: startup fails without it rather than silently running
 *                  unprotected while the operator believes the challenge is on.
 * @param verifyUrl Turnstile siteverify endpoint.
 */
@ConfigurationProperties(prefix = "app.bot-challenge")
public record BotChallengeProperties(boolean enabled, String secretKey, String verifyUrl) {

    public BotChallengeProperties {
        secretKey = secretKey == null ? "" : secretKey.trim();
        verifyUrl = (verifyUrl == null || verifyUrl.isBlank())
                ? "https://challenges.cloudflare.com/turnstile/v0/siteverify"
                : verifyUrl.trim();
        if (enabled && secretKey.isEmpty()) {
            throw new IllegalStateException(
                    "app.bot-challenge.enabled=true needs TURNSTILE_SECRET_KEY (or set BOT_CHALLENGE_ENABLED=false)");
        }
    }
}
