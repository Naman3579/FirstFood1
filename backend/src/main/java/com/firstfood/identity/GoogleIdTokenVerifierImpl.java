package com.firstfood.identity;

import com.firstfood.identity.error.GoogleSignInUnavailableException;
import com.firstfood.identity.error.InvalidGoogleTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies a Google ID token locally, the way Google documents it
 * (https://developers.google.com/identity/gsi/web/guides/verify-google-id-token): RS256 signature against Google's
 * published keys, {@code iss} is Google, {@code aud} is OUR client id, not expired, and {@code email_verified} is true.
 *
 * <p>Google's keys are cached and refreshed when they expire or when a token names a key we have not seen (Google
 * rotates them). Refreshes are spaced at least a minute apart so garbage tokens cannot make us hammer Google. Fails
 * CLOSED: if the keys cannot be fetched the sign-in is refused (503), never waved through.
 *
 * <p>Nothing here logs the token.
 */
@Component
public class GoogleIdTokenVerifierImpl implements GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierImpl.class);
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");
    private static final Duration KEY_TTL = Duration.ofHours(1);
    private static final Duration MIN_REFETCH_GAP = Duration.ofMinutes(1);
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final GoogleProperties properties;
    private final Supplier<Map<String, PublicKey>> keyFetcher;
    private final java.time.Clock clock;

    private final Object keyLock = new Object();
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile long keysFetchedAtMillis = 0;

    @Autowired
    public GoogleIdTokenVerifierImpl(GoogleProperties properties) {
        this(properties, jwksFetcher(properties), java.time.Clock.systemUTC());
    }

    /** For tests: supply the keys directly instead of calling Google. */
    GoogleIdTokenVerifierImpl(GoogleProperties properties, Supplier<Map<String, PublicKey>> keyFetcher, java.time.Clock clock) {
        this.properties = properties;
        this.keyFetcher = keyFetcher;
        this.clock = clock;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (!properties.enabled()) {
            throw new GoogleSignInUnavailableException();
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096) {
            throw new InvalidGoogleTokenException();
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(ProtectedHeader header) {
                            return keyFor(header.getKeyId());
                        }
                    })
                    .requireAudience(properties.clientId())
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (GoogleSignInUnavailableException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            // Signature, expiry, audience, malformed, unknown key id: all the same answer to the caller.
            log.debug("Google ID token rejected: {}", e.getClass().getSimpleName());
            throw new InvalidGoogleTokenException();
        }

        if (claims.getIssuer() == null || !ISSUERS.contains(claims.getIssuer())) {
            throw new InvalidGoogleTokenException();
        }
        String subject = claims.getSubject();
        String email = claims.get("email", String.class);
        // Google serialises email_verified as a boolean in ID tokens (a "true" string in the tokeninfo endpoint), so
        // accept both and require it to be true.
        Object verified = claims.get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
        if (subject == null || subject.isBlank() || email == null || email.isBlank() || !emailVerified) {
            throw new InvalidGoogleTokenException();
        }
        String name = claims.get("name", String.class);
        return new GoogleIdentity(subject, email.trim().toLowerCase(Locale.ROOT), name == null ? null : name.trim());
    }

    private Key keyFor(String keyId) {
        if (keyId == null) {
            return null; // jjwt turns a null key into a JwtException -> 401
        }
        long now = clock.millis();
        Map<String, PublicKey> current = keys;
        boolean stale = now - keysFetchedAtMillis > KEY_TTL.toMillis();
        PublicKey key = current.get(keyId);
        if (key != null && !stale) {
            return key;
        }
        // Unknown key id or expired cache: refresh, but not more than once a minute.
        synchronized (keyLock) {
            current = keys;
            key = current.get(keyId);
            stale = clock.millis() - keysFetchedAtMillis > KEY_TTL.toMillis();
            if (key != null && !stale) {
                return key;
            }
            if (clock.millis() - keysFetchedAtMillis >= MIN_REFETCH_GAP.toMillis() || current.isEmpty()) {
                try {
                    keys = keyFetcher.get();
                    keysFetchedAtMillis = clock.millis();
                } catch (RuntimeException e) {
                    log.warn("Could not fetch Google signing keys: {}", e.getClass().getSimpleName());
                    if (current.isEmpty()) {
                        throw new GoogleSignInUnavailableException();
                    }
                    // Keep serving from the old keys rather than locking everyone out of a blip.
                }
            }
            return keys.get(keyId);
        }
    }

    private static Supplier<Map<String, PublicKey>> jwksFetcher(GoogleProperties properties) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return () -> {
            try {
                Map<String, Object> body = client.get().uri(properties.jwksUrl()).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() { });
                return parseJwks(body);
            } catch (RestClientException e) {
                throw new IllegalStateException("Google JWKS unavailable", e);
            }
        };
    }

    /** Reads a JWK Set ({@code {"keys":[{"kid","kty":"RSA","n","e"}, ...]}}) into RSA public keys by key id. */
    @SuppressWarnings("unchecked")
    static Map<String, PublicKey> parseJwks(Map<String, Object> body) {
        Map<String, PublicKey> result = new HashMap<>();
        Object list = body == null ? null : body.get("keys");
        if (!(list instanceof List<?> jwks)) {
            throw new IllegalStateException("Malformed JWKS");
        }
        for (Object entry : jwks) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> jwk = (Map<String, Object>) raw;
            if (!"RSA".equals(jwk.get("kty")) || jwk.get("kid") == null || jwk.get("n") == null || jwk.get("e") == null) {
                continue;
            }
            try {
                Base64.Decoder decoder = Base64.getUrlDecoder();
                BigInteger modulus = new BigInteger(1, decoder.decode(jwk.get("n").toString()));
                BigInteger exponent = new BigInteger(1, decoder.decode(jwk.get("e").toString()));
                result.put(jwk.get("kid").toString(),
                        KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                // Skip a key we cannot read; the others may still be fine.
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("JWKS held no usable keys");
        }
        return Map.copyOf(result);
    }
}
