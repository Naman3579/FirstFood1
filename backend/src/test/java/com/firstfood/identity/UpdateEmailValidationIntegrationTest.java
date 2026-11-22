package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
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
 * F-15: PATCH /me/email validates its input. Phones use +91987685xxxx; the database is a shared singleton
 * container, so do not reuse them.
 */
class UpdateEmailValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void anOverlongAddressIsA400NotA500AndNothingIsStored() {
        AuthTokensResponse tokens = login("+919876850001");
        String tooLong = emailOfLength(256);

        patch(tokens, tooLong).expectStatus().isBadRequest();

        assertThat(profile(tokens).email()).isNull();
    }

    @Test
    void anAddressOfExactly255CharactersIsAccepted() {
        AuthTokensResponse tokens = login("+919876850002");
        String atLimit = emailOfLength(255);

        patch(tokens, atLimit).expectStatus().isOk();

        assertThat(profile(tokens).email()).isEqualTo(atLimit);
    }

    @Test
    void blankAndMissingAddressesAreRejectedAndTheStoredOneSurvives() {
        AuthTokensResponse tokens = login("+919876850003");
        patch(tokens, "keep@example.com").expectStatus().isOk();

        patch(tokens, "").expectStatus().isBadRequest();
        patch(tokens, "   ").expectStatus().isBadRequest();
        patch(tokens, null).expectStatus().isBadRequest();

        assertThat(profile(tokens).email()).isEqualTo("keep@example.com");
    }

    @Test
    void aMalformedAddressIsStillRejected() {
        AuthTokensResponse tokens = login("+919876850004");
        patch(tokens, "not-an-email").expectStatus().isBadRequest();
        assertThat(profile(tokens).email()).isNull();
    }

    // ------------------------------------------------------------ helpers

    /**
     * A syntactically valid address of exactly {@code length} characters, so a 400 can only come from @Size:
     * Bean Validation's @Email caps the local part at 64 and each domain label at 63 characters.
     */
    private static String emailOfLength(int length) {
        String local = "a".repeat(64);
        int domainLength = length - local.length() - 1;
        StringBuilder domain = new StringBuilder();
        while (domainLength > 0) {
            int label = Math.min(63, domainLength);
            if (domainLength - label == 1) { // a lone "." would leave an empty label
                label--;
            }
            domain.append("b".repeat(label));
            domainLength -= label;
            if (domainLength > 0) {
                domain.append('.');
                domainLength--;
            }
        }
        String email = local + "@" + domain;
        if (email.length() != length) {
            throw new IllegalStateException("fixture is " + email.length() + " characters, wanted " + length);
        }
        return email;
    }

    private RestTestClient.ResponseSpec patch(AuthTokensResponse tokens, String email) {
        return restClient.patch().uri("/api/v1/me/email")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateEmailRequest(email))
                .exchange();
    }

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

    private ProfileResponse profile(AuthTokensResponse tokens) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }
}
