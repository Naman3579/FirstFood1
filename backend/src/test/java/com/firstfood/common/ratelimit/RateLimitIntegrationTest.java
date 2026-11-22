package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.UpdateEmailRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End-to-end limits against real Redis, with tight budgets.
 *
 * The test server treats loopback as a trusted proxy, so every test picks its own fake client address via
 * {@code X-Forwarded-For} and gets private per-IP counters - the tests never share a budget, in any order.
 * Phones are +91987660xxxx (earlier phases use ...650xxxx to ...659xxxx).
 */
@TestPropertySource(properties = {
        "app.rate-limit.key-prefix=rl-it",
        "app.rate-limit.trusted-proxies=127.0.0.1/32,::1/128,0:0:0:0:0:0:0:1/128",
        "app.rate-limit.otp-request-ip-burst.limit=3",
        "app.rate-limit.otp-verify-failures-phone-ip.limit=3",
        "app.rate-limit.otp-verify-failures-phone.limit=4",
        "app.rate-limit.account-write.limit=2",
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void otpRequestsPerIpAreCappedNoMatterWhichPhonesAreUsed() {
        String ip = "198.51.100.10";

        requestOtp("+919876600001", ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+919876600002", ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        requestOtp("+919876600003", ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        // Rotating phone numbers does not get around the per-IP cap.
        String body = requestOtp("+919876600004", ip)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"RATE_LIMITED\"");
    }

    @Test
    void otpRequestsPerPhoneAreCappedNoMatterWhichIpsAreUsed() {
        String phone = "+919876600010";

        // app.otp.request-rate-limit-per-hour = 5. Each request comes from a different address.
        for (int i = 1; i <= 5; i++) {
            requestOtp(phone, "198.51.100." + (20 + i)).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        }

        String body = requestOtp(phone, "198.51.100.30")
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("OTP_RATE_LIMIT_EXCEEDED");
    }

    @Test
    void theSamePhoneWithOrWithoutPlusSharesOneBudget() {
        for (int i = 1; i <= 5; i++) {
            String phone = (i % 2 == 0) ? "+919876600011" : "919876600011";
            requestOtp(phone, "198.51.100." + (40 + i)).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        }

        requestOtp("+919876600011", "198.51.100.50").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void repeatedWrongCodesLockOutThatPhoneAndAddressEvenForTheRightCode() {
        String phone = "+919876600020";
        String ip = "198.51.100.60";
        requestOtp(phone, ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        String realCode = otpSender.lastOtpFor(phone);

        for (int i = 0; i < 3; i++) {
            verifyOtp(phone, "000000", ip).expectStatus().isUnauthorized();
        }

        String body = verifyOtp(phone, realCode, ip)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("OTP_VERIFY_RATE_LIMITED");

        // A different, honest address is not locked out by the first one's failures (the pair limit is per phone+IP)...
        verifyOtp(phone, realCode, "198.51.100.61").expectStatus().isOk();
    }

    @Test
    void failuresFromOtherAddressesNeverLockTheRealOwnerOut() {
        String phone = "+919876600030";
        requestOtp(phone, "198.51.100.70").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        String realCode = otpSender.lastOtpFor(phone);

        // F-02: the per-phone counter (limit 4 in this test) is an alert signal only. Wrong guesses from four other
        // addresses must not stop the owner at a fifth, fresh address from signing in with the right code.
        for (int i = 1; i <= 3; i++) {
            verifyOtp(phone, "000000", "198.51.100." + (70 + i)).expectStatus().isUnauthorized();
        }
        verifyOtp(phone, "000000", "198.51.100.74").expectStatus().isUnauthorized();

        verifyOtp(phone, realCode, "198.51.100.80").expectStatus().isOk();
    }

    @Test
    void badVerifiesWithNoPendingCodeCannotLockAVictimOut() {
        String phone = "+919876600031";
        String attacker = "198.51.100.85";

        // F-02 reproduction: no OTP requested, no SIM. These are "no pending code" answers and must not count.
        for (int i = 0; i < 12; i++) {
            verifyOtp(phone, "000000", attacker).expectStatus().isUnauthorized();
        }

        // The attacker's own address is not locked either (nothing was counted), and the victim signs in normally.
        requestOtp(phone, "198.51.100.86").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        verifyOtp(phone, otpSender.lastOtpFor(phone), "198.51.100.86").expectStatus().isOk();
    }

    @Test
    void aFreshOtpRequestStartsANewAttemptBudgetForThatAddress() {
        String phone = "+919876600032";
        String ip = "198.51.100.87";
        requestOtp(phone, ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        for (int i = 0; i < 3; i++) { // app.rate-limit.otp-verify-failures-phone-ip.limit = 3
            verifyOtp(phone, "000000", ip).expectStatus().isUnauthorized();
        }
        verifyOtp(phone, otpSender.lastOtpFor(phone), ip).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        requestOtp(phone, ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        verifyOtp(phone, otpSender.lastOtpFor(phone), ip).expectStatus().isOk();
    }

    @Test
    void successfulLoginIsNeverPenalised() {
        String phone = "+919876600040";
        String ip = "198.51.100.90";
        for (int i = 0; i < 3; i++) {
            requestOtp(phone, ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
            verifyOtp(phone, otpSender.lastOtpFor(phone), ip).expectStatus().isOk();
        }
    }

    @Test
    void accountWriteBudgetIsSeparateFromReads() {
        String phone = "+919876600050";
        String ip = "198.51.100.100";
        AuthTokensResponse tokens = login(phone, ip);

        // app.rate-limit.account-write.limit = 2
        patchEmail(tokens, "a@example.com", ip).expectStatus().isOk();
        patchEmail(tokens, "b@example.com", ip).expectStatus().isOk();
        patchEmail(tokens, "c@example.com", ip)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueMatches("Retry-After", "\\d+");

        // Reads have their own (here: generous) budget and keep working.
        restClient.get().uri("/api/v1/me")
                .header("X-Forwarded-For", ip)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk();
    }

    // ---------------------------------------------------------------- helpers

    private RestTestClient.ResponseSpec requestOtp(String phone, String ip) {
        return restClient.post().uri("/api/v1/auth/otp/request")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }

    private RestTestClient.ResponseSpec verifyOtp(String phone, String code, String ip) {
        return restClient.post().uri("/api/v1/auth/otp/verify")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, code))
                .exchange();
    }

    private AuthTokensResponse login(String phone, String ip) {
        requestOtp(phone, ip).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        return verifyOtp(phone, otpSender.lastOtpFor(phone), ip)
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec patchEmail(AuthTokensResponse tokens, String email, String ip) {
        return restClient.patch().uri("/api/v1/me/email")
                .header("X-Forwarded-For", ip)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateEmailRequest(email))
                .exchange();
    }
}
