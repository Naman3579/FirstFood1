package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PhoneChangeVerifyRequest;
import com.firstfood.identity.dto.RefreshTokenRequest;
import com.firstfood.identity.error.InvalidRefreshTokenException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-11: refresh-token reuse is treated as theft, logout and phone change end access tokens at once. The grace
 * window is 0 here so a replay is a theft signal immediately (SessionFamilyGraceIntegrationTest covers the window).
 * Phones use +91987674xxxx; the database is a shared singleton container, so do not reuse them.
 */
@TestPropertySource(properties = "app.jwt.refresh-reuse-grace-seconds=0")
class SessionFamilyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    AuthenticationService authenticationService;

    @Autowired
    RefreshSessionService refreshSessions;

    @Autowired
    TokenService tokenService;

    @Test
    void aRefreshKeepsTheSameSessionSoTheEarlierAccessTokenStillWorks() {
        AuthTokensResponse first = login("+919876740001");
        AuthTokensResponse second = refresh(first.refreshToken()).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        assertThat(sessionOf(second.accessToken())).isEqualTo(sessionOf(first.accessToken()));
        me(first.accessToken()).expectStatus().isOk();
        me(second.accessToken()).expectStatus().isOk();
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeSessionIncludingTheThiefsAndTheUsersNewToken() {
        AuthTokensResponse stolen = login("+919876740002");
        // The thief rotates first and now holds the live token.
        AuthTokensResponse thief = refresh(stolen.refreshToken()).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        me(thief.accessToken()).expectStatus().isOk();

        // The real user presents the token they still hold: it fails, and the session dies with it.
        refresh(stolen.refreshToken()).expectStatus().isUnauthorized();

        refresh(thief.refreshToken()).expectStatus().isUnauthorized();
        me(thief.accessToken()).expectStatus().isUnauthorized();
        me(stolen.accessToken()).expectStatus().isUnauthorized();
    }

    @Test
    void aReplayDoesNotTouchTheSameAccountsOtherSessions() {
        String phone = "+919876740003";
        AuthTokensResponse laptop = login(phone);
        AuthTokensResponse phoneApp = login(phone);

        AuthTokensResponse rotated = refresh(laptop.refreshToken()).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        refresh(laptop.refreshToken()).expectStatus().isUnauthorized(); // replay: kills the laptop session only

        me(rotated.accessToken()).expectStatus().isUnauthorized();
        me(phoneApp.accessToken()).expectStatus().isOk();
        refresh(phoneApp.refreshToken()).expectStatus().isOk();
    }

    @Test
    void replayingALoggedOutTokenIsRefusedWithoutAnyExtraEffect() {
        AuthTokensResponse tokens = login("+919876740004");
        logout(tokens.refreshToken());

        refresh(tokens.refreshToken()).expectStatus().isUnauthorized();
        refresh(tokens.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void logoutEndsTheAccessTokenAtOnce() {
        AuthTokensResponse tokens = login("+919876740005");
        me(tokens.accessToken()).expectStatus().isOk();

        logout(tokens.refreshToken());

        me(tokens.accessToken()).expectStatus().isUnauthorized();
    }

    @Test
    void logoutIsPerSessionAndLeavesTheAccountsOtherSessionsAlone() {
        String phone = "+919876740006";
        AuthTokensResponse laptop = login(phone);
        AuthTokensResponse phoneApp = login(phone);

        logout(laptop.refreshToken());

        me(laptop.accessToken()).expectStatus().isUnauthorized();
        me(phoneApp.accessToken()).expectStatus().isOk();
    }

    @Test
    void anAccessTokenWithoutASessionClaimIsNotAccepted() {
        // What every token issued before F-11 looks like: validly signed, but no sid. It must not authenticate; the
        // client's next refresh (which still works) hands out a proper one.
        AuthTokensResponse tokens = login("+919876740007");
        UUID account = tokenService.parse(tokens.accessToken()).orElseThrow().accountId();
        String legacy = io.jsonwebtoken.Jwts.builder()
                .subject(account.toString())
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-only-secret-do-not-use-in-production".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        me(legacy).expectStatus().isUnauthorized();
        refresh(tokens.refreshToken()).expectStatus().isOk();
    }

    @Test
    void changingThePhoneNumberEndsEveryOtherSessionButKeepsTheCallers() {
        String oldPhone = "+919876740008";
        String newPhone = "+919876740009";
        AuthTokensResponse thisDevice = login(oldPhone);
        AuthTokensResponse otherDevice = login(oldPhone);

        restClient.post().uri("/api/v1/me/phone/otp/request")
                .header("Authorization", "Bearer " + thisDevice.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeRequest(newPhone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        restClient.post().uri("/api/v1/me/phone/otp/verify")
                .header("Authorization", "Bearer " + thisDevice.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeVerifyRequest(newPhone, otpSender.lastOtpFor(newPhone)))
                .exchange().expectStatus().isOk();

        // The other device is out at once: access token and refresh token.
        me(otherDevice.accessToken()).expectStatus().isUnauthorized();
        refresh(otherDevice.refreshToken()).expectStatus().isUnauthorized();
        // The device that made the change is not bounced.
        me(thisDevice.accessToken()).expectStatus().isOk();
        refresh(thisDevice.refreshToken()).expectStatus().isOk();
    }

    @Test
    void parallelRefreshesOfOneTokenNeverLeaveALiveSessionBehind() throws Exception {
        AuthTokensResponse tokens = login("+919876740010");
        UUID family = sessionOf(tokens.accessToken());
        int threads = 12;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return authenticationService.refresh(tokens.refreshToken()).refreshToken();
                } catch (InvalidRefreshTokenException e) {
                    return null;
                }
            }));
        }
        start.countDown();
        List<String> winners = new ArrayList<>();
        for (Future<String> f : results) {
            String token = f.get(30, TimeUnit.SECONDS);
            if (token != null) {
                winners.add(token);
            }
        }
        pool.shutdownNow();

        // At most one request spent the token; everyone else was a replay, and a replay revokes the family. With the
        // advisory lock the revocation cannot slip between the winner's rotation and its replacement row.
        assertThat(winners).hasSizeLessThanOrEqualTo(1);
        assertThat(refreshSessions.isSessionLive(family)).isFalse();
        winners.forEach(token -> refresh(token).expectStatus().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private UUID sessionOf(String accessToken) {
        return tokenService.parse(accessToken).orElseThrow().sessionId();
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

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return restClient.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshToken))
                .exchange();
    }

    private void logout(String refreshToken) {
        restClient.post().uri("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshToken))
                .exchange().expectStatus().isNoContent();
    }

    private RestTestClient.ResponseSpec me(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange();
    }
}
