package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-14: the customer-side {@code /me/...} routes, including sub-paths like {@code PUT /me/person/address}, refuse
 * an account that never registered on the customer portal, while the shared account routes stay open to it. Phones use
 * +91987684xxxx; the database is a shared singleton container, so do not reuse them.
 */
class CustomerPortalGateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void aStaffOnlyAccountIsRefusedOnEveryCustomerSideMeRoute() {
        String staffOnly = signUp("staff", "+919876840001").accessToken();
        UUID id = UUID.randomUUID();

        String[][] routes = {
            {"GET", "/api/v1/me/person"},
            {"PUT", "/api/v1/me/person"},
            {"PUT", "/api/v1/me/person/address"},
            {"GET", "/api/v1/me/memberships"},
            {"POST", "/api/v1/me/memberships/" + id + "/leave"},
            {"GET", "/api/v1/me/invitations"},
            {"POST", "/api/v1/me/invitations/" + id + "/accept"},
            {"POST", "/api/v1/me/invitations/" + id + "/decline"},
        };
        for (String[] route : routes) {
            var spec = restClient.method(HttpMethod.valueOf(route[0])).uri(route[1])
                    .header("Authorization", "Bearer " + staffOnly);
            // The gate runs before the body is bound, so an empty body is enough for the routes that take one.
            var response = "GET".equals(route[0])
                    ? spec.exchange()
                    : spec.contentType(MediaType.APPLICATION_JSON).body(Map.of()).exchange();
            Map<?, ?> body = response.expectStatus().isForbidden()
                    .expectBody(Map.class).returnResult().getResponseBody();
            assertThat(body).as("%s %s", route[0], route[1]).isNotNull();
            assertThat(body.get("code")).as("%s %s", route[0], route[1]).isEqualTo("PORTAL_ACCESS_DENIED");
        }
    }

    @Test
    void theSharedAccountRoutesStayOpenToAStaffOnlyAccount() {
        String staffOnly = signUp("staff", "+919876840002").accessToken();

        restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + staffOnly)
                .exchange().expectStatus().isOk();
    }

    @Test
    void aCustomerAccountGetsPastTheGate() {
        String customer = signUp("customer", "+919876840003").accessToken();

        // Whatever the person endpoint answers for a brand-new customer, it is not the portal refusal.
        restClient.get().uri("/api/v1/me/person")
                .header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().value(status -> assertThat(status).isNotEqualTo(403));
        restClient.get().uri("/api/v1/me/memberships")
                .header("Authorization", "Bearer " + customer)
                .exchange().expectStatus().isOk();
    }

    // ------------------------------------------------------------ helpers

    private AuthTokensResponse signUp(String portal, String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        return restClient.post().uri("/api/v1/auth/" + portal + "/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalSignUpRequest(phone, otpSender.lastOtpFor(phone), "Gate Tester"))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
    }
}
