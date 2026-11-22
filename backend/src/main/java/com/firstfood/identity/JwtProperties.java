package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param refreshReuseGraceSeconds F-11: a rotated refresh token presented again within this many seconds of its
 *     rotation is just refused (two tabs refreshing at the same instant); after it, the replay is treated as theft and
 *     the whole session family is revoked. 0 = every replay revokes.
 * @param issuer F-17: the {@code iss} claim every access token carries and every parse requires.
 * @param audience F-17: the {@code aud} claim every access token carries and every parse requires. A token minted
 *     for another service that happens to share the secret (or another environment of this one that sets a different
 *     audience) is refused.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        int accessTokenTtlMinutes,
        int refreshTokenTtlDays,
        @DefaultValue("10") int refreshReuseGraceSeconds,
        @DefaultValue("firstfood") String issuer,
        @DefaultValue("firstfood-api") String audience) {
}
