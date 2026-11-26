package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * F-17 end to end: a token signed with the REAL secret and naming a LIVE session is still refused unless it carries
 * the expected issuer and audience. Phones use +91987686xxxx; the database is a shared singleton container, so do not
 * reuse them.
 */
class TokenClaimsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    TokenService tokenService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @Test
    void aForgedTokenIsAcceptedOnlyWithIssuerAndAudience() {
        AuthTokensResponse tokens = login("+919876860001");
        var claims = tokenService.parse(tokens.accessToken()).orElseThrow();

        String noClaims = forge(claims, null, null);
        String wrongIssuer = forge(claims, "someone-else", "firstfood-api");
        String wrongAudience = forge(claims, "firstfood", "another-service");
        String complete = forge(claims, "firstfood", "firstfood-api"); // control: proves the forgery itself is sound

        me(noClaims).expectStatus().isUnauthorized();
        me(wrongIssuer).expectStatus().isUnauthorized();
        me(wrongAudience).expectStatus().isUnauthorized();
        me(complete).expectStatus().isOk();
        me(tokens.accessToken()).expectStatus().isOk();
    }

    @Test
    void aRefreshStillHandsOutAUsableTokenAfterAnOldOneIsRefused() {
        AuthTokensResponse tokens = login("+919876860002");
        var claims = tokenService.parse(tokens.accessToken()).orElseThrow();
        me(forge(claims, null, null)).expectStatus().isUnauthorized();

        AuthTokensResponse refreshed = restClient.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new com.firstfood.identity.dto.RefreshTokenRequest(tokens.refreshToken()))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        me(refreshed.accessToken()).expectStatus().isOk();
    }

    // ------------------------------------------------------------ helpers

    private String forge(TokenService.AccessClaims claims, String issuer, String audience) {
        var b = Jwts.builder()
                .subject(claims.accountId().toString())
                .claim("sid", claims.sessionId().toString())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)));
        if (issuer != null) {
            b.issuer(issuer);
        }
        if (audience != null) {
            b.audience().add(audience);
        }
        return b.compact();
    }

    private AuthTokensResponse login(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        return restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec me(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange();
    }
}
