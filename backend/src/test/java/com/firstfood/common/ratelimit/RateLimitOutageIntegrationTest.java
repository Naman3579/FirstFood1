package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.OtpRequestRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Redis is gone: SMS routes must refuse, ordinary routes must keep working under the local fallback. */
@TestPropertySource(properties = "app.rate-limit.key-prefix=rl-it-outage")
class RateLimitOutageIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    RateLimitStore store;

    @Autowired
    RestTestClient restClient;

    @BeforeEach
    void redisIsDown() {
        var down = new RateLimitStoreException("simulated outage");
        when(store.hit(anyString(), anyInt(), any(Duration.class))).thenThrow(down);
        when(store.peek(anyString(), anyInt(), any(Duration.class))).thenThrow(down);
        // OTP policies use the sliding window. A Mockito mock does NOT run interface default methods (they would
        // return null), so the outage has to be stubbed on these two as well.
        when(store.hitSliding(anyString(), anyInt(), any(Duration.class))).thenThrow(down);
        when(store.peekSliding(anyString(), anyInt(), any(Duration.class))).thenThrow(down);
        org.mockito.Mockito.doThrow(down).when(store).reset(anyString());
    }

    @Test
    void otpRequestsFailClosedWith503AndRetryAfter() {
        String body = restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest("+919876620001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    }

    @Test
    void ordinaryRoutesKeepServingUnderTheLocalFallback() {
        // Not 503: the per-IP API budget degrades to this instance's own counters instead of blocking everyone.
        restClient.get().uri("/api/v1/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthAndVersionAreNotLimitedAtAll() {
        restClient.get().uri("/api/v1/version").exchange().expectStatus().isOk();
    }
}
