package com.firstfood.common.ratelimit;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.OtpRequestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The shipped default: NO trusted proxies. A caller who sends their own {@code X-Forwarded-For} must not be able to
 * pick their own rate-limit bucket.
 */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-it-spoof",
        "app.rate-limit.otp-request-ip-burst.limit=3",
})
class RateLimitSpoofingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Test
    void aForgedForwardedForHeaderDoesNotEscapeThePerIpLimit() {
        // Phones +91987661xxxx. Every request claims a different client address; all share the real one.
        for (int i = 1; i <= 3; i++) {
            send("+91987661000" + i, "203.0.113." + i).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        }

        send("+919876610004", "203.0.113.99").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private RestTestClient.ResponseSpec send(String phone, String forgedAddress) {
        return restClient.post().uri("/api/v1/auth/otp/request")
                .header("X-Forwarded-For", forgedAddress)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }
}
