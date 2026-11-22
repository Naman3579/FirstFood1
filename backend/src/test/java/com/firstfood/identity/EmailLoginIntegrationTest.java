package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.EmailChangeRequest;
import com.firstfood.identity.dto.EmailChangeVerifyRequest;
import com.firstfood.identity.dto.EmailOtpRequest;
import com.firstfood.identity.dto.EmailOtpVerifyRequest;
import com.firstfood.identity.dto.EmailSignUpRequest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.UpdateEmailRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Email sign-up and sign-in (POST /auth/email/otp/request, /auth/{portal}/email/signin|signup). Emails here are unique
 * to this class and phones use +9162001100xx; the database is a shared singleton container, so do not reuse them.
 */
class EmailLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    TestCapturingEmailOtpSender emailSender;

    @Test
    void anEmailOnlyAccountCanSignUpAndThenSignInAgain() {
        requestEmailOtp("Meera.Iyer@Example.com");
        // The code goes to the lower-cased address.
        String code = emailSender.lastOtpFor("meera.iyer@example.com");

        AuthTokensResponse tokens = signUp("customer", "Meera.Iyer@Example.com", code, "Meera Iyer").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        ProfileResponse me = profile(tokens.accessToken());
        assertThat(me.phone()).isNull();
        assertThat(me.email()).isEqualTo("meera.iyer@example.com");
        assertThat(me.emailVerified()).isTrue();
        assertThat(me.fullName()).isEqualTo("Meera Iyer");
        assertThat(me.portals()).containsExactly("CUSTOMER");

        requestEmailOtp("meera.iyer@example.com");
        AuthTokensResponse again = signIn("customer", "meera.iyer@example.com", emailSender.lastOtpFor("meera.iyer@example.com"))
                .expectStatus().isOk().expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(again.accessToken()).accountId()).isEqualTo(me.accountId());
    }

    @Test
    void signInWithAnUnregisteredEmailIs404WithoutSpendingTheCode() {
        String email = "nobody.here@example.com";
        requestEmailOtp(email);
        String code = emailSender.lastOtpFor(email);

        assertCode(signIn("customer", email, code), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");

        // The same code still creates the account.
        signUp("customer", email, code, "New Person").expectStatus().isOk();
    }

    @Test
    void aWrongCodeIsRejectedBeforeAnythingAboutTheAccountIsRevealed() {
        requestEmailOtp("wrong.code@example.com");
        assertCode(signIn("customer", "wrong.code@example.com", "000000".equals(emailSender.lastOtpFor("wrong.code@example.com")) ? "111111" : "000000"),
                HttpStatus.UNAUTHORIZED, "INVALID_OTP");
    }

    @Test
    void signingUpTwiceOnTheSamePortalIs409AndTheOtherPortalCanBeAdded() {
        String email = "twice@example.com";
        requestEmailOtp(email);
        signUp("customer", email, emailSender.lastOtpFor(email), "Twice Over").expectStatus().isOk();

        requestEmailOtp(email);
        String code = emailSender.lastOtpFor(email);
        assertCode(signUp("customer", email, code, "Twice Over"), HttpStatus.CONFLICT, "ACCOUNT_ALREADY_REGISTERED");

        // Same code, other door: the account gains the staff portal instead of a second account appearing.
        AuthTokensResponse tokens = signUp("staff", email, code, "Twice Over").expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(tokens.accessToken()).portals()).containsExactly("CUSTOMER", "STAFF");
    }

    @Test
    void aPhoneAccountWithAVerifiedEmailCanSignInByEmail() {
        String phone = "+916200110001";
        AuthTokensResponse phoneTokens = phoneLogin(phone);
        verifyEmailOnAccount(phoneTokens.accessToken(), "dual.login@example.com");

        requestEmailOtp("dual.login@example.com");
        AuthTokensResponse byEmail = signIn("customer", "dual.login@example.com", emailSender.lastOtpFor("dual.login@example.com"))
                .expectStatus().isOk().expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        ProfileResponse me = profile(byEmail.accessToken());
        assertThat(me.phone()).isEqualTo(phone);
        assertThat(me.accountId()).isEqualTo(profile(phoneTokens.accessToken()).accountId());
    }

    @Test
    void anUnverifiedEmailIsNeverALoginIdentity() {
        AuthTokensResponse phoneTokens = phoneLogin("+916200110002");
        // The original PATCH stores an address nobody has proven.
        restClient.patch().uri("/api/v1/me/email")
                .header("Authorization", "Bearer " + phoneTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateEmailRequest("unproven@example.com"))
                .exchange().expectStatus().isOk();

        requestEmailOtp("unproven@example.com");
        // Whoever holds this mailbox cannot get into the phone account with it ...
        assertCode(signIn("customer", "unproven@example.com", emailSender.lastOtpFor("unproven@example.com")),
                HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");
        // ... they just get their own, separate account.
        AuthTokensResponse own = signUp("customer", "unproven@example.com", emailSender.lastOtpFor("unproven@example.com"), "Other Person")
                .expectStatus().isOk().expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(own.accessToken()).accountId()).isNotEqualTo(profile(phoneTokens.accessToken()).accountId());
    }

    @Test
    void aVerifiedEmailBelongsToOneAccount() {
        AuthTokensResponse first = phoneLogin("+916200110003");
        verifyEmailOnAccount(first.accessToken(), "shared.mail@example.com");

        AuthTokensResponse second = phoneLogin("+916200110004");
        restClient.post().uri("/api/v1/me/email/otp/request")
                .header("Authorization", "Bearer " + second.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeRequest("shared.mail@example.com"))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        Map<?, ?> body = restClient.post().uri("/api/v1/me/email/otp/verify")
                .header("Authorization", "Bearer " + second.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeVerifyRequest("shared.mail@example.com", emailSender.lastOtpFor("shared.mail@example.com")))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body.get("code")).isEqualTo("EMAIL_ALREADY_IN_USE");
    }

    @Test
    void aMalformedEmailIsRejectedBeforeAnyMailIsSent() {
        restClient.post().uri("/api/v1/auth/email/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "not-an-email"))
                .exchange().expectStatus().isBadRequest();
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
