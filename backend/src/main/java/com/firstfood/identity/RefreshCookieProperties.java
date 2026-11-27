package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param name   cookie name
 * @param secure send only over HTTPS. MUST be true in production; false only for plain-http local development
 *               (Safari does not accept Secure cookies on http://localhost).
 */
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public record RefreshCookieProperties(String name, boolean secure) {

    public RefreshCookieProperties {
        name = (name == null || name.isBlank()) ? "ff_refresh" : name;
    }
}
