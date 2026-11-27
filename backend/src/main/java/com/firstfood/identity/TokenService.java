package com.firstfood.identity;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived JWT access tokens (architecture.md
 * #12 "Keep access tokens short-lived"). The refresh token is a
 * separate, opaque, revocable concept handled by {@link RefreshSessionService}
 * - it is deliberately not a JWT, so it can be revoked server-side
 * (rules.md/architecture.md emphasize backend-authoritative auth).
 */
@Service
public class TokenService {

    static final String SESSION_CLAIM = "sid";

    /** What a valid access token says: who, and which session. */
    public record AccessClaims(UUID accountId, UUID sessionId) {
    }

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final String issuer;
    private final String audience;

    /** Refuses to construct (so the application does not start) with an unsafe secret; see {@link JwtSecretPolicy}. */
    public TokenService(JwtProperties jwtProperties, Environment environment) {
        JwtSecretPolicy.require(jwtProperties.secret(), Set.copyOf(java.util.Arrays.asList(environment.getActiveProfiles())));
        this.issuer = jwtProperties.issuer();
        this.audience = jwtProperties.audience();
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(jwtProperties.accessTokenTtlMinutes());
    }

    /**
     * @param sessionId the refresh-session family this token belongs to (claim {@code sid}). The security filter asks
     *     whether that session is still alive, so logout, revocation and theft handling end the access token at once
     *     instead of leaving it valid for up to its 15 minutes (F-11).
     */
    public String issueAccessToken(UUID accountId, UUID sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(accountId.toString())
                .claim(SESSION_CLAIM, sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the token's account and session if it is structurally valid, unexpired, correctly signed and names a
     * session - empty otherwise. A token without {@code sid} (issued before F-11) or without the expected {@code iss} /
     * {@code aud} (issued before F-17, or minted for something else) is simply unauthenticated; the client's next refresh
     * gets it a new one. Never throws for a bad/expired token; callers (the security
     * filter) should treat "empty" as "unauthenticated", not as a server error.
     */
    public Optional<AccessClaims> parse(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String sid = claims.get(SESSION_CLAIM, String.class);
            if (sid == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessClaims(UUID.fromString(claims.getSubject()), UUID.fromString(sid)));
        } catch (JwtException | IllegalArgumentException e) {
            // Never log the token itself here (rules.md Rule 17.4).
            return Optional.empty();
        }
    }
}
