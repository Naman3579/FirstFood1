package com.firstfood.payment;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in payment handling. Use the factories. */
public class PaymentException extends DomainException {

    private PaymentException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static PaymentException subscriptionNotFound() {
        return new PaymentException("SUBSCRIPTION_NOT_FOUND", "Subscription not found.", HttpStatus.NOT_FOUND);
    }

    public static PaymentException paymentNotFound() {
        return new PaymentException("PAYMENT_NOT_FOUND", "Payment not found.", HttpStatus.NOT_FOUND);
    }

    public static PaymentException providerClosed() {
        return new PaymentException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static PaymentException invalid(String message) {
        return new PaymentException("PAYMENT_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static PaymentException referenceRequired(PaymentMethod method) {
        return new PaymentException("PAYMENT_REFERENCE_REQUIRED",
                "A transaction reference (UTR / receipt number) is required for " + method + " payments.",
                HttpStatus.BAD_REQUEST);
    }

    public static PaymentException inFuture() {
        return new PaymentException("PAYMENT_TIME_IN_FUTURE",
                "The payment date and time cannot be in the future.", HttpStatus.BAD_REQUEST);
    }

    public static PaymentException exceedsOutstanding(String remaining, String currency) {
        return new PaymentException("PAYMENT_EXCEEDS_OUTSTANDING",
                "That is more than is still unpaid on this subscription (" + currency + " " + remaining
                        + " not yet covered by verified or pending payments).", HttpStatus.CONFLICT);
    }

    public static PaymentException duplicateReference() {
        return new PaymentException("PAYMENT_REFERENCE_DUPLICATE",
                "A payment with this transaction reference has already been submitted.", HttpStatus.CONFLICT);
    }

    public static PaymentException idempotencyKeyReused() {
        return new PaymentException("PAYMENT_IDEMPOTENCY_KEY_REUSED",
                "That request key was already used for a different subscription.", HttpStatus.CONFLICT);
    }

    public static PaymentException alreadyDecided() {
        return new PaymentException("PAYMENT_ALREADY_DECIDED",
                "This payment has already been verified or rejected.", HttpStatus.CONFLICT);
    }

    public static PaymentException ownPayment() {
        return new PaymentException("PAYMENT_OWN_PAYMENT",
                "You cannot verify or reject a payment you submitted yourself.", HttpStatus.FORBIDDEN);
    }
}
