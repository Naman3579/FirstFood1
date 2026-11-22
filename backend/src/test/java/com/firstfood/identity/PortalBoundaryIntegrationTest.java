package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-12: the staff portal is a door, not a security boundary. Anyone with an OTP can walk through it, so what
 * protects a provider is the per-provider role check, not "is registered on STAFF". Phones use +91987681xxxx; the
 * database is a shared singleton container, so do not reuse them.
 */
class PortalBoundaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void anyoneCanRegisterOnTheStaffPortalSoPortalMembershipProvesNothing() {
        String phone = "+919876810001";
        AuthTokensResponse tokens = signUpOnStaffPortal(phone, "Throwaway Account");

        assertThat(profile(tokens.accessToken()).portals()).containsExactly("STAFF");
        // The door opens: the staff API answers. It just has nothing to show this account.
        ProviderResponse[] mine = restClient.get().uri("/api/v1/staff/providers")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProviderResponse[].class).returnResult().getResponseBody();
        assertThat(mine).isEmpty();
    }

    @Test
    void aStaffPortalAccountWithNoRoleOnAProviderIsTreatedLikeAnyStranger() {
        String owner = login("+919876810002");
        String stranger = signUpOnStaffPortal("+919876810003", "Stranger").accessToken();
        UUID provider = createProvider(owner, "Boundary Kitchen " + UUID.randomUUID().toString().substring(0, 8));
        UUID nobody = UUID.randomUUID();

        // The owner's own call works, so the routes below really exist.
        restClient.get().uri("/api/v1/staff/providers/" + provider + "/customers")
                .header("Authorization", "Bearer " + owner).exchange().expectStatus().isOk();

        // The stranger passed the staff door, yet gets exactly what they get for a provider that does not exist.
        for (String path : new String[] {"", "/customers", "/customers/invitations"}) {
            Map<?, ?> foreign = get("/api/v1/staff/providers/" + provider + path, stranger);
            Map<?, ?> missing = get("/api/v1/staff/providers/" + nobody + path, stranger);
            assertThat(foreign).as("body for %s", path).isEqualTo(missing);
        }
        // ...and the provider is not in their list.
        ProviderResponse[] mine = restClient.get().uri("/api/v1/staff/providers")
                .header("Authorization", "Bearer " + stranger)
                .exchange().expectStatus().isOk()
                .expectBody(ProviderResponse[].class).returnResult().getResponseBody();
        assertThat(mine).extracting(ProviderResponse::id).doesNotContain(provider);
    }

    // ------------------------------------------------------------ helpers

    /** 404, and the JSON body (code + message) so two answers can be compared. */
    private Map<?, ?> get(String uri, String accessToken) {
        Map<?, ?> body = restClient.get().uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isNotFound()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return Map.of("code", String.valueOf(body.get("code")), "message", String.valueOf(body.get("message")));
    }

    private AuthTokensResponse signUpOnStaffPortal(String phone, String name) {
        requestOtp(phone);
        return restClient.post().uri("/api/v1/auth/staff/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalSignUpRequest(phone, otpSender.lastOtpFor(phone), name))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }

    /** The suite's one-call login (a new number gets both portals); returns the access token. */
    private String login(String phone) {
        requestOtp(phone);
        return restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody().accessToken();
    }

    private UUID createProvider(String accessToken, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return restClient.post().uri("/api/v1/providers")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange().expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(ProviderResponse.class).returnResult().getResponseBody().id();
    }

    private void requestOtp(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }
}
