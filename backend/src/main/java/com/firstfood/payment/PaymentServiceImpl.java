package com.firstfood.payment;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.subscription.SubscriptionLookupService;
import com.firstfood.subscription.SubscriptionPrice;
import com.firstfood.subscription.SubscriptionRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {

    /** A phone clock a few minutes ahead of the server must not make an honest payment "in the future". */
    private static final long CLOCK_SKEW_MINUTES = 5;
    private static final Set<PaymentVerificationStatus> COUNTED =
            Set.of(PaymentVerificationStatus.PENDING, PaymentVerificationStatus.VERIFIED);

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final SubscriptionLookupService subscriptionLookup;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionPaymentEventRepository events;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public PaymentServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup,
            SubscriptionLookupService subscriptionLookup,
            SubscriptionPaymentRepository payments,
            SubscriptionPaymentEventRepository events,
            BusinessCalendar calendar,
            Clock clock) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.subscriptionLookup = subscriptionLookup;
        this.payments = payments;
        this.events = events;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentView submit(UUID accountId, UUID subscriptionId, SubmitPaymentCommand cmd) {
        // The subscription row lock serializes this customer's submissions on one subscription, so the
        // "do not overpay" and idempotency checks below cannot be raced past by two parallel requests.
        SubscriptionRef sub = subscriptionLookup
                .lockOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(PaymentException::subscriptionNotFound);
        requireOpen(sub.providerId());

        String key = cleanKey(cmd.idempotencyKey());
        if (key != null) {
            var replay = payments.findByCustomerAccountIdAndIdempotencyKey(accountId, key);
            if (replay.isPresent()) {
                if (!replay.get().getSubscriptionId().equals(subscriptionId)) {
                    throw PaymentException.idempotencyKeyReused();
                }
                return PaymentView.from(replay.get(), false);
            }
        }

        PaymentMethod method = cmd.method();
        String reference = cleanReference(method, cmd.transactionReference());
        BigDecimal amount = cleanAmount(cmd.amount());
        requireNotInFuture(cmd);

        SubscriptionPrice price = subscriptionLookup.priceOf(subscriptionId)
                .orElseThrow(PaymentException::subscriptionNotFound);
        BigDecimal committed = payments.sumAmount(subscriptionId, COUNTED);
        BigDecimal remaining = price.price().subtract(committed);
        if (amount.compareTo(remaining) > 0) {
            throw PaymentException.exceedsOutstanding(remaining.max(BigDecimal.ZERO).toPlainString(), price.currency());
        }
        requireUnusedReference(sub.providerId(), method, reference);

        MemberSummary member = membershipLookup.summarize(List.of(sub.membershipId())).get(sub.membershipId());
        String name = member == null || member.fullName() == null || member.fullName().isBlank()
                ? "(unknown)" : member.fullName().trim();

        Instant now = now();
        SubscriptionPayment payment = new SubscriptionPayment(subscriptionId, sub.providerId(), accountId, name,
                reference, amount, price.currency(), method, cmd.paymentDate(), cmd.paymentTime(), now, key);
        try {
            payment = payments.saveAndFlush(payment);
            events.save(new SubscriptionPaymentEvent(payment.getId(), sub.providerId(),
                    PaymentEventType.SUBMITTED, accountId, null, now));
        } catch (DataIntegrityViolationException e) {
            // Backstops for the database indexes; the checks above normally catch these first.
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("uq_subscription_payment_digital_reference")
                    || detail.contains("uq_subscription_payment_receipt_reference")) {
                throw PaymentException.duplicateReference();
            }
            if (detail.contains("uq_subscription_payment_idempotency")) {
                throw PaymentException.idempotencyKeyReused();
            }
            throw e;
        }
        return PaymentView.from(payment, false);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentLedgerView ledgerMine(UUID accountId, UUID subscriptionId) {
        SubscriptionRef sub = subscriptionLookup
                .findOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(PaymentException::subscriptionNotFound);
        return buildLedger(sub.subscriptionId(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentLedgerView ledger(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        SubscriptionRef sub = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(PaymentException::subscriptionNotFound);
        return buildLedger(sub.subscriptionId(), true);
    }

    @Override
    @Transactional
    public PaymentView verify(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID paymentId) {
        SubscriptionPayment p = lockPendingForDecision(actorAccountId, providerId, subscriptionId, paymentId);
        Instant now = now();
        p.verify(actorAccountId, now);
        p = payments.saveAndFlush(p);
        events.save(new SubscriptionPaymentEvent(p.getId(), providerId, PaymentEventType.VERIFIED, actorAccountId,
                null, now));
        return PaymentView.from(p, true);
    }

    @Override
    @Transactional
    public PaymentView reject(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID paymentId,
            String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.isEmpty()) {
            throw PaymentException.invalid("Say why the payment is rejected - the customer will see it.");
        }
        if (clean.length() > 500) {
            throw PaymentException.invalid("The reason can be at most 500 characters.");
        }
        SubscriptionPayment p = lockPendingForDecision(actorAccountId, providerId, subscriptionId, paymentId);
        Instant now = now();
        p.reject(actorAccountId, clean, now);
        p = payments.saveAndFlush(p);
        events.save(new SubscriptionPaymentEvent(p.getId(), providerId, PaymentEventType.REJECTED, actorAccountId,
                clean, now));
        return PaymentView.from(p, true);
    }

    // ---- helpers ----

    /** Authorize, lock the subscription then the payment, and make sure it is still waiting for a decision. */
    private SubscriptionPayment lockPendingForDecision(UUID actorAccountId, UUID providerId, UUID subscriptionId,
            UUID paymentId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        requireOpen(providerId);
        subscriptionLookup.lock(providerId, subscriptionId).orElseThrow(PaymentException::subscriptionNotFound);
        SubscriptionPayment p = payments.findForDecision(paymentId, subscriptionId, providerId)
                .orElseThrow(PaymentException::paymentNotFound);
        // Whoever holds the staff role cannot also approve their own money as a customer.
        if (p.getCustomerAccountId().equals(actorAccountId)) {
            throw PaymentException.ownPayment();
        }
        if (!p.isPending()) {
            throw PaymentException.alreadyDecided();
        }
        return p;
    }

    private PaymentLedgerView buildLedger(UUID subscriptionId, boolean forStaff) {
        SubscriptionPrice price = subscriptionLookup.priceOf(subscriptionId)
                .orElseThrow(PaymentException::subscriptionNotFound);
        List<SubscriptionPayment> all = payments.findBySubscriptionIdOrderBySubmittedAtDesc(subscriptionId);
        BigDecimal verified = sum(all, PaymentVerificationStatus.VERIFIED);
        BigDecimal pending = sum(all, PaymentVerificationStatus.PENDING);
        BigDecimal outstanding = price.price().subtract(verified).max(BigDecimal.ZERO);
        BigDecimal remainingToSubmit = price.price().subtract(verified).subtract(pending).max(BigDecimal.ZERO);
        return new PaymentLedgerView(subscriptionId, price.currency(), price.price(), verified, pending, outstanding,
                remainingToSubmit, all.stream().map(p -> PaymentView.from(p, forStaff)).toList());
    }

    private static BigDecimal sum(List<SubscriptionPayment> all, PaymentVerificationStatus status) {
        return all.stream().filter(p -> p.getVerificationStatus() == status)
                .map(SubscriptionPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireOpen(UUID providerId) {
        // Non-locking read: payments must not serialize every customer of a provider behind its row lock.
        if (providerLookup.intakeOf(providerId).closed()) {
            throw PaymentException.providerClosed();
        }
    }

    private static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (k.length() > 64) {
            throw PaymentException.invalid("The request key can be at most 64 characters.");
        }
        return k;
    }

    private static String cleanReference(PaymentMethod method, String raw) {
        String ref = raw == null ? "" : raw.trim();
        if (ref.isEmpty()) {
            if (method.requiresReference()) {
                throw PaymentException.referenceRequired(method);
            }
            return null;
        }
        if (!PaymentReference.hasAllowedCharacters(ref)) {
            throw PaymentException.invalid("The transaction reference may only contain letters, digits and"
                    + " / _ . : # - (and spaces), up to 150 characters.");
        }
        String canonical = PaymentReference.canonical(ref);
        PaymentReference.formatProblem(method, canonical).ifPresent(problem -> {
            throw PaymentException.invalid(problem);
        });
        // UPI / bank references are stored in their canonical form, so staff see the 12 digits that the bank
        // statement shows, not how the customer happened to space them. Receipt numbers keep their own style.
        return method.requiresReference() ? canonical : ref;
    }

    private static BigDecimal cleanAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw PaymentException.invalid("The amount paid must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw PaymentException.invalid("The amount can have at most 2 decimal places.");
        }
        return amount.setScale(2);
    }

    private void requireNotInFuture(SubmitPaymentCommand cmd) {
        LocalDateTime claimed = LocalDateTime.of(cmd.paymentDate(), cmd.paymentTime());
        LocalDateTime now = LocalDateTime.now(clock.withZone(calendar.zone()));
        if (claimed.isAfter(now.plusMinutes(CLOCK_SKEW_MINUTES))) {
            throw PaymentException.inFuture();
        }
    }

    private void requireUnusedReference(UUID providerId, PaymentMethod method, String reference) {
        if (reference == null) {
            return;
        }
        String key = PaymentReference.canonical(reference);
        boolean used = method.referenceUniqueAcrossProviders()
                ? payments.existsLiveDigitalReference(PaymentMethod.DIGITAL, key)
                : payments.existsLiveReferenceInProvider(providerId, method, key);
        if (used) {
            throw PaymentException.duplicateReference();
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
