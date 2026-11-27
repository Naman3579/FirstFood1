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

/** Audit F-05: the site-wide DAILY budget is a hard ceiling too. Own key prefix. Phones are +91987672xxxx. */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-sms-daily",
        "app.sms.hourly-budget=1000",
        "app.sms.daily-budget=2",
})
class SmsDailyBudgetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void theDailyBudgetOpensTheCircuitBreaker() {
        requestOtp("+919876720001").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+919876720002").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        String body = requestOtp("+919876720003")
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"SMS_TEMPORARILY_UNAVAILABLE\"");
        assertThatThrownBy(() -> otpSender.lastOtpFor("+919876720003")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void foreignNumbersAreRefusedBeforeTheBudgetIsEvenConsulted() {
        // Shares the tiny budget with the test above, in either order, so it may already be spent. Foreign numbers must
        // still get the 400 (region check runs first), never the 503 (budget).
        for (int i = 0; i < 5; i++) {
            requestOtp("+1415555200" + i).expectStatus().isBadRequest();
        }
    }

    private RestTestClient.ResponseSpec requestOtp(String phone) {
        return restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }
}
