package com.firstfood.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PortalSignUpRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.subscription.MySubscriptionView;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Customer discovery (nearby search, provider profile, self-subscribe) and the per-portal sign-in/up with the
 * /staff route prefix. Real Postgres + Redis. Phones use +91987662xxxx; the database is a shared singleton
 * container, so do not reuse them in other tests.
 */
class DiscoveryApiIntegrationTest extends AbstractIntegrationTest {

    // Bhopal city centre, and points roughly 3-4 km, 6-7 km and ~170 km (Indore) away from it.
    private static final String HERE = "lat=23.2599&lng=77.4126";

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void discoveryRequiresAuthentication() {
        assertThat(send("GET", "/api/v1/discover/providers", null, null).expectBody(String.class)
                .returnResult().getStatus().value()).isEqualTo(401);
    }

    @Test
    void nearbySearchRanksByDistanceAndHonoursRadiusAndFilters() {
        Account owner = login("+919876620001");
        Account customer = login("+919876620002");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID near = createProvider(owner, "Near " + tag, "VEG", "23.2330", "77.4340");
        UUID mid = createProvider(owner, "Mid " + tag, "NON_VEG", "23.3000", "77.4500");
        UUID far = createProvider(owner, "Far " + tag, "VEG", "22.7196", "75.8577");
        createPlan(owner, near, "Monthly", "2500", 30);
        createPlan(owner, mid, "Monthly", "3500", 30);

        ProviderCard[] nearby = search(customer, "?" + HERE + "&radiusKm=20&q=" + tag);
        assertThat(nearby).extracting(ProviderCard::id).containsExactly(near, mid);
        assertThat(nearby[0].distanceKm()).isBetween(2.0, 6.0);
        assertThat(nearby[0].distanceKm()).isLessThan(nearby[1].distanceKm());
        assertThat(nearby[0].available()).isTrue();
        assertThat(nearby[0].activePlanCount()).isEqualTo(1);
        assertThat(nearby[0].minPrice()).isEqualByComparingTo("2500");

        // The far provider shows up once the radius is wide enough.
        assertThat(search(customer, "?" + HERE + "&radiusKm=300&q=" + tag)).extracting(ProviderCard::id)
                .containsExactly(near, mid, far);

        // Food type and price filters.
        assertThat(search(customer, "?" + HERE + "&radiusKm=300&q=" + tag + "&foodType=NON_VEG"))
                .extracting(ProviderCard::id).containsExactly(mid);
        assertThat(search(customer, "?" + HERE + "&radiusKm=300&q=" + tag + "&minPrice=3000&maxPrice=4000"))
                .extracting(ProviderCard::id).containsExactly(mid);

        // A provider that stops taking customers drops out of "available" results but not out of the full list.
        send("PATCH", "/api/v1/staff/providers/" + near, owner.accessToken(),
                Map.of("acceptingNewCustomers", false)).expectStatus().isOk();
        assertThat(search(customer, "?" + HERE + "&radiusKm=20&q=" + tag)).extracting(ProviderCard::id)
                .containsExactly(mid);
        ProviderCard[] all = search(customer, "?" + HERE + "&radiusKm=20&q=" + tag + "&availableOnly=false");
        assertThat(all).extracting(ProviderCard::id).containsExactly(near, mid);
        assertThat(all[0].available()).isFalse();
    }

    @Test
    void closedProvidersAreNeverShown() {
        Account owner = login("+919876620003");
        Account customer = login("+919876620004");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID provider = createProvider(owner, "Closing " + tag, "VEG", "23.2330", "77.4340");
        send("PATCH", "/api/v1/staff/providers/" + provider, owner.accessToken(),
                closeBody(owner.accessToken(), provider, "test")).expectStatus().isOk();

        assertThat(search(customer, "?q=" + tag + "&availableOnly=false")).isEmpty();
        assertThat(send("GET", "/api/v1/discover/providers/" + provider, customer.accessToken(), null)
                .expectBody(String.class).returnResult().getStatus().value()).isEqualTo(404);
    }

    @Test
    void profileShowsActivePlansOnly() {
        Account owner = login("+919876620005");
        Account customer = login("+919876620006");
        UUID provider = createProvider(owner, "Profile " + UUID.randomUUID().toString().substring(0, 8), "VEG",
                "23.2330", "77.4340");
        PlanView live = createPlan(owner, provider, "Live", "2500", 30);
        PlanView retired = createPlan(owner, provider, "Retired", "1000", 10);
        send("POST", "/api/v1/staff/providers/" + provider + "/plans/" + retired.id() + "/deactivate",
                owner.accessToken(), null).expectStatus().isOk();

        ProviderDetail detail = send("GET", "/api/v1/discover/providers/" + provider + "?" + HERE,
                customer.accessToken(), null).expectStatus().isOk().expectBody(ProviderDetail.class)
                .returnResult().getResponseBody();
        assertThat(detail.plans()).extracting(p -> p.id()).containsExactly(live.id());
        assertThat(detail.provider().distanceKm()).isNotNull();
        assertThat(detail.provider().activePlanCount()).isEqualTo(1);
    }

    @Test
    void customerSubscribesThemselvesAndCannotDoubleBook() {
        Account owner = login("+919876620007");
        Account customer = login("+919876620008");
        UUID provider = createProvider(owner, "Subscribe " + UUID.randomUUID().toString().substring(0, 8), "VEG",
                "23.2330", "77.4340");
        PlanView plan = createPlan(owner, provider, "Monthly", "2500", 30);
        String url = "/api/v1/discover/providers/" + provider + "/subscribe";

        // No profile yet: a clear, fixable error.
        assertThat(send("POST", url, customer.accessToken(), Map.of("planId", plan.id()))
                .expectBody(String.class).returnResult().getStatus().value()).isEqualTo(400);

        send("PUT", "/api/v1/me/person", customer.accessToken(), Map.of("fullName", "Asha Verma"))
                .expectStatus().isOk();
        MySubscriptionView sub = send("POST", url, customer.accessToken(), Map.of("planId", plan.id()))
                .expectStatus().isCreated().expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        assertThat(sub.providerId()).isEqualTo(provider);
        assertThat(sub.terms().price()).isEqualByComparingTo(new BigDecimal("2500"));

        // Same plan over the same dates is an overlap; nothing new is created, and the membership is reused.
        assertThat(send("POST", url, customer.accessToken(), Map.of("planId", plan.id()))
                .expectBody(String.class).returnResult().getStatus().value()).isEqualTo(409);
        MySubscriptionView[] mine = send("GET", "/api/v1/subscriptions", customer.accessToken(), null)
                .expectStatus().isOk().expectBody(MySubscriptionView[].class).returnResult().getResponseBody();
        assertThat(mine).hasSize(1);
        Object[] memberships = send("GET", "/api/v1/me/memberships", customer.accessToken(), null)
                .expectStatus().isOk().expectBody(Object[].class).returnResult().getResponseBody();
        assertThat(memberships).hasSize(1);
    }

    @Test
    void portalSignInAndSignUpAreSeparateDoors() {
        String phone = "+919876620009";
        requestOtp(phone);

        // Sign-in without a registration is refused BEFORE the code is spent, so sign-up can reuse it.
        String code = otpSender.lastOtpFor(phone);
        assertThat(status(sendPublic("/api/v1/auth/customer/signin", new OtpVerifyRequest(phone, code))))
                .isEqualTo(404);
        AuthTokensResponse tokens = sendPublic("/api/v1/auth/customer/signup",
                new PortalSignUpRequest(phone, code, "Meera Iyer")).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        ProfileResponse profile = profile(tokens.accessToken());
        assertThat(profile.fullName()).isEqualTo("Meera Iyer");
        assertThat(profile.portals()).containsExactly("CUSTOMER");
        assertThat(profile.emailVerified()).isFalse();

        // A customer-only account cannot use the staff API.
        assertThat(status(send("GET", "/api/v1/staff/providers", tokens.accessToken(), null))).isEqualTo(403);

        // Signing up again on the same portal is a conflict; the other portal is a fresh registration.
        requestOtp(phone);
        String second = otpSender.lastOtpFor(phone);
        assertThat(status(sendPublic("/api/v1/auth/customer/signup", new PortalSignUpRequest(phone, second, "X Y"))))
                .isEqualTo(409);
        AuthTokensResponse staff = sendPublic("/api/v1/auth/staff/signup",
                new PortalSignUpRequest(phone, second, "Meera Iyer")).expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        assertThat(profile(staff.accessToken()).portals()).containsExactlyInAnyOrder("CUSTOMER", "STAFF");
        send("GET", "/api/v1/staff/providers", staff.accessToken(), null).expectStatus().isOk();

        assertThat(status(sendPublic("/api/v1/auth/nope/signin", new OtpVerifyRequest(phone, "123456"))))
                .isEqualTo(404);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String accessToken) {
    }

    private void requestOtp(String phone) {
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    }

    /** The original portal-less login: a new number is registered on both portals. */
    private Account login(String phone) {
        requestOtp(phone);
        AuthTokensResponse tokens = restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();
        return new Account(profile(tokens.accessToken()).accountId(), tokens.accessToken());
    }

    private ProfileResponse profile(String accessToken) {
        return restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
    }

    private UUID createProvider(Account owner, String name, String foodType, String lat, String lng) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("foodType", foodType);
        body.put("latitude", new BigDecimal(lat));
        body.put("longitude", new BigDecimal(lng));
        return send("POST", "/api/v1/staff/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    private PlanView createPlan(Account owner, UUID provider, String name, String price, int days) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("consumptionType", "DAY");
        body.put("durationDays", days);
        body.put("price", new BigDecimal(price));
        body.put("policy", Map.of("extensionAllowed", false, "sameDayAbsenceAllowed", true));
        return send("POST", "/api/v1/staff/providers/" + provider + "/plans", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private ProviderCard[] search(Account customer, String query) {
        return send("GET", "/api/v1/discover/providers" + query, customer.accessToken(), null)
                .expectStatus().isOk().expectBody(ProviderCard[].class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec sendPublic(String uri, Object body) {
        return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    private int status(RestTestClient.ResponseSpec spec) {
        return spec.expectBody(String.class).returnResult().getStatus().value();
    }

    /** token == null sends no Authorization header. */
    private RestTestClient.ResponseSpec send(String method, String uri, String token, Object body) {
        RestTestClient.RequestBodyUriSpec spec = restClient.method(HttpMethod.valueOf(method));
        RestTestClient.RequestBodySpec request = spec.uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            return request.body(body).exchange();
        }
        return request.exchange();
    }
}
