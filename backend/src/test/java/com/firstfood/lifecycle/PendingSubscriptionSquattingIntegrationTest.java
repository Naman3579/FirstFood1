package com.firstfood.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.subscription.MySubscriptionView;
import com.firstfood.subscription.VerificationStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Audit F-06: unpaid self-service requests must not be able to squat a provider's capacity. A customer's own purchase is
 * stored ACTIVE while it waits for staff; before the fix it held a seat (and its dates) until staff rejected it by hand.
 * Now only confirmed subscriptions and FRESH requests hold a seat, one account may wait on only a few requests, and an
 * hourly job closes requests nobody confirmed. Phones are +91987680xxxx; the database is a shared singleton container, so
 * do not reuse that prefix elsewhere.
 *
 * Time is moved the way the other lifecycle tests do it: the request is made through the API and then pushed into the
 * past through SQL (the immutability trigger is switched off for that one statement only).
 */
class PendingSubscriptionSquattingIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final Duration TTL = Duration.ofHours(48); // app.subscription.pending-ttl default

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRunner runner;

    @Autowired
    PendingSubscriptionExpiryJob pendingExpiryJob;

    // ============================================================ capacity

    @Test
    void anUnconfirmedRequestHoldsASeatOnlyWhileItIsFresh() {
        Account owner = login();
        UUID provider = createProvider(owner, 1);
        PlanView plan = createPlan(owner, provider);
        Account squatter = customer();
        Account honest = customer();

        MySubscriptionView request = subscribe(squatter, provider, plan).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        assertThat(request.verification().status()).isEqualTo(VerificationStatus.PENDING);

        // Fresh: the seat is taken, so a real customer is turned away (this is the capacity the request is entitled to).
        assertError(subscribe(honest, provider, plan), HttpStatus.CONFLICT, "PROVIDER_AT_CAPACITY");

        // Past the TTL and STILL PENDING (the job has not run): it no longer holds the seat.
        backdateRequest(request.id(), TTL.plusHours(1));
        assertThat(verificationStatus(request.id())).isEqualTo("PENDING");
        subscribe(honest, provider, plan).expectStatus().isCreated();
    }

    @Test
    void aConfirmedSubscriptionAlwaysHoldsItsSeatHoweverOldTheRequest() {
        Account owner = login();
        UUID provider = createProvider(owner, 1);
        PlanView plan = createPlan(owner, provider);
        Account first = customer();
        Account second = customer();

        MySubscriptionView request = subscribe(first, provider, plan).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        send("POST", "/api/v1/providers/" + provider + "/subscriptions/" + request.id() + "/verify",
                owner.accessToken(), Map.of()).expectStatus().isOk();
        backdateRequest(request.id(), Duration.ofDays(10));

        assertError(subscribe(second, provider, plan), HttpStatus.CONFLICT, "PROVIDER_AT_CAPACITY");
    }

    // ============================================================ the expiry job

    @Test
    void theJobClosesAStaleRequestAndFreesItsDatesAndSeat() {
        Account owner = login();
        UUID provider = createProvider(owner, 5);
        PlanView plan = createPlan(owner, provider);
        Account customer = customer();
        MySubscriptionView request = subscribe(customer, provider, plan).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        backdateRequest(request.id(), TTL.plusMinutes(5));

        JobRunResult result = runner.run(pendingExpiryJob);

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(result.changed()).isGreaterThanOrEqualTo(1);
        assertThat(verificationStatus(request.id())).isEqualTo("EXPIRED");
        assertThat(subscriptionStatus(request.id())).isEqualTo("CANCELLED");
        // The schema requires a canceller: it is the customer's own request, closed automatically, and says so.
        assertThat(jdbc.queryForObject("select cancelled_by from subscription where id = ?", UUID.class, request.id()))
                .isEqualTo(customer.id());
        assertThat(jdbc.queryForObject("select cancellation_reason from subscription where id = ?", String.class,
                request.id())).startsWith("Cancelled automatically");
        // A system decision: no staff member, no reason text, a decision time.
        Map<String, Object> row = jdbc.queryForMap(
                "select decided_by_account_id, decided_at, rejection_reason from subscription_verification"
                        + " where subscription_id = ?", request.id());
        assertThat(row.get("decided_by_account_id")).isNull();
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("rejection_reason")).isNull();

        // Its dates are free again, so the customer can simply ask again.
        MySubscriptionView again = subscribe(customer, provider, plan).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        assertThat(again.id()).isNotEqualTo(request.id());
        assertThat(again.verification().status()).isEqualTo(VerificationStatus.PENDING);
    }

    @Test
    void theJobLeavesFreshConfirmedAndRejectedRequestsAlone() {
        Account owner = login();
        UUID provider = createProvider(owner, 10);
        PlanView plan = createPlan(owner, provider);
        MySubscriptionView fresh = subscribeOk(customer(), provider, plan);
        MySubscriptionView confirmed = subscribeOk(customer(), provider, plan);
        MySubscriptionView rejected = subscribeOk(customer(), provider, plan);
        send("POST", "/api/v1/providers/" + provider + "/subscriptions/" + confirmed.id() + "/verify",
                owner.accessToken(), Map.of()).expectStatus().isOk();
        send("POST", "/api/v1/providers/" + provider + "/subscriptions/" + rejected.id() + "/reject",
                owner.accessToken(), Map.of("reason", "No payment received")).expectStatus().isOk();
        backdateRequest(confirmed.id(), Duration.ofDays(10));
        backdateRequest(rejected.id(), Duration.ofDays(10));
        backdateRequest(fresh.id(), Duration.ofHours(47));

        runner.run(pendingExpiryJob);

        assertThat(verificationStatus(fresh.id())).isEqualTo("PENDING");
        assertThat(subscriptionStatus(fresh.id())).isEqualTo("ACTIVE");
        assertThat(verificationStatus(confirmed.id())).isEqualTo("VERIFIED");
        assertThat(subscriptionStatus(confirmed.id())).isEqualTo("ACTIVE");
        assertThat(verificationStatus(rejected.id())).isEqualTo("REJECTED");
        assertThat(subscriptionStatus(rejected.id())).isEqualTo("CANCELLED");
    }

    @Test
    void aRequestStaffAlreadyCancelledIsClosedWithoutTouchingTheSubscriptionAgain() {
        Account owner = login();
        UUID provider = createProvider(owner, 10);
        PlanView plan = createPlan(owner, provider);
        MySubscriptionView request = subscribeOk(customer(), provider, plan);
        send("POST", "/api/v1/providers/" + provider + "/subscriptions/" + request.id() + "/cancel",
                owner.accessToken(), Map.of("reason", "Customer asked")).expectStatus().isOk();
        Instant cancelledAt = cancelledAt(request.id());
        backdateRequest(request.id(), TTL.plusHours(2));

        runner.run(pendingExpiryJob);

        assertThat(verificationStatus(request.id())).isEqualTo("EXPIRED");
        assertThat(subscriptionStatus(request.id())).isEqualTo("CANCELLED");
        assertThat(cancelledAt(request.id())).isEqualTo(cancelledAt);
        assertThat(jdbc.queryForObject("select cancellation_reason from subscription where id = ?", String.class,
                request.id())).isEqualTo("Customer asked");
    }

    @Test
    void runningTheJobAgainChangesNothing() {
        Account owner = login();
        UUID provider = createProvider(owner, 10);
        PlanView plan = createPlan(owner, provider);
        MySubscriptionView request = subscribeOk(customer(), provider, plan);
        backdateRequest(request.id(), TTL.plusHours(2));

        runner.run(pendingExpiryJob);
        Instant decidedAt = decidedAt(request.id());
        runner.run(pendingExpiryJob);
        JobRunResult third = runner.run(pendingExpiryJob);

        assertThat(third.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(third.failed()).isZero();
        assertThat(verificationStatus(request.id())).isEqualTo("EXPIRED");
        assertThat(decidedAt(request.id())).isEqualTo(decidedAt);
    }

    @Test
    void staffCannotConfirmARequestTheSystemHasAlreadyClosed() {
        Account owner = login();
        UUID provider = createProvider(owner, 10);
        PlanView plan = createPlan(owner, provider);
        MySubscriptionView request = subscribeOk(customer(), provider, plan);
        backdateRequest(request.id(), TTL.plusHours(2));
        runner.run(pendingExpiryJob);

        assertError(send("POST", "/api/v1/providers/" + provider + "/subscriptions/" + request.id() + "/verify",
                owner.accessToken(), Map.of()), HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_PENDING");
    }

    // ============================================================ per-account cap

    @Test
    void oneAccountCannotWaitOnMoreThanTheCapAtOnce() {
        Account customer = customer();
        Account owner = login();
        UUID[] providers = new UUID[4];
        PlanView[] plans = new PlanView[4];
        for (int i = 0; i < 4; i++) {
            providers[i] = createProvider(owner, null);
            plans[i] = createPlan(owner, providers[i]);
        }
        MySubscriptionView first = null;
        for (int i = 0; i < 3; i++) {
            MySubscriptionView s = subscribeOk(customer, providers[i], plans[i]);
            first = first == null ? s : first;
        }

        assertError(subscribe(customer, providers[3], plans[3]), HttpStatus.CONFLICT,
                "TOO_MANY_PENDING_SUBSCRIPTIONS");

        // Once staff confirm one it is no longer "waiting", and the customer may ask for another.
        send("POST", "/api/v1/providers/" + providers[0] + "/subscriptions/" + first.id() + "/verify",
                owner.accessToken(), Map.of()).expectStatus().isOk();
        subscribe(customer, providers[3], plans[3]).expectStatus().isCreated();
    }

    @Test
    void requestsPastTheTtlDoNotCountTowardTheCap() {
        Account customer = customer();
        Account owner = login();
        UUID[] providers = new UUID[4];
        PlanView[] plans = new PlanView[4];
        MySubscriptionView[] made = new MySubscriptionView[3];
        for (int i = 0; i < 4; i++) {
            providers[i] = createProvider(owner, null);
            plans[i] = createPlan(owner, providers[i]);
            if (i < 3) {
                made[i] = subscribeOk(customer, providers[i], plans[i]);
            }
        }
        for (MySubscriptionView s : made) {
            backdateRequest(s.id(), TTL.plusHours(1)); // stale, job has NOT run
        }

        subscribe(customer, providers[3], plans[3]).expectStatus().isCreated();
    }

    // ============================================================ what the customer sees

    @Test
    void aPendingRequestTellsTheCustomerWhenItLapses() {
        Account owner = login();
        UUID provider = createProvider(owner, null);
        PlanView plan = createPlan(owner, provider);
        MySubscriptionView request = subscribeOk(customer(), provider, plan);

        assertThat(request.verification().expiresAt()).isNotNull();
        assertThat(Duration.between(request.verification().requestedAt(), request.verification().expiresAt()))
                .isEqualTo(TTL);
    }

    // ============================================================ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    private RestTestClient.ResponseSpec subscribe(Account customer, UUID provider, PlanView plan) {
        return send("POST", "/api/v1/discover/providers/" + provider + "/subscribe", customer.accessToken(),
                Map.of("planId", plan.id()));
    }

    private MySubscriptionView subscribeOk(Account customer, UUID provider, PlanView plan) {
        return subscribe(customer, provider, plan).expectStatus().isCreated()
                .expectBody(MySubscriptionView.class).returnResult().getResponseBody();
    }

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        String body = spec.expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"" + code + "\"");
    }

    /** Moves a request's creation time into the past (the immutability trigger is off for this one statement only). */
    private void backdateRequest(UUID subscriptionId, Duration by) {
        jdbc.execute("alter table subscription_verification disable trigger subscription_verification_no_identity_change");
        try {
            jdbc.update("update subscription_verification set requested_at = requested_at - (? * interval '1 second')"
                    + " where subscription_id = ?", by.toSeconds(), subscriptionId);
        } finally {
            jdbc.execute("alter table subscription_verification enable trigger subscription_verification_no_identity_change");
        }
    }

    private String verificationStatus(UUID subscriptionId) {
        return jdbc.queryForObject("select verification_status from subscription_verification where subscription_id = ?",
                String.class, subscriptionId);
    }

    private String subscriptionStatus(UUID subscriptionId) {
        return jdbc.queryForObject("select status from subscription where id = ?", String.class, subscriptionId);
    }

    private Instant cancelledAt(UUID subscriptionId) {
        return jdbc.queryForObject("select cancelled_at from subscription where id = ?", Timestamp.class, subscriptionId)
                .toInstant();
    }

    private Instant decidedAt(UUID subscriptionId) {
        return jdbc.queryForObject("select decided_at from subscription_verification where subscription_id = ?",
                Timestamp.class, subscriptionId).toInstant();
    }

    /** A provider owner; the same account may own several providers. */
    private UUID createProvider(Account owner, Integer maxActive) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Squat " + UUID.randomUUID().toString().substring(0, 8));
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("foodType", "VEG");
        body.put("latitude", new BigDecimal("23.2330"));
        body.put("longitude", new BigDecimal("77.4340"));
        if (maxActive != null) {
            body.put("maxActiveSubscriptions", maxActive);
        }
        return send("POST", "/api/v1/staff/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult().getResponseBody().id();
    }

    private PlanView createPlan(Account owner, UUID provider) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Monthly");
        body.put("consumptionType", "DAY");
        body.put("durationDays", 30);
        body.put("price", new BigDecimal("2500"));
        body.put("policy", Map.of("extensionAllowed", false, "sameDayAbsenceAllowed", true));
        return send("POST", "/api/v1/staff/providers/" + provider + "/plans", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    /** A customer with a profile name (a self-purchase needs one). */
    private Account customer() {
        Account account = login();
        send("PUT", "/api/v1/me/person", account.accessToken(), Map.of("fullName", "Customer " + account.phone().substring(9)))
                .expectStatus().isOk();
        return account;
    }

    private Account login() {
        String phone = String.format("+91987680%04d", PHONE_COUNTER.getAndIncrement());
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
