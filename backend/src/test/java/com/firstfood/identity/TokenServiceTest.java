package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** F-17: issuer and audience are issued and required; weak secrets stop construction. Plain JUnit, no Spring context. */
class TokenServiceTest {

    private static final String SECRET = "kR7vQ2mX9pLw4ZtB8nYcF3sHjD6aGeU1oVxEi5MqNb0=";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final UUID account = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final TokenService service = service(SECRET, "firstfood", "firstfood-api");

    @Test
    void anIssuedTokenCarriesIssuerAndAudienceAndParsesBack() {
        String token = service.issueAccessToken(account, session);

        var claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getIssuer()).isEqualTo("firstfood");
        assertThat(claims.getAudience()).containsExactly("firstfood-api");

        var parsed = service.parse(token).orElseThrow();
        assertThat(parsed.accountId()).isEqualTo(account);
        assertThat(parsed.sessionId()).isEqualTo(session);
    }

    @Test
    void aCorrectlySignedTokenWithoutIssuerOrAudienceIsRefused() {
        assertThat(service.parse(builder().compact())).isEmpty();                                   // neither
        assertThat(service.parse(builder().issuer("firstfood").compact())).isEmpty();               // no aud
        assertThat(service.parse(builder().audience().add("firstfood-api").and().compact())).isEmpty(); // no iss
    }

    @Test
    void aWrongIssuerOrAudienceIsRefused() {
        assertThat(service.parse(builder().issuer("someone-else").audience().add("firstfood-api").and().compact())).isEmpty();
        assertThat(service.parse(builder().issuer("firstfood").audience().add("another-service").and().compact())).isEmpty();
    }

    @Test
    void aTokenIssuedUnderAnotherAudienceSettingIsRefused() {
        // e.g. staging and production sharing a secret by mistake but configured with different audiences
        String staging = service(SECRET, "firstfood", "firstfood-staging").issueAccessToken(account, session);
        assertThat(service.parse(staging)).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        String other = service("Zx9wQ4mR2pLk7VtB1nYcF8sHjD3aGeU6oXvEi0MqNb5=", "firstfood", "firstfood-api")
                .issueAccessToken(account, session);
        assertThat(service.parse(other)).isEmpty();
    }

    @Test
    void anUnsignedTokenIsRefused() {
        String none = Jwts.builder().issuer("firstfood").audience().add("firstfood-api").and()
                .subject(account.toString()).claim("sid", session.toString())
                .expiration(Date.from(Instant.now().plusSeconds(600))).compact();
        assertThat(service.parse(none)).isEmpty();
    }

    @Test
    void aWeakSecretStopsConstruction() {
        assertThatThrownBy(() -> service("too-short", "firstfood", "firstfood-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageNotContaining("too-short");
    }

    @Test
    void theRetiredLocalSecretStopsConstructionEvenUnderTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatThrownBy(() -> new TokenService(
                        props(JwtSecretPolicy.RETIRED_LOCAL_SECRET, "firstfood", "firstfood-api"), local))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ helpers

    private io.jsonwebtoken.JwtBuilder builder() {
        return Jwts.builder()
                .subject(account.toString())
                .claim("sid", session.toString())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(KEY);
    }

    private static JwtProperties props(String secret, String issuer, String audience) {
        return new JwtProperties(secret, 15, 30, 10, issuer, audience);
    }

    private static TokenService service(String secret, String issuer, String audience) {
        return new TokenService(props(secret, issuer, audience), new MockEnvironment());
    }
}
