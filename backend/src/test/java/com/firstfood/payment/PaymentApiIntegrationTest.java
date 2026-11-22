package com.firstfood.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.membership.CustomerView;
import com.firstfood.membership.MyMembershipView;
import com.firstfood.membership.InvitationView;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.subscription.SubscriptionView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 11 HTTP-level tests: payments against a subscription (V17). Real Postgres + Redis, real OTP login.
 * Phones are generated as +91987665xxxx; the database is a shared singleton container, so do not reuse that
 * prefix elsewhere.
 *
 * Plan price is 4800 unless a test says otherwise, so the instalment arithmetic matches the product examples.
 */
class PaymentApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger REF_COUNTER = new AtomicInteger(1);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------ authentication

    @Test
    void paymentEndpointsRequireAuthentication() {
        UUID id = UUID.randomUUID();
        String mine = "/api/v1/subscriptions/" + id + "/payments";
        String staff = "/api/v1/providers/" + id + "/subscriptions/" + id + "/payments";
        for (String[] call : new String[][] {
                {"GET", mine}, {"POST", mine}, {"GET", staff}, {"POST", staff + "/" + id + "/verify"},
                {"POST", staff + "/" + id + "/reject"}}) {
            Object body = call[0].equals("GET") ? null : Map.of();
            assertThat(statusOf(send(call[0], call[1], null, body))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ submitting

    @Test
    void aSubmittedPaymentIsPendingAndDoesNotReduceTheOutstandingBalance() {
        Fixture f = fixture();
        String ref = uniqueRef();

        PaymentView p = submit(f.customer, f.subscription, upi(ref, "2000"));

        assertThat(p.verificationStatus()).isEqualTo(PaymentVerificationStatus.PENDING);
        assertThat(p.subscriberName()).isEqualTo(f.customerName);
        assertThat(p.transactionReference()).isEqualTo(ref);
        assertThat(p.amount()).isEqualByComparingTo("2000.00");
        assertThat(p.currency()).isEqualTo("INR");
        assertThat(p.paymentMethod()).isEqualTo(PaymentMethod.UPI);
        // The customer's claim and the server's own timestamp are different things.
        assertThat(p.paymentDate()).isEqualTo(yesterday());
        assertThat(p.submittedAt()).isNotNull();
        assertThat(p.verifiedAt()).isNull();
        assertThat(p.verifiedByAccountId()).isNull();

        PaymentLedgerView ledger = myLedger(f.customer, f.subscription);
        assertThat(ledger.price()).isEqualByComparingTo("4800.00");
        assertThat(ledger.confirmedPaid()).isEqualByComparingTo("0");
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("2000");
        assertThat(ledger.outstanding()).isEqualByComparingTo("4800");
        assertThat(ledger.remainingToSubmit()).isEqualByComparingTo("2800");
        assertThat(ledger.payments()).extracting(PaymentView::id).containsExactly(p.id());
        assertThat(eventTypes(p.id())).containsExactly("SUBMITTED");
    }

    @Test
    void installmentsAreSeparateRecordsAndOnlyVerifiedOnesCountAsPaid() {
        Fixture f = fixture();
        PaymentView a = submit(f.customer, f.subscription, upi(uniqueRef(), "2000"));
        PaymentView b = submit(f.customer, f.subscription, upi(uniqueRef(), "1000"));
        PaymentView c = submit(f.customer, f.subscription, bank(uniqueRef(), "800"));

        verify(f.owner, f.provider, f.subscription, a.id());
        verify(f.owner, f.provider, f.subscription, c.id());

        // The product example: 4800 price, A 2000 verified, B 1000 pending, C 800 verified.
        PaymentLedgerView ledger = staffLedger(f.owner, f.provider, f.subscription);
        assertThat(ledger.payments()).hasSize(3);
        assertThat(ledger.confirmedPaid()).isEqualByComparingTo("2800");
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("1000");
        assertThat(ledger.outstanding()).isEqualByComparingTo("2000");
        assertThat(ledger.payments()).filteredOn(p -> p.id().equals(b.id()))
                .extracting(PaymentView::verificationStatus).containsExactly(PaymentVerificationStatus.PENDING);

        // The customer sees the same balance.
        PaymentLedgerView mine = myLedger(f.customer, f.subscription);
        assertThat(mine.confirmedPaid()).isEqualByComparingTo("2800");
        assertThat(mine.outstanding()).isEqualByComparingTo("2000");
    }

    @Test
    void aPaymentCanBeAddedLongAfterTheSubscriptionWasTaken() {
        Fixture f = fixture();
        // The subscription exists and has no payments at all yet: the customer pays later.
        assertThat(myLedger(f.customer, f.subscription).payments()).isEmpty();
        assertThat(myLedger(f.customer, f.subscription).outstanding()).isEqualByComparingTo("4800");

        PaymentView later = submit(f.customer, f.subscription, upi(uniqueRef(), "4800"));
        verify(f.owner, f.provider, f.subscription, later.id());

        PaymentLedgerView ledger = myLedger(f.customer, f.subscription);
        assertThat(ledger.outstanding()).isEqualByComparingTo("0");
        assertThat(ledger.remainingToSubmit()).isEqualByComparingTo("0");
    }

    @Test
    void cashMayHaveNoReferenceButDigitalPaymentsMust() {
        Fixture f = fixture();

        PaymentView cash = submit(f.customer, f.subscription, body("CASH", null, "500"));
        assertThat(cash.transactionReference()).isNull();
        assertThat(cash.paymentMethod()).isEqualTo(PaymentMethod.CASH);

        assertError(send("POST", myUrl(f.subscription), f.customer.accessToken(), body("UPI", null, "100")),
                HttpStatus.BAD_REQUEST, "PAYMENT_REFERENCE_REQUIRED");
        assertError(send("POST", myUrl(f.subscription), f.customer.accessToken(), body("BANK_TRANSFER", "   ", "100")),
                HttpStatus.BAD_REQUEST, "PAYMENT_REFERENCE_REQUIRED");
    }

    @Test
    void invalidAmountsReferencesAndTimesAreRejected() {
        Fixture f = fixture();
        String url = myUrl(f.subscription);

        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi(uniqueRef(), "0")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi(uniqueRef(), "-5")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi(uniqueRef(), "10.505")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi("<script>", "10")))).isEqualTo(400);
        // Per-method formats: a UPI UTR is 12 digits, a bank reference 8-35 letters/digits.
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi("12345", "10")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi("12345678901A", "10")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), upi("---", "10")))).isEqualTo(400);
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), bank("AB12", "10")))).isEqualTo(400);

        Map<String, Object> future = upi(uniqueRef(), "10");
        future.put("paymentDate", LocalDate.now(BUSINESS_ZONE).plusDays(1).toString());
        assertError(send("POST", url, f.customer.accessToken(), future), HttpStatus.BAD_REQUEST,
                "PAYMENT_TIME_IN_FUTURE");

        Map<String, Object> noMethod = upi(uniqueRef(), "10");
        noMethod.remove("method");
        assertThat(statusOf(send("POST", url, f.customer.accessToken(), noMethod))).isEqualTo(400);

        assertThat(myLedger(f.customer, f.subscription).payments()).isEmpty();
    }

    @Test
    void paymentsCannotExceedWhatIsStillUnpaid() {
        Fixture f = fixture();
        submit(f.customer, f.subscription, upi(uniqueRef(), "4000"));

        assertError(send("POST", myUrl(f.subscription), f.customer.accessToken(), upi(uniqueRef(), "800.01")),
                HttpStatus.CONFLICT, "PAYMENT_EXCEEDS_OUTSTANDING");
        // Exactly the remainder is fine.
        submit(f.customer, f.subscription, upi(uniqueRef(), "800"));
        assertError(send("POST", myUrl(f.subscription), f.customer.accessToken(), upi(uniqueRef(), "0.01")),
                HttpStatus.CONFLICT, "PAYMENT_EXCEEDS_OUTSTANDING");
    }

    // ------------------------------------------------------------ duplicates and idempotency

    @Test
    void aDigitalReferenceCanBeUsedOnlyOnceAcrossTheWholeSystem() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();
        String ref = uniqueRef();
        submit(f1.customer, f1.subscription, upi(ref, "100"));

        // Same customer, other subscription's customer, different case / padding: all the same UTR.
        assertError(send("POST", myUrl(f1.subscription), f1.customer.accessToken(), upi(ref, "100")),
                HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        assertError(send("POST", myUrl(f2.subscription), f2.customer.accessToken(),
                upi("  " + ref + " ", "100")), HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
    }

    @Test
    void insertedSpacesAndSeparatorsDoNotMakeAnotherReference() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();
        String ref = uniqueRef();
        PaymentView first = submit(f1.customer, f1.subscription, upi(ref, "100"));

        String spaced = ref.substring(0, 4) + " " + ref.substring(4, 8) + " " + ref.substring(8);
        String dashed = ref.substring(0, 6) + "-" + ref.substring(6);
        String slashed = ref.substring(0, 3) + "/" + ref.substring(3);
        for (String variant : new String[] {spaced, dashed, ref.substring(0, 5) + "." + ref.substring(5), slashed}) {
            assertError(send("POST", myUrl(f2.subscription), f2.customer.accessToken(), upi(variant, "100")),
                    HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        }
        assertThat(myLedger(f2.customer, f2.subscription).payments()).isEmpty();
        // What is stored is the clean form, however it was typed.
        PaymentView typedWithSpaces = submit(f2.customer, f2.subscription, upi(uniqueRefSpaced(), "100"));
        assertThat(typedWithSpaces.transactionReference()).matches("[0-9]{12}");
        assertThat(first.transactionReference()).isEqualTo(ref);
    }

    @Test
    void oneRealTransferCannotBeClaimedAgainUnderAnotherMethod() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();
        String ref = uniqueRef();
        submit(f1.customer, f1.subscription, upi(ref, "100"));

        // A 12-digit number is a valid IMPS reference too - but it is the same transfer.
        assertError(send("POST", myUrl(f2.subscription), f2.customer.accessToken(), bank(ref, "100")),
                HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        assertError(send("POST", myUrl(f2.subscription), f2.customer.accessToken(), bank(ref.substring(0, 6) + " "
                + ref.substring(6), "100")), HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        assertThat(myLedger(f2.customer, f2.subscription).payments()).isEmpty();
    }

    @Test
    void bankReferencesAreComparedWithoutCaseSpacesOrSeparators() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();
        String neft = "SBIN" + uniqueRef();
        PaymentView p = submit(f1.customer, f1.subscription, bank(neft, "100"));
        assertThat(p.transactionReference()).isEqualTo(neft);

        assertError(send("POST", myUrl(f2.subscription), f2.customer.accessToken(),
                bank(neft.toLowerCase().substring(0, 4) + " " + neft.substring(4, 10) + "-" + neft.substring(10),
                        "100")), HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
    }

    @Test
    void theDatabaseKeyMatchesTheJavaCanonicalForm() {
        Fixture f = fixture();
        String ref = uniqueRef();
        PaymentView upi = submit(f.customer, f.subscription, upi(ref, "100"));
        PaymentView cash = submit(f.customer, f.subscription, body("CASH", "r-0 1.a/b", "100"));

        String upiKey = jdbc.queryForObject("select reference_key from subscription_payment where id = ?",
                String.class, upi.id());
        String cashKey = jdbc.queryForObject("select reference_key from subscription_payment where id = ?",
                String.class, cash.id());
        assertThat(upiKey).isEqualTo(PaymentReference.canonical(ref));
        assertThat(cashKey).isEqualTo(PaymentReference.canonical("r-0 1.a/b")).isEqualTo("R01AB");
        // Cash keeps the receipt number as written.
        assertThat(cash.transactionReference()).isEqualTo("r-0 1.a/b");
    }

    @Test
    void cashReceiptNumbersAreOnlyUniqueWithinOneProvider() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();
        String receipt = "R-" + uniqueRef();
        submit(f1.customer, f1.subscription, body("CASH", receipt, "100"));

        assertError(send("POST", myUrl(f1.subscription), f1.customer.accessToken(), body("CASH", receipt, "100")),
                HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        assertError(send("POST", myUrl(f1.subscription), f1.customer.accessToken(),
                body("CASH", receipt.toLowerCase().replace("-", " - "), "100")),
                HttpStatus.CONFLICT, "PAYMENT_REFERENCE_DUPLICATE");
        // Another provider has its own receipt book.
        submit(f2.customer, f2.subscription, body("CASH", receipt, "100"));
    }

    @Test
    void retryingWithTheSameKeyReturnsTheFirstPaymentInsteadOfCreatingASecond() {
        Fixture f = fixture();
        Map<String, Object> request = upi(uniqueRef(), "300");
        request.put("idempotencyKey", "key-" + UUID.randomUUID());

        PaymentView first = submit(f.customer, f.subscription, request);
        PaymentView retry = submit(f.customer, f.subscription, request);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(paymentRows(f.subscription)).isEqualTo(1);
        assertThat(myLedger(f.customer, f.subscription).pendingAmount()).isEqualByComparingTo("300");

    }

    @Test
    void parallelRetriesOfOneKeyCreateOnePayment() throws Exception {
        Fixture f = fixture();
        Map<String, Object> request = upi(uniqueRef(), "300");
        request.put("idempotencyKey", "par-" + UUID.randomUUID());

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", myUrl(f.subscription), f.customer.accessToken(), request)),
                () -> statusOf(send("POST", myUrl(f.subscription), f.customer.accessToken(), request)),
                () -> statusOf(send("POST", myUrl(f.subscription), f.customer.accessToken(), request)));

        assertThat(statuses).allMatch(s -> s == 201);
        assertThat(paymentRows(f.subscription)).isEqualTo(1);
    }

    @Test
    void parallelSubmissionsCannotTogetherOverpay() throws Exception {
        Fixture f = fixture();
        submit(f.customer, f.subscription, upi(uniqueRef(), "3500"));

        // 1300 left; two parallel 1000s cannot both fit.
        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", myUrl(f.subscription), f.customer.accessToken(), upi(uniqueRef(), "1000"))),
                () -> statusOf(send("POST", myUrl(f.subscription), f.customer.accessToken(), upi(uniqueRef(), "1000"))));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(myLedger(f.customer, f.subscription).pendingAmount()).isEqualByComparingTo("4500");
    }

    // ------------------------------------------------------------ verifying and rejecting

    @Test
    void verifyingRecordsWhoAndWhenAndIsAudited() {
        Fixture f = fixture();
        PaymentView p = submit(f.customer, f.subscription, upi(uniqueRef(), "1000"));

        PaymentView verified = verify(f.owner, f.provider, f.subscription, p.id());

        assertThat(verified.verificationStatus()).isEqualTo(PaymentVerificationStatus.VERIFIED);
        assertThat(verified.verifiedAt()).isNotNull();
        assertThat(verified.verifiedByAccountId()).isEqualTo(f.owner.id());
        assertThat(eventTypes(p.id())).containsExactly("SUBMITTED", "VERIFIED");
        // The customer sees the outcome but not which staff account decided.
        assertThat(myLedger(f.customer, f.subscription).payments().get(0).verifiedByAccountId()).isNull();
        // A decision is final.
        assertError(send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/verify",
                f.owner.accessToken(), Map.of()), HttpStatus.CONFLICT, "PAYMENT_ALREADY_DECIDED");
        assertError(send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/reject",
                f.owner.accessToken(), Map.of("reason", "too late")), HttpStatus.CONFLICT, "PAYMENT_ALREADY_DECIDED");
    }

    @Test
    void aRejectedPaymentNeedsAReasonIsNotCountedAndFreesItsReference() {
        Fixture f = fixture();
        String ref = uniqueRef();
        PaymentView p = submit(f.customer, f.subscription, upi(ref, "1000"));

        assertThat(statusOf(send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/reject",
                f.owner.accessToken(), Map.of("reason", "  ")))).isEqualTo(400);

        PaymentView rejected = send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/reject",
                f.owner.accessToken(), Map.of("reason", "Amount does not match the bank statement"))
                .expectStatus().isOk().expectBody(PaymentView.class).returnResult().getResponseBody();
        assertThat(rejected.verificationStatus()).isEqualTo(PaymentVerificationStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Amount does not match the bank statement");
        assertThat(eventTypes(p.id())).containsExactly("SUBMITTED", "REJECTED");

        PaymentLedgerView ledger = myLedger(f.customer, f.subscription);
        assertThat(ledger.confirmedPaid()).isEqualByComparingTo("0");
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("0");
        assertThat(ledger.remainingToSubmit()).isEqualByComparingTo("4800");
        assertThat(ledger.payments().get(0).rejectionReason()).isEqualTo("Amount does not match the bank statement");

        // The customer can resubmit the same reference, corrected.
        PaymentView again = submit(f.customer, f.subscription,
                upi(ref.substring(0, 4) + " " + ref.substring(4), "900"));
        assertThat(again.id()).isNotEqualTo(p.id());
    }

    @Test
    void parallelVerifyAndRejectOfOnePaymentHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture();
        PaymentView p = submit(f.customer, f.subscription, upi(uniqueRef(), "1000"));
        String base = staffUrl(f.provider, f.subscription) + "/" + p.id();

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", base + "/verify", f.owner.accessToken(), Map.of())),
                () -> statusOf(send("POST", base + "/reject", f.owner.accessToken(), Map.of("reason", "no"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(eventTypes(p.id())).hasSize(2);
    }

    // ------------------------------------------------------------ authorization

    @Test
    void anotherCustomerCannotSeeOrPayOnSomeoneElsesSubscription() {
        Fixture f = fixture();
        Account stranger = login();

        assertError(send("GET", myUrl(f.subscription), stranger.accessToken(), null), HttpStatus.NOT_FOUND,
                "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", myUrl(f.subscription), stranger.accessToken(), upi(uniqueRef(), "10")),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertThat(paymentRows(f.subscription)).isZero();
    }

    @Test
    void staffOfAnotherProviderCannotReadOrDecidePayments() {
        Fixture f = fixture();
        PaymentView p = submit(f.customer, f.subscription, upi(uniqueRef(), "100"));
        Account otherOwner = login();
        UUID otherProvider = createProvider(otherOwner, "Other Mess");

        // No role on this provider at all.
        assertThat(statusOf(send("GET", staffUrl(f.provider, f.subscription), otherOwner.accessToken(), null)))
                .isEqualTo(404);
        assertThat(statusOf(send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/verify",
                otherOwner.accessToken(), Map.of()))).isEqualTo(404);
        // Their own provider, but someone else's subscription id.
        assertError(send("GET", staffUrl(otherProvider, f.subscription), otherOwner.accessToken(), null),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", staffUrl(otherProvider, f.subscription) + "/" + p.id() + "/verify",
                otherOwner.accessToken(), Map.of()), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertThat(ledgerStatus(f.provider, f.subscription, p.id())).isEqualTo("PENDING");
    }

    @Test
    void aWorkerCannotSeePaymentsButAManagerCanVerify() {
        Fixture f = fixture();
        PaymentView p = submit(f.customer, f.subscription, upi(uniqueRef(), "100"));
        Account worker = login();
        Account manager = login();
        assignRole(f.owner, f.provider, worker, "WORKER");
        assignRole(f.owner, f.provider, manager, "MANAGER");

        assertThat(statusOf(send("GET", staffUrl(f.provider, f.subscription), worker.accessToken(), null)))
                .isEqualTo(403);
        assertThat(statusOf(send("POST", staffUrl(f.provider, f.subscription) + "/" + p.id() + "/verify",
                worker.accessToken(), Map.of()))).isEqualTo(403);
        assertThat(ledgerStatus(f.provider, f.subscription, p.id())).isEqualTo("PENDING");

        PaymentView verified = verify(manager, f.provider, f.subscription, p.id());
        assertThat(verified.verifiedByAccountId()).isEqualTo(manager.id());
    }

    @Test
    void aStaffMemberCannotVerifyTheirOwnPayment() {
        Account owner = login();
        UUID provider = createProvider(owner, "Self Mess");
        PlanView plan = createPlan(owner, provider);
        // The owner is also a customer of their own mess.
        CustomerView me = enroll(owner, provider, owner, "Owner Eats Here");
        UUID subscription = sell(owner, provider, me.membershipId(), plan.id()).id();
        PaymentView p = submit(owner, subscription, upi(uniqueRef(), "100"));

        assertError(send("POST", staffUrl(provider, subscription) + "/" + p.id() + "/verify",
                owner.accessToken(), Map.of()), HttpStatus.FORBIDDEN, "PAYMENT_OWN_PAYMENT");
        assertError(send("POST", staffUrl(provider, subscription) + "/" + p.id() + "/reject",
                owner.accessToken(), Map.of("reason", "x")), HttpStatus.FORBIDDEN, "PAYMENT_OWN_PAYMENT");
    }

    // ------------------------------------------------------------ database guards

    @Test
    void thePaymentTablesRejectEditsAndDeletesBehindTheApplicationsBack() {
        Fixture f = fixture();
        PaymentView p = submit(f.customer, f.subscription, upi(uniqueRef(), "100"));

        // What the customer submitted is immutable, even while PENDING.
        assertThatThrownBy(() -> jdbc.update("update subscription_payment set amount = 1 where id = ?", p.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from subscription_payment where id = ?", p.id()))
                .hasMessageContaining("never deleted");
        // The audit is append-only.
        assertThatThrownBy(() -> jdbc.update("update subscription_payment_event set detail = 'x' where payment_id = ?",
                p.id())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from subscription_payment_event where payment_id = ?", p.id()))
                .hasMessageContaining("append-only");

        // A decided payment cannot be flipped back or to the other outcome, and a rejection must say why.
        verify(f.owner, f.provider, f.subscription, p.id());
        assertThatThrownBy(() -> jdbc.update("""
                update subscription_payment set verification_status = 'PENDING', verified_by_account_id = null,
                       verified_at = null where id = ?
                """, p.id())).hasMessageContaining("can no longer change");
        PaymentView q = submit(f.customer, f.subscription, upi(uniqueRef(), "100"));
        assertThatThrownBy(() -> jdbc.update("""
                update subscription_payment set verification_status = 'REJECTED', verified_by_account_id = ?,
                       verified_at = now() where id = ?
                """, f.owner.id(), q.id())).isInstanceOf(Exception.class);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    /** An owned provider with a 4800 plan, one member with their own login, and a running subscription. */
    private record Fixture(Account owner, UUID provider, Account customer, String customerName, UUID subscription) {
    }

    private Fixture fixture() {
        Account owner = login();
        UUID provider = createProvider(owner, "Pay Mess");
        PlanView plan = createPlan(owner, provider);
        Account customer = login();
        String name = "Customer " + customer.phone().substring(customer.phone().length() - 4);
        CustomerView member = enroll(owner, provider, customer, name);
        UUID subscription = sell(owner, provider, member.membershipId(), plan.id()).id();
        return new Fixture(owner, provider, customer, name, subscription);
    }

    private static LocalDate yesterday() {
        return LocalDate.now(BUSINESS_ZONE).minusDays(1);
    }

    private static String uniqueRef() {
        // A real UPI UTR: exactly 12 digits (distinct per call: the clock only moves forward, the counter breaks ties).
        long n = (System.nanoTime() / 1000 + REF_COUNTER.getAndIncrement()) % 900_000_000_000L + 100_000_000_000L;
        return Long.toString(n);
    }

    /** A fresh 12-digit UTR typed the way people copy it: in groups of four. */
    private static String uniqueRefSpaced() {
        String r = uniqueRef();
        return r.substring(0, 4) + " " + r.substring(4, 8) + " " + r.substring(8);
    }

    private static Map<String, Object> body(String method, String reference, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("method", method);
        if (reference != null) {
            m.put("transactionReference", reference);
        }
        m.put("amount", new BigDecimal(amount));
        m.put("paymentDate", yesterday().toString());
        m.put("paymentTime", "10:30");
        return m;
    }

    private static Map<String, Object> upi(String reference, String amount) {
        return body("UPI", reference, amount);
    }

    private static Map<String, Object> bank(String reference, String amount) {
        return body("BANK_TRANSFER", reference, amount);
    }

    private static String myUrl(UUID subscription) {
        return "/api/v1/subscriptions/" + subscription + "/payments";
    }

    private static String staffUrl(UUID provider, UUID subscription) {
        return "/api/v1/providers/" + provider + "/subscriptions/" + subscription + "/payments";
    }

    private PaymentView submit(Account customer, UUID subscription, Map<String, Object> request) {
        return send("POST", myUrl(subscription), customer.accessToken(), request)
                .expectStatus().isCreated().expectBody(PaymentView.class).returnResult().getResponseBody();
    }

    private PaymentView verify(Account staff, UUID provider, UUID subscription, UUID payment) {
        return send("POST", staffUrl(provider, subscription) + "/" + payment + "/verify", staff.accessToken(),
                Map.of()).expectStatus().isOk().expectBody(PaymentView.class).returnResult().getResponseBody();
    }

    private PaymentLedgerView myLedger(Account customer, UUID subscription) {
        return send("GET", myUrl(subscription), customer.accessToken(), null)
                .expectStatus().isOk().expectBody(PaymentLedgerView.class).returnResult().getResponseBody();
    }

    private PaymentLedgerView staffLedger(Account staff, UUID provider, UUID subscription) {
        return send("GET", staffUrl(provider, subscription), staff.accessToken(), null)
                .expectStatus().isOk().expectBody(PaymentLedgerView.class).returnResult().getResponseBody();
    }

    private String ledgerStatus(UUID provider, UUID subscription, UUID payment) {
        return jdbc.queryForObject("select verification_status from subscription_payment where id = ?",
                String.class, payment);
    }

    private List<String> eventTypes(UUID payment) {
        return jdbc.queryForList(
                "select event_type from subscription_payment_event where payment_id = ? order by occurred_at, id",
                String.class, payment);
    }

    private int paymentRows(UUID subscription) {
        return jdbc.queryForObject("select count(*) from subscription_payment where subscription_id = ?",
                Integer.class, subscription);
    }

    /**
     * F-03: enrolment is an invitation the customer accepts. Gives the customer their own profile name (a provider can no
     * longer supply it), invites the number, accepts as the customer, and returns the provider's view of the member.
     */
    private CustomerView enroll(Account owner, UUID provider, Account customer, String name) {
        send("PUT", "/api/v1/me/person", customer.accessToken(), Map.of("fullName", name)).expectStatus().isOk();
        InvitationView invitation = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", customer.phone()))
                .expectStatus().isAccepted().expectBody(InvitationView.class).returnResult().getResponseBody();
        MyMembershipView membership = send("POST", "/api/v1/me/invitations/" + invitation.invitationId() + "/accept",
                customer.accessToken(), Map.of())
                .expectStatus().isOk().expectBody(MyMembershipView.class).returnResult().getResponseBody();
        return send("GET", "/api/v1/providers/" + provider + "/customers/" + membership.membershipId(),
                owner.accessToken(), null)
                .expectStatus().isOk().expectBody(CustomerView.class).returnResult().getResponseBody();
    }

    private Account login() {
        String phone = String.format("+91987665%04d", PHONE_COUNTER.getAndIncrement());
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

    private UUID createProvider(Account owner, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerTypes", java.util.List.of("MESS"));
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    private void assignRole(Account owner, UUID providerId, Account target, String role) {
        send("POST", "/api/v1/providers/" + providerId + "/roles", owner.accessToken(),
                Map.of("phone", target.phone(), "role", role)).expectStatus().isCreated();
    }

    /** A 30-day plan priced at 4800. */
    private PlanView createPlan(Account owner, UUID provider) {
        Map<String, Object> policy = new HashMap<>();
        policy.put("extensionAllowed", false);
        policy.put("sameDayAbsenceAllowed", false);
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", "Executive Dual Feast");
        plan.put("consumptionType", "DAY");
        plan.put("durationDays", 30);
        plan.put("price", new BigDecimal("4800"));
        plan.put("policy", policy);
        return send("POST", "/api/v1/providers/" + provider + "/plans", owner.accessToken(), plan)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private SubscriptionView sell(Account actor, UUID provider, UUID membership, UUID planId) {
        return send("POST", "/api/v1/providers/" + provider + "/subscriptions", actor.accessToken(),
                Map.of("membershipId", membership, "planId", planId))
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();
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

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }

    private int statusOf(RestTestClient.ResponseSpec spec) {
        return spec.expectBody(String.class).returnResult().getStatus().value();
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
