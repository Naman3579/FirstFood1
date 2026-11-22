package com.firstfood.identity.security;

import java.util.UUID;

/**
 * The Spring Security {@code Authentication} principal for a
 * JWT-authenticated request. Controllers can resolve it directly via
 * {@code @AuthenticationPrincipal AuthenticatedAccount principal}.
 *
 * @param sessionId the refresh-session family the access token was issued for (F-11); lets an action say "every
 *     session except this one".
 */
public record AuthenticatedAccount(UUID accountId, UUID sessionId) {
}
