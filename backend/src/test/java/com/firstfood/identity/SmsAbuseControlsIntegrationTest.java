package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.error.UnsupportedPhoneRegionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-05, destination side: OTP SMS go only to the countries on the allow-list, through every route that can send
 * one, and a refused destination costs nothing (no SMS, no budget). Phones are +91987670xxxx; the database is a shared
 * singleton container, so do not reuse them.
 */
class SmsAbuseControlsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    OtpService otpService;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void anInternationalNumberIsRefusedAndNothingIsSent() {
        String body = requestOtp("+14155552671")
                .expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"code\":\"PHONE_REGION_NOT_SUPPORTED\"");
        assertThatThrownBy(() -> otpSender.lastOtpFor("+14155552671")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBareTenDigitNumberWithNoCountryCodeIsRefused() {
        requestOtp("9876700001").expectStatus().isBadRequest();
        assertThatThrownBy(() -> otpSender.lastOtpFor("9876700001")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void indianNumbersStillWorkWithAndWithoutThePlus() {
        requestOtp("+919876700011").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("919876700012").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        assertThat(otpSender.lastOtpFor("+919876700011")).hasSize(6);
        // F-13: the SMS provider and the stored challenge always see the one canonical spelling.
        assertThat(otpSender.lastOtpFor("+919876700012")).hasSize(6);
    }

    @Test
    void theRuleLivesInTheOneDoorEveryPhoneOtpUses() {
        // Phone change and owner step-up call OtpService directly, so the allow-list cannot be bypassed per route.
        for (OtpPurpose purpose : new OtpPurpose[] {OtpPurpose.LOGIN, OtpPurpose.PHONE_CHANGE, OtpPurpose.OWNER_ACTION}) {
            assertThatThrownBy(() -> otpService.requestOtp("+442071838750", purpose))
                    .isInstanceOf(UnsupportedPhoneRegionException.class);
        }
    }

    @Test
    void theAnswerForAnUnsupportedRegionNeverDependsOnWhetherAnAccountExists() {
        // Same number twice, before and after registering an Indian one: the 400 is a pure function of the number.
        requestOtp("+14155552672").expectStatus().isBadRequest();
        requestOtp("+919876700021").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+14155552672").expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec requestOtp(String phone) {
        return restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }
}
