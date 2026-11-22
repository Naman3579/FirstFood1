package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.EmailChangeRequest;
import com.firstfood.identity.dto.EmailChangeVerifyRequest;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.UpdateEmailRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The OTP-gated email flow (POST /me/email/otp/request + /verify) and how it coexists with the original
 * PATCH /me/email. Phones use +91987663xxxx; the database is a shared singleton container, so do not reuse them.
 */
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    TestCapturingEmailOtpSender emailSender;

    @Test
    void emailIsStoredOnlyAfterTheEmailedCodeIsVerified() {
        AuthTokensResponse tokens = login("+919876630001");

        restClient.post().uri("/api/v1/me/email/otp/request")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeRequest("Asha.Verma@Example.com"))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        // Nothing is stored yet.
        ProfileResponse before = profile(tokens.accessToken());
        assertThat(before.email()).isNull();
        assertThat(before.emailVerified()).isFalse();

        // The code goes to the lower-cased address.
        String code = emailSender.lastOtpFor("asha.verma@example.com");
        ProfileResponse after = restClient.post().uri("/api/v1/me/email/otp/verify")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeVerifyRequest("Asha.Verma@Example.com", code))
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();

        assertThat(after.email()).isEqualTo("asha.verma@example.com");
        assertThat(after.emailVerified()).isTrue();
    }

    @Test
    void aWrongEmailCodeIsRejectedAndNothingIsStored() {
        AuthTokensResponse tokens = login("+919876630002");
        restClient.post().uri("/api/v1/me/email/otp/request")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeRequest("wrong@example.com"))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        String real = emailSender.lastOtpFor("wrong@example.com");
        String wrong = real.equals("000000") ? "111111" : "000000";
        restClient.post().uri("/api/v1/me/email/otp/verify")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailChangeVerifyRequest("wrong@example.com", wrong))
                .exchange().expectStatus().isUnauthorized();

        assertThat(profile(tokens.accessToken()).email()).isNull();
    }

    @Test
    void theOriginalPatchStillWorksButStoresAnUnverifiedEmail() {
        AuthTokensResponse tokens = login("+919876630003");

        ProfileResponse patched = restClient.patch().uri("/api/v1/me/email")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateEmailRequest("plain@example.com"))
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();

        assertThat(patched.email()).isEqualTo("plain@example.com");
        assertThat(patched.emailVerified()).isFalse();
    }

    /** Test profile only (register-new-accounts=true); production refuses unknown numbers, see LegacyLoginClosedIntegrationTest. */
    @Test
    void legacyLoginRegistersAnewNumberOnBothPortals() {
        AuthTokensResponse tokens = login("+919876630004");
        assertThat(profile(tokens.accessToken()).portals()).containsExactlyInAnyOrder("CUSTOMER", "STAFF");
    }

    // ------------------------------------------------------------ helpers

    private AuthTokensResponse login(String phone) {
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

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }
}
