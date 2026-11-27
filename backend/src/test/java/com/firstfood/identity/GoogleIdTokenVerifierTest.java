package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.identity.error.GoogleSignInUnavailableException;
import com.firstfood.identity.error.InvalidGoogleTokenException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Google ID token checks: signature, audience, issuer, expiry, verified email, key rotation. Plain JUnit, no Google call. */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "123-abc.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final KeyPair google = newKeyPair();
    private final KeyPair attacker = newKeyPair();
    private final AtomicInteger fetches = new AtomicInteger();

    private GoogleIdTokenVerifierImpl verifier(String clientId, Map<String, PublicKey> keys) {
        return new GoogleIdTokenVerifierImpl(new GoogleProperties(clientId, null), () -> {
            fetches.incrementAndGet();
            return keys;
        }, CLOCK);
    }

    private String token(KeyPair signer, String kid, String aud, String iss, Instant exp, Object emailVerified) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(iss).audience().add(aud).and().subject("1234567890")
                .claim("email", "Meera.Iyer@Example.com").claim("email_verified", emailVerified).claim("name", " Meera Iyer ")
                .issuedAt(Date.from(NOW.minusSeconds(60))).expiration(Date.from(exp))
                .signWith(signer.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private String good() {
        return token(google, "k1", CLIENT_ID, "https://accounts.google.com", NOW.plusSeconds(600), true);
    }

    @Test
    void aValidTokenYieldsTheLowerCasedVerifiedIdentity() {
        GoogleIdentity id = verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(good());
        assertThat(id.email()).isEqualTo("meera.iyer@example.com");
        assertThat(id.name()).isEqualTo("Meera Iyer");
        assertThat(id.subject()).isEqualTo("1234567890");
    }

    @Test
    void theBareIssuerFormIsAlsoAccepted() {
        String t = token(google, "k1", CLIENT_ID, "accounts.google.com", NOW.plusSeconds(600), true);
        assertThat(verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(t).email()).isEqualTo("meera.iyer@example.com");
    }

    @Test
    void aTokenSignedWithSomeoneElsesKeyIsRefused() {
        String forged = token(attacker, "k1", CLIENT_ID, "https://accounts.google.com", NOW.plusSeconds(600), true);
        assertThatThrownBy(() -> verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(forged))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void aTokenIssuedForAnotherClientIsRefused() {
        String other = token(google, "k1", "someone-else.apps.googleusercontent.com", "https://accounts.google.com",
                NOW.plusSeconds(600), true);
        assertThatThrownBy(() -> verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(other))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        String expired = token(google, "k1", CLIENT_ID, "https://accounts.google.com", NOW.minusSeconds(600), true);
        assertThatThrownBy(() -> verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(expired))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void aWrongIssuerIsRefused() {
        String t = token(google, "k1", CLIENT_ID, "https://evil.example", NOW.plusSeconds(600), true);
        assertThatThrownBy(() -> verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(t))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void anEmailGoogleHasNotVerifiedIsRefused() {
        String t = token(google, "k1", CLIENT_ID, "https://accounts.google.com", NOW.plusSeconds(600), false);
        assertThatThrownBy(() -> verifier(CLIENT_ID, Map.of("k1", google.getPublic())).verify(t))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void anUnsignedTokenAndGarbageAreRefused() {
        String unsigned = Jwts.builder().subject("x").claim("email", "a@b.co").claim("email_verified", true)
                .audience().add(CLIENT_ID).and().issuer("https://accounts.google.com").compact();
        GoogleIdTokenVerifierImpl v = verifier(CLIENT_ID, Map.of("k1", google.getPublic()));
        assertThatThrownBy(() -> v.verify(unsigned)).isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> v.verify("not.a.jwt")).isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> v.verify(" ")).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void withoutAClientIdTheFeatureIsOffAndGoogleIsNeverCalled() {
        GoogleIdTokenVerifierImpl v = verifier("", Map.of("k1", google.getPublic()));
        assertThatThrownBy(() -> v.verify(good())).isInstanceOf(GoogleSignInUnavailableException.class);
        assertThat(fetches.get()).isZero();
    }

    @Test
    void whenGoogleCannotBeReachedAndNothingIsCachedTheSignInFailsClosed() {
        GoogleIdTokenVerifierImpl v = new GoogleIdTokenVerifierImpl(new GoogleProperties(CLIENT_ID, null),
                () -> { throw new IllegalStateException("down"); }, CLOCK);
        assertThatThrownBy(() -> v.verify(good())).isInstanceOf(GoogleSignInUnavailableException.class);
    }

    @Test
    void keysAreCachedAcrossCalls() {
        GoogleIdTokenVerifierImpl v = verifier(CLIENT_ID, Map.of("k1", google.getPublic()));
        v.verify(good());
        v.verify(good());
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void anUnknownKeyIdIsRefusedWithoutRefetchingMoreThanOncePerMinute() {
        GoogleIdTokenVerifierImpl v = verifier(CLIENT_ID, Map.of("k1", google.getPublic()));
        v.verify(good());
        String rotated = token(google, "unknown", CLIENT_ID, "https://accounts.google.com", NOW.plusSeconds(600), true);
        assertThatThrownBy(() -> v.verify(rotated)).isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> v.verify(rotated)).isInstanceOf(InvalidGoogleTokenException.class);
        assertThat(fetches.get()).isEqualTo(1);
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
