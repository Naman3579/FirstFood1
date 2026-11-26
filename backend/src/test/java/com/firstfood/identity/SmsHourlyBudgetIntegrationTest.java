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
 * Audit F-05, spend side: the site-wide hourly budget is a circuit breaker. An attacker who rotates phone numbers (and,
 * in real life, IPs) beats the per-phone and per-IP limits; this is what still stops the spend. Own Redis key prefix so
 * the tight budget cannot leak into other tests. Phones are +91987671xxxx.
 */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-sms-hourly",
        "app.sms.hourly-budget=3",
        "app.sms.daily-budget=1000",
})
class SmsHourlyBudgetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void onceTheHourlyBudgetIsSpentNoMoreSmsGoOutEvenToFreshNumbers() {
        // Three different phones, each far under its own limit of 5/hour: the SITE budget is what runs out.
        requestOtp("+919876710001").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+919876710002").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+919876710003").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        String body = requestOtp("+919876710004")
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"SMS_TEMPORARILY_UNAVAILABLE\"");
        // The refused request must not have sent anything.
        assertThatThrownBy(() -> otpSender.lastOtpFor("+919876710004")).isInstanceOf(IllegalStateException.class);
    }

    private RestTestClient.ResponseSpec requestOtp(String phone) {
        return restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }
}
