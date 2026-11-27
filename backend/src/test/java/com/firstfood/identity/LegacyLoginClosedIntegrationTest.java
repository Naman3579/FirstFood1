package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.ProfileResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-12, production behaviour of the old portal-less {@code POST /auth/otp/verify}: it signs in existing accounts
 * but no longer creates one (and so no longer hands a new number BOTH portals). The rest of the suite runs with
 * {@code register-new-accounts=true} as a one-call login; this class switches it off, like the production profile.
 * Phones use +91987682xxxx; the database is a shared singleton container, so do not reuse them.
 */
@TestPropertySource(properties = "app.auth.legacy-login.register-new-accounts=false")
class LegacyLoginClosedIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    UserAccountRepository accounts;

    @Test
    void aNumberNobodyRegisteredIsRefusedAndItsCodeIsNotSpent() {
        String phone = "+919876820001";
        requestOtp(phone);
        String code = otpSender.lastOtpFor(phone);

        assertCode(legacyVerify(phone, code), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_REGISTERED");
        assertThat(accounts.existsByPhone(phone)).isFalse();

        // The same code still signs the person up on the portal they actually want - and only that portal is created.
        AuthTokensResponse tokens = signUp("staff", phone, code, "Ravi Kumar");
        assertThat(profile(tokens.accessToken()).portals()).containsExactly("STAFF");
    }

    @Test
    void aWrongCodeForAnUnknownNumberIsRejectedLikeAWrongCodeForAKnownOne() {
        String unknown = "+919876820002";
        String known = "+919876820003";
        requestOtp(known);
        signUp("customer", known, otpSender.lastOtpFor(known), "Known Person");

        requestOtp(unknown);
        requestOtp(known);
        Map<?, ?> forUnknown = legacyVerify(unknown, "000000").expectStatus().isUnauthorized()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<?, ?> forKnown = legacyVerify(known, "000000").expectStatus().isUnauthorized()
                .expectBody(Map.class).returnResult().getResponseBody();

        // Without the code, "unknown number" and "known number" must look the same (no registration oracle).
        assertThat(forUnknown.get("code")).isEqualTo(forKnown.get("code"));
        assertThat(accounts.existsByPhone(unknown)).isFalse();
    }

    @Test
    void anExistingAccountStillSignsInAndKeepsOnlyItsOwnPortals() {
        String phone = "+919876820004";
        requestOtp(phone);
        signUp("customer", phone, otpSender.lastOtpFor(phone), "Asha Verma");

        requestOtp(phone);
        AuthTokensResponse tokens = legacyVerify(phone, otpSender.lastOtpFor(phone)).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        assertThat(profile(tokens.accessToken()).portals()).containsExactly("CUSTOMER");
    }

    // ------------------------------------------------------------ helpers

    private void requestOtp(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    private RestTestClient.ResponseSpec legacyVerify(String phone, String code) {
        return restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, code))
                .exchange();
    }

    private AuthTokensResponse signUp(String portal, String phone, String code, String name) {
        return restClient.post().uri("/api/v1/auth/" + portal + "/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalSignUpRequest(phone, code, name))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
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
