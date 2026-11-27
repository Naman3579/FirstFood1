package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.OtpRequestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-05: with the bot challenge ENABLED, the public OTP request needs a verified token before it can cost an SMS.
 * Cloudflare itself is not contacted: the verify URL points at a closed local port, which is exactly the "verification
 * service unreachable" case (must fail closed). Phones are +91987673xxxx.
 */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-botchallenge",
        "app.bot-challenge.enabled=true",
        "app.bot-challenge.secret-key=test-secret",
        "app.bot-challenge.verify-url=http://127.0.0.1:9/siteverify",
})
class BotChallengeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    BotChallengeVerifier verifier;

    @Test
    void theRealVerifierIsActiveWhenEnabled() {
        assertThat(verifier).isInstanceOf(TurnstileBotChallengeVerifier.class);
    }

    @Test
    void noTokenMeansNoSms() {
        String body = restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest("+919876730001"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"BOT_CHALLENGE_FAILED\"");
        assertThatThrownBy(() -> otpSender.lastOtpFor("+919876730001")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ifTheVerifierCannotBeReachedTheRequestIsRefusedNotWavedThrough() {
        String body = restClient.post().uri("/api/v1/auth/otp/request")
                .header("X-Challenge-Token", "any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest("+919876730002"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"BOT_CHALLENGE_UNAVAILABLE\"");
        assertThatThrownBy(() -> otpSender.lastOtpFor("+919876730002")).isInstanceOf(IllegalStateException.class);
    }
}
