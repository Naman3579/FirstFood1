package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.RefreshTokenRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-11, default configuration (10 s grace): two tabs that refresh at the same moment must not sign the user out.
 * A replay inside the window is refused, but the token that won stays usable. Phones: +91987675xxxx.
 */
class SessionFamilyGraceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void aReplayInsideTheGraceWindowIsRefusedButDoesNotEndTheSession() {
        String phone = "+919876750001";
        AuthTokensResponse first = login(phone);

        AuthTokensResponse winner = refresh(first.refreshToken()).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(winner).isNotNull();

        refresh(first.refreshToken()).expectStatus().isUnauthorized(); // the slower tab
        refresh(winner.refreshToken()).expectStatus().isOk();          // the session is intact
        me(winner.accessToken()).expectStatus().isOk();
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

    private RestTestClient.ResponseSpec me(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange();
    }
}
