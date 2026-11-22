package com.firstfood.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * F-03 abuse caps: anyone can sign up on the staff portal, so how many providers one account may own and create, and how
 * many phone-number lookups one provider may make, are limited. Phones use +91987663xxxx; the database is a shared
 * singleton container, so do not reuse.
 */
@TestPropertySource(properties = {
        "app.provider.max-owned-per-account=3",
        "app.provider.max-created-per-account-per-day=2",
        "app.provider.max-phone-lookups-per-day=2",
})
class ProviderAbuseLimitsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anAccountCanCreateOnlyAFewProvidersPerDay() {
        Account owner = login("+919876630001");

        createProvider(owner, "Day One").expectStatus().isCreated();
        createProvider(owner, "Day Two").expectStatus().isCreated();

        assertError(createProvider(owner, "Day Three"), HttpStatus.TOO_MANY_REQUESTS, "PROVIDER_CREATE_RATE_LIMITED");
    }

    @Test
    void anAccountCannotOwnMoreThanTheCapAtOnce() {
        Account owner = login("+919876630002");
        // Owned providers that were not created through the API (e.g. transferred to this account) count too.
        for (int i = 0; i < 3; i++) {
            insertOwnedProvider(owner.id());
        }

        assertError(createProvider(owner, "One Too Many"), HttpStatus.CONFLICT, "PROVIDER_LIMIT_REACHED");
    }

    @Test
    void aProviderCanLookUpOnlyAFewPhoneNumbersPerDay() {
        Account owner = login("+919876630003");
        UUID provider = createProvider(owner, "Lookup Mess").expectStatus().isCreated()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody().id();

        // Two lookups (unregistered numbers answer "not found") are allowed...
        assertError(assign(owner, provider, "+919876639001"), HttpStatus.NOT_FOUND, "TARGET_ACCOUNT_NOT_FOUND");
        assertError(assign(owner, provider, "+919876639002"), HttpStatus.NOT_FOUND, "TARGET_ACCOUNT_NOT_FOUND");
        // ...a third is refused, so a provider cannot sweep phone numbers to see which ones have accounts.
        assertError(assign(owner, provider, "+919876639003"), HttpStatus.TOO_MANY_REQUESTS, "LOOKUP_LIMIT_REACHED");
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String accessToken) {
    }

    private Account login(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        AuthTokensResponse tokens = restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        ProfileResponse profile = restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
        return new Account(profile.accountId(), tokens.accessToken());
    }

    private RestTestClient.ResponseSpec createProvider(Account owner, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return restClient.method(HttpMethod.POST).uri("/api/v1/providers")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    private RestTestClient.ResponseSpec assign(Account owner, UUID provider, String phone) {
        return restClient.method(HttpMethod.POST).uri("/api/v1/providers/" + provider + "/roles")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("phone", phone, "role", "MANAGER")).exchange();
    }

    private void insertOwnedProvider(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into food_provider (id, name, address_line, locality, city) "
                + "values (?, 'Seeded Mess', '1 Test Road', 'MP Nagar', 'Bhopal')", id);
        jdbc.update("insert into food_provider_type (provider_id, provider_type) values (?, 'MESS')", id);
        jdbc.update("insert into provider_role_assignment (id, provider_id, account_id, role, assigned_by) "
                + "values (?, ?, ?, 'OWNER', ?)", UUID.randomUUID(), id, ownerId, ownerId);
    }

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }
}
