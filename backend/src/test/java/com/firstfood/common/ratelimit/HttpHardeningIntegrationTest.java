package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Response headers, request correlation ids and the request-size limit. */
class HttpHardeningIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Test
    void responsesCarryTheSecurityHeaders() {
        restClient.get().uri("/api/v1/version").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Security-Policy", ".*default-src 'none'.*frame-ancestors 'none'.*")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*");
    }

    @Test
    void everyResponseGetsARequestIdAndAWellFormedOneIsEchoed() {
        restClient.get().uri("/api/v1/version").exchange()
                .expectHeader().valueMatches("X-Request-Id", "[A-Za-z0-9._-]{8,64}");

        restClient.get().uri("/api/v1/version").header("X-Request-Id", "client-trace-12345").exchange()
                .expectHeader().valueEquals("X-Request-Id", "client-trace-12345");
    }

    @Test
    void aMaliciousRequestIdIsReplacedNotEchoed() {
        // Newlines/control characters in an id would let a caller forge log lines.
        restClient.get().uri("/api/v1/version").header("X-Request-Id", "bad id with spaces and <tags>").exchange()
                .expectHeader().valueMatches("X-Request-Id", "[A-Za-z0-9._-]{8,64}");
    }

    @Test
    void oversizedBodiesAreRejectedWith413BeforeAnyWork() {
        String huge = "{\"phone\":\"" + "9".repeat(80_000) + "\"}";

        String body = restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(huge)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.valueOf(413))
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void errorsStillUseTheStableJsonShape() {
        String body = restClient.get().uri("/api/v1/me").exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"UNAUTHENTICATED\"");
    }
}
