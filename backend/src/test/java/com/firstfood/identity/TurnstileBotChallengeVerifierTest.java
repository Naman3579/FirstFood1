package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.firstfood.identity.error.BotChallengeFailedException;
import com.firstfood.identity.error.BotChallengeUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** F-05: the Turnstile verifier, against a mock of Cloudflare's siteverify (no network). */
class TurnstileBotChallengeVerifierTest {

    private static final String URL = "https://challenges.example.test/siteverify";

    private MockRestServiceServer server;
    private TurnstileBotChallengeVerifier verifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new TurnstileBotChallengeVerifier(
                builder.build(), new BotChallengeProperties(true, "test-secret", URL), new SimpleMeterRegistry());
    }

    @Test
    void aValidTokenPassesAndTheSecretTokenAndAddressAreSentAsAForm() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("secret=test-secret").contains("response=good-token")
                            .contains("remoteip=203.0.113.7");
                })
                .andRespond(withSuccess("{\"success\":true,\"error-codes\":[],\"hostname\":\"example.test\"}",
                        MediaType.APPLICATION_JSON));

        verifier.verify("good-token", "203.0.113.7");

        server.verify();
    }

    @Test
    void aRejectedTokenIsA400() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("used-token", "203.0.113.7"))
                .isInstanceOf(BotChallengeFailedException.class);
    }

    @Test
    void aMissingOrAbsurdTokenIsRefusedWithoutCallingCloudflare() {
        // No expectations registered: any outbound call would fail the test.
        assertThatThrownBy(() -> verifier.verify(null, "203.0.113.7")).isInstanceOf(BotChallengeFailedException.class);
        assertThatThrownBy(() -> verifier.verify("  ", "203.0.113.7")).isInstanceOf(BotChallengeFailedException.class);
        assertThatThrownBy(() -> verifier.verify("x".repeat(5000), "203.0.113.7"))
                .isInstanceOf(BotChallengeFailedException.class);
        server.verify();
    }

    @Test
    void whenCloudflareIsDownTheRequestIsRefusedNotWavedThrough() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> verifier.verify("some-token", "203.0.113.7"))
                .isInstanceOf(BotChallengeUnavailableException.class);
    }

    @Test
    void anEmptyAnswerIsTreatedAsFailure() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify("some-token", null)).isInstanceOf(BotChallengeFailedException.class);
    }
}
