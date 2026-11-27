package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.google.*} - "Continue with Google" sign-in / sign-up.
 *
 * @param clientId the OAuth 2.0 Web client ID from Google Cloud Console (public, not a secret). Empty = Google
 *                 sign-in is switched off and its endpoints answer 503 GOOGLE_SIGNIN_UNAVAILABLE. Only ID tokens issued
 *                 FOR this client are accepted (the {@code aud} check), so a token minted for some other site's
 *                 Google client can never sign anyone in here.
 * @param jwksUrl  where Google publishes the keys its ID tokens are signed with.
 */
@ConfigurationProperties(prefix = "app.google")
public record GoogleProperties(String clientId, String jwksUrl) {

    public GoogleProperties {
        clientId = clientId == null ? "" : clientId.trim();
        jwksUrl = (jwksUrl == null || jwksUrl.isBlank()) ? "https://www.googleapis.com/oauth2/v3/certs" : jwksUrl.trim();
    }

    public boolean enabled() {
        return !clientId.isEmpty();
    }
}
