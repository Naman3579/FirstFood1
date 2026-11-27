package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AddPhoneRequest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.EmailChangeRequest;
import com.firstfood.identity.dto.EmailChangeVerifyRequest;
import com.firstfood.identity.dto.EmailOtpRequest;
import com.firstfood.identity.dto.EmailOtpVerifyRequest;
import com.firstfood.identity.dto.EmailSignUpRequest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PhoneChangeVerifyRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.ProfileResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A phone number can be added to an email-first account without a code (launch mode, app.phone.verification-required
 * is false in the test profile) - but an unproven number must never act as an identity. Phones use +9162001200xx and
 * emails are unique to this class; the database is a shared singleton container, so do not reuse them.
 */
class UnverifiedPhoneIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    TestCapturingEmailOtpSender emailSender;

    @Test
    void anEmailAccountCanAddAPhoneWithoutACodeAndItStaysUnverified() {
        AuthTokensResponse tokens = emailSignUp("addphone.one@example.com");

        ProfileResponse me = addPhone(tokens.accessToken(), "+916200120001").expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
        assertThat(me.phone()).isEqualTo("+916200120001");
        assertThat(me.phoneVerified()).isFalse();
        assertThat(profile(tokens.accessToken()).phoneVerified()).isFalse();
    }

    @Test
    void anUnverifiedNumberCannotSignInByPhone() {
        AuthTokensResponse tokens = emailSignUp("addphone.two@example.com");
        addPhone(tokens.accessToken(), "+916200120002").expectStatus().isOk();

        requestPhoneOtp("+916200120002");
        assertCode(phoneSignIn("customer", "+916200120002", otpSender.lastOtpFor("+916200120002")),
                HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");
    }

    @Test
    void theRealOwnerSigningUpWithThatNumberGetsHisOwnAccountAndTheClaimIsReleased() {
        AuthTokensResponse squatter = emailSignUp("addphone.three@example.com");
        addPhone(squatter.accessToken(), "+916200120003").expectStatus().isOk();

        requestPhoneOtp("+916200120003");
        AuthTokensResponse owner = restClient.post().uri("/api/v1/auth/customer/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalSignUpRequest("+916200120003", otpSender.lastOtpFor("+916200120003"), "Real Owner"))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        ProfileResponse ownerProfile = profile(owner.accessToken());
        assertThat(ownerProfile.phone()).isEqualTo("+916200120003");
        assertThat(ownerProfile.phoneVerified()).isTrue();
        ProfileResponse squatterProfile = profile(squatter.accessToken());
        assertThat(squatterProfile.accountId()).isNotEqualTo(ownerProfile.accountId());
        assertThat(squatterProfile.phone()).isNull();
    }

    @Test
    void aProvenNumberCannotBeReplacedWithoutACode() {
        AuthTokensResponse tokens = phoneLogin("+916200120004");
        assertCode(addPhone(tokens.accessToken(), "+916200120005"), HttpStatus.CONFLICT, "PHONE_VERIFICATION_REQUIRED");
    }

    @Test
    void aNumberProvenByAnotherAccountIsRefused() {
        phoneLogin("+916200120006");
        AuthTokensResponse tokens = emailSignUp("addphone.four@example.com");
        assertCode(addPhone(tokens.accessToken(), "+916200120006"), HttpStatus.CONFLICT, "PHONE_ALREADY_IN_USE");
    }

    @Test
    void theUnverifiedNumberCanLaterBeVerifiedWithACode() {
        AuthTokensResponse tokens = emailSignUp("addphone.five@example.com");
        addPhone(tokens.accessToken(), "+916200120007").expectStatus().isOk();

        restClient.post().uri("/api/v1/me/phone/otp/request")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeRequest("+916200120007"))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        restClient.post().uri("/api/v1/me/phone/otp/verify")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeVerifyRequest("+916200120007", otpSender.lastOtpFor("+916200120007")))
                .exchange().expectStatus().isOk();

        ProfileResponse me = profile(tokens.accessToken());
        assertThat(me.phone()).isEqualTo("+916200120007");
        assertThat(me.phoneVerified()).isTrue();
    }

    // ---- helpers ----

    private AuthTokensResponse emailSignUp(String email) {
        requestEmailOtp(email);
        return signUp("customer", email, emailSender.lastOtpFor(email), "Test Person").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec addPhone(String accessToken, String phone) {
        return restClient.put().uri("/api/v1/me/phone")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AddPhoneRequest(phone))
                .exchange();
    }

    private void requestPhoneOtp(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    private RestTestClient.ResponseSpec phoneSignIn(String portal, String phone, String code) {
        return restClient.post().uri("/api/v1/auth/" + portal + "/signin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, code))
                .exchange();
    }

    // ---- helpers ----

    private void requestEmailOtp(String email) {
        restClient.post().uri("/api/v1/auth/email/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailOtpRequest(email))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    private RestTestClient.ResponseSpec signIn(String portal, String email, String code) {
        return restClient.post().uri("/api/v1/auth/" + portal + "/email/signin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailOtpVerifyRequest(email, code))
                .exchange();
    }

    private RestTestClient.ResponseSpec signUp(String portal, String email, String code, String name) {
        return restClient.post().uri("/api/v1/auth/" + portal + "/email/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailSignUpRequest(email, code, name))
                .exchange();
    }

    private AuthTokensResponse phoneLogin(String phone) {
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

    private void verifyEmailOnAccount(String accessToken, String email) {
        restClient.post().uri("/api/v1/me/email/otp/request")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeRequest(email))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        restClient.post().uri("/api/v1/me/email/otp/verify")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeVerifyRequest(email, emailSender.lastOtpFor(email)))
                .exchange().expectStatus().isOk();
    }

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }

    private static void assertCode(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }
}
