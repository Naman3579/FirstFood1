package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * V25 through the real API and database: the customer's vacation (no return date, resumed by the customer) and the
 * provider's closure (the owner's explicit decision whether subscriptions are extended). The day arithmetic itself is
 * covered without a database in {@link PauseRulesTest}; here we check who may do what, idempotency, the decisions and
 * that the database and the views agree. Phones are +91987691xxxx; the database is a shared singleton container, so do
 * not reuse that prefix elsewhere.
 */
class VacationAndClosureIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    // ============================================================ vacation

    @Test
    void aVacationCanBeScheduledAndCalledOffWithoutChangingThePlan() {
        Fixture f = fixture();
        MySubscriptionView sub = confirmed(f);
        LocalDate tomorrow = today().plusDays(1);

        Map<?, ?> pause = json(send("POST", "/api/v1/subscriptions/" + sub.id() + "/vacation", f.customer.token,
                Map.of("startDate", tomorrow.toString())).expectStatus().isCreated());
        assertThat(pause.get("status")).isEqualTo("OPEN");
        assertThat(pause.get("started")).isEqualTo(false);
        assertThat(mySubscription(f, sub.id()).pausedSince()).isEqualTo(tomorrow);

        Map<?, ?> called = json(send("POST", "/api/v1/subscriptions/" + sub.id() + "/vacation/resume", f.customer.token,
                Map.of()).expectStatus().isOk());
        assertThat(called.get("status")).isEqualTo("CANCELLED");
        MySubscriptionView after = mySubscription(f, sub.id());
        assertThat(after.pausedSince()).isNull();
        assertThat(after.effectiveExpiryDate()).isEqualTo(sub.effectiveExpiryDate());
    }

    @Test
    void aVacationStartedTodayIsOpenUntilTheCustomerResumesAndResumingTwiceAddsNothing() {
        Fixture f = fixture();
        MySubscriptionView sub = confirmed(f);
        String base = "/api/v1/subscriptions/" + sub.id() + "/vacation";

        send("POST", base, f.customer.token, Map.of()).expectStatus().isCreated();
        MySubscriptionView paused = mySubscription(f, sub.id());
        assertThat(paused.onVacation()).isTrue();
        assertThat(paused.phase()).isEqualTo(SubscriptionPhase.RUNNING);

        assertError(send("POST", base, f.customer.token, Map.of()), HttpStatus.CONFLICT, "VACATION_ALREADY_OPEN");

        // Resumed the same day it began: nothing was withheld, so nothing is given back.
        Map<?, ?> first = json(send("POST", base + "/resume", f.customer.token, Map.of()).expectStatus().isOk());
        assertThat(first.get("status")).isEqualTo("RESUMED");
        assertThat(first.get("creditedDays")).isEqualTo(0);
        Map<?, ?> again = json(send("POST", base + "/resume", f.customer.token, Map.of()).expectStatus().isOk());
        assertThat(again.get("id")).isEqualTo(first.get("id"));

        MySubscriptionView after = mySubscription(f, sub.id());
        assertThat(after.onVacation()).isFalse();
        assertThat(after.effectiveExpiryDate()).isEqualTo(sub.effectiveExpiryDate());
    }

    @Test
    void aSubscriptionTheProviderHasNotConfirmedCannotGoOnVacation() {
        Fixture f = fixture();
        MySubscriptionView pending = subscribe(f).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();

        assertError(send("POST", "/api/v1/subscriptions/" + pending.id() + "/vacation", f.customer.token, Map.of()),
                HttpStatus.CONFLICT, "VACATION_NOT_ALLOWED");
    }

    @Test
    void someoneElsesSubscriptionIsIndistinguishableFromAMissingOne() {
        Fixture f = fixture();
        MySubscriptionView sub = confirmed(f);
        Account stranger = customer();

        assertError(send("GET", "/api/v1/subscriptions/" + sub.id() + "/vacation", stranger.token, null),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", "/api/v1/subscriptions/" + sub.id() + "/vacation", stranger.token, Map.of()),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", "/api/v1/subscriptions/" + sub.id() + "/vacation/resume", stranger.token, Map.of()),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    void aVacationCannotStartInThePast() {
        Fixture f = fixture();
        MySubscriptionView sub = confirmed(f);

        assertError(send("POST", "/api/v1/subscriptions/" + sub.id() + "/vacation", f.customer.token,
                Map.of("startDate", today().minusDays(1).toString())), HttpStatus.BAD_REQUEST, "VACATION_START_INVALID");
    }

    // ============================================================ closure

    @Test
    void aClosureWithExtensionAddsTheClosedDaysToAConfirmedSubscriptionAndIsIdempotent() {
        Fixture f = fixture();
        MySubscriptionView sub = confirmed(f);
        LocalDate from = today().plusDays(2);
        LocalDate to = today().plusDays(4); // 3 days
        Map<String, Object> body = Map.of("startDate", from.toString(), "endDate", to.toString(), "reason", "Festival",
                "extendSubscriptions", true);

        Map<?, ?> preview = json(send("GET", closures(f) + "/preview?from=" + from + "&to=" + to, f.owner.token, null)
                .expectStatus().isOk());
        assertThat(preview.get("affectedCount")).isEqualTo(1);
        assertThat(preview.get("totalDays")).isEqualTo(3);
        // A preview writes nothing.
        assertThat(mySubscription(f, sub.id()).effectiveExpiryDate()).isEqualTo(sub.effectiveExpiryDate());

        Map<?, ?> created = json(send("POST", closures(f), f.owner.token, body).expectStatus().isCreated());
        assertThat(created.get("totalDaysApplied")).isEqualTo(3);
        assertThat(mySubscription(f, sub.id()).effectiveExpiryDate()).isEqualTo(sub.effectiveExpiryDate().plusDays(3));

        // The same declaration again: 200, the recorded closure, no second extension.
        Map<?, ?> again = json(send("POST", closures(f), f.owner.token, body).expectStatus().isOk());
        assertThat(again.get("id")).isEqualTo(created.get("id"));
        assertThat(mySubscription(f, sub.id()).effectiveExpiryDate()).isEqualTo(sub.effectiveExpiryDate().plusDays(3));

        // A closure that extended subscriptions cannot be cancelled.
        assertError(send("POST", closures(f) + "/" + created.get("id") + "/cancel", f.owner.token, Map.of()),
                HttpStatus.CONFLICT, "CLOSURE_HAS_EXTENSIONS");
    }

    @Test
    void aSubscriptionNobodyConfirmedIsNotExtended() {
        Fixture f = fixture();
        MySubscriptionView pending = subscribe(f).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        LocalDate from = today().plusDays(2);

        Map<?, ?> created = json(send("POST", closures(f), f.owner.token,
                Map.of("startDate", from.toString(), "endDate", from.plusDays(1).toString(), "extendSubscriptions", true))
                .expectStatus().isCreated());

        assertThat(created.get("affectedCount")).isEqualTo(0);
        assertThat(mySubscription(f, pending.id()).effectiveExpiryDate()).isEqualTo(pending.effectiveExpiryDate());
    }

    @Test
    void theDecisionToExtendHasNoDefaultAndCannotBeChangedByRepeatingTheDeclaration() {
        Fixture f = fixture();
        LocalDate from = today().plusDays(2);
        Map<String, Object> noDecision = new HashMap<>(Map.of("startDate", from.toString(), "endDate", from.toString()));
        assertError(send("POST", closures(f), f.owner.token, noDecision), HttpStatus.BAD_REQUEST,
                "CLOSURE_DECISION_REQUIRED");

        send("POST", closures(f), f.owner.token,
                Map.of("startDate", from.toString(), "endDate", from.toString(), "extendSubscriptions", false))
                .expectStatus().isCreated();
        assertError(send("POST", closures(f), f.owner.token,
                Map.of("startDate", from.toString(), "endDate", from.toString(), "extendSubscriptions", true)),
                HttpStatus.CONFLICT, "CLOSURE_DECISION_CONFLICT");
    }

    @Test
    void aRecordedOnlyClosureCanBeCancelledAndTwoLiveClosuresCannotOverlap() {
        Fixture f = fixture();
        LocalDate from = today().plusDays(5);
        Map<?, ?> first = json(send("POST", closures(f), f.owner.token,
                Map.of("startDate", from.toString(), "endDate", from.plusDays(1).toString(), "extendSubscriptions", false))
                .expectStatus().isCreated());

        assertError(send("POST", closures(f), f.owner.token,
                Map.of("startDate", from.plusDays(1).toString(), "endDate", from.plusDays(2).toString(),
                        "extendSubscriptions", false)), HttpStatus.CONFLICT, "CLOSURE_OVERLAP");

        Map<?, ?> cancelled = json(send("POST", closures(f) + "/" + first.get("id") + "/cancel", f.owner.token, Map.of())
                .expectStatus().isOk());
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void aPersonOutsideTheProviderTeamCannotDeclareOrListClosuresAndLearnsNothingAboutTheProvider() {
        Fixture f = fixture();
        LocalDate from = today().plusDays(2);

        // Same convention as the rest of the staff API: a non-member gets 404, not 403, so provider ids are not revealed.
        assertError(send("POST", closures(f), f.customer.token,
                Map.of("startDate", from.toString(), "endDate", from.toString(), "extendSubscriptions", true)),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertError(send("GET", closures(f), f.customer.token, null), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");

        // ... and nothing was recorded.
        Map<?, ?> none = json(send("GET", closures(f) + "/preview?from=" + from + "&to=" + from, f.owner.token, null)
                .expectStatus().isOk());
        assertThat(none.get("affectedCount")).isEqualTo(0);
        assertThat(send("GET", closures(f), f.owner.token, null).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody()).isEqualTo("[]");
    }

    @Test
    void aClosureOfOneProviderNeverTouchesAnotherProvidersSubscriptions() {
        Fixture a = fixture();
        Fixture b = fixture();
        MySubscriptionView subOfB = confirmed(b);
        LocalDate from = today().plusDays(2);

        send("POST", closures(a), a.owner.token,
                Map.of("startDate", from.toString(), "endDate", from.plusDays(2).toString(), "extendSubscriptions", true))
                .expectStatus().isCreated();

        assertThat(mySubscription(b, subOfB.id()).effectiveExpiryDate()).isEqualTo(subOfB.effectiveExpiryDate());
    }

    // ============================================================ helpers

    private record Account(UUID id, String phone, String token) {
    }

    private record Fixture(Account owner, Account customer, UUID provider, PlanView plan) {
    }

    private Fixture fixture() {
        Account owner = login();
        UUID provider = createProvider(owner);
        return new Fixture(owner, customer(), provider, createPlan(owner, provider));
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    private static String closures(Fixture f) {
        return "/api/v1/staff/providers/" + f.provider + "/closures";
    }

    private RestTestClient.ResponseSpec subscribe(Fixture f) {
        return send("POST", "/api/v1/discover/providers/" + f.provider + "/subscribe", f.customer.token,
                Map.of("planId", f.plan.id()));
    }

    /** A subscription the customer bought and the owner confirmed (a vacation and a closure apply only to those). */
    private MySubscriptionView confirmed(Fixture f) {
        MySubscriptionView sub = subscribe(f).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        send("POST", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id() + "/verify", f.owner.token, Map.of())
                .expectStatus().isOk();
        return mySubscription(f, sub.id());
    }

    private MySubscriptionView mySubscription(Fixture f, UUID id) {
        return send("GET", "/api/v1/subscriptions/" + id, f.customer.token, null).expectStatus().isOk()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
    }

    private static Map<?, ?> json(RestTestClient.ResponseSpec spec) {
        return spec.expectBody(Map.class).returnResult().getResponseBody();
    }

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        String body = spec.expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"" + code + "\"");
    }

    private UUID createProvider(Account owner) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Vacation " + UUID.randomUUID().toString().substring(0, 8));
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("foodType", "VEG");
        body.put("latitude", new BigDecimal("23.2330"));
        body.put("longitude", new BigDecimal("77.4340"));
        return send("POST", "/api/v1/staff/providers", owner.token, body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult().getResponseBody().id();
    }

    private PlanView createPlan(Account owner, UUID provider) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Monthly");
        body.put("consumptionType", "DAY");
        body.put("durationDays", 30);
        body.put("price", new BigDecimal("2500"));
        body.put("policy", Map.of("extensionAllowed", false, "sameDayAbsenceAllowed", true));
        return send("POST", "/api/v1/staff/providers/" + provider + "/plans", owner.token, body)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private Account customer() {
        Account account = login();
        send("PUT", "/api/v1/me/person", account.token, Map.of("fullName", "Customer " + account.phone().substring(9)))
                .expectStatus().isOk();
        return account;
    }

    private Account login() {
        String phone = String.format("+91987691%04d", PHONE_COUNTER.getAndIncrement());
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
        return new Account(profile.accountId(), phone, tokens.accessToken());
    }

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
