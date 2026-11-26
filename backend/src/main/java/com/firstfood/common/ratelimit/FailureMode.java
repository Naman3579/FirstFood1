package com.firstfood.common.ratelimit;

/** What to do when the shared counter store is unreachable. */
public enum FailureMode {
    /**
     * Refuse with 503. For anything an attacker profits from (SMS sends, OTP guesses, token endpoints): a
     * limiter that silently stops limiting during an outage is an invitation to cause or exploit one.
     */
    FAIL_CLOSED,
    /**
     * Keep serving, but enforce the same budget with this instance's own in-memory counters, so the API is
     * degraded-but-limited rather than unlimited.
     */
    LOCAL_FALLBACK
}
