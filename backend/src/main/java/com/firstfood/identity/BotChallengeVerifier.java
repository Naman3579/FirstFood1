package com.firstfood.identity;

/**
 * Proves a request came from a browser session that passed a bot challenge. Business code depends on this interface, not
 * on the vendor (architecture.md #24), so Turnstile can be swapped for another provider.
 */
public interface BotChallengeVerifier {

    /**
     * @param token    the one-time token the browser got from the challenge widget (may be null/blank)
     * @param clientIp the address the request came from (as resolved behind the trusted proxies), or null
     * @throws com.firstfood.identity.error.BotChallengeFailedException      400: missing, invalid, expired or reused token
     * @throws com.firstfood.identity.error.BotChallengeUnavailableException 503: the verification service could not be reached
     */
    void verify(String token, String clientIp);
}
