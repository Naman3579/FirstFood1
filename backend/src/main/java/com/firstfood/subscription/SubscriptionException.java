package com.firstfood.subscription;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in subscription management. Use the factories. */
public class SubscriptionException extends DomainException {

    private SubscriptionException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static SubscriptionException subscriptionNotFound() {
        return new SubscriptionException("SUBSCRIPTION_NOT_FOUND", "Subscription not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException membershipNotFound() {
        return new SubscriptionException("MEMBERSHIP_NOT_FOUND", "Membership not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException membershipNotActive() {
        return new SubscriptionException("MEMBERSHIP_NOT_ACTIVE",
                "This customer is no longer a member of the provider. Add them again first.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException planNotFound() {
        return new SubscriptionException("PLAN_NOT_FOUND", "Plan not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException planNotActive() {
        return new SubscriptionException("PLAN_NOT_ACTIVE",
                "This plan is inactive and cannot be sold. Activate it or choose another plan.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException startInvalid(String message) {
        return new SubscriptionException("SUBSCRIPTION_START_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException periodInPast() {
        return new SubscriptionException("SUBSCRIPTION_PERIOD_IN_PAST",
                "That start date would make the subscription end before today.", HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException overlap() {
        return new SubscriptionException("SUBSCRIPTION_OVERLAP",
                "This customer already has an active subscription covering those days.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException providerAtCapacity() {
        return new SubscriptionException("PROVIDER_AT_CAPACITY",
                "This provider has reached its limit of active subscriptions.", HttpStatus.CONFLICT);
    }

    /** F-06: one account cannot hold an unlimited number of unconfirmed requests. */
    public static SubscriptionException tooManyPending(int max) {
        return new SubscriptionException("TOO_MANY_PENDING_SUBSCRIPTIONS",
                "You already have " + max + " subscription requests waiting for a provider to confirm them. "
                        + "Wait for them to be confirmed, or cancel one, before requesting another.",
                HttpStatus.CONFLICT);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static SubscriptionException providerClosed() {
        return new SubscriptionException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException notActive() {
        return new SubscriptionException("SUBSCRIPTION_NOT_ACTIVE",
                "Only a subscription that has not ended or been cancelled can be cancelled.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException notRenewable() {
        return new SubscriptionException("SUBSCRIPTION_NOT_RENEWABLE",
                "Only a subscription that has ended can be renewed. Wait until it ends, or sell a new one.",
                HttpStatus.CONFLICT);
    }

    public static SubscriptionException alreadyRenewed() {
        return new SubscriptionException("SUBSCRIPTION_ALREADY_RENEWED",
                "This subscription has already been renewed.", HttpStatus.CONFLICT);
    }

    /** The customer's absences earned days the extension engine has not applied yet; renewing now would lose them. */
    public static SubscriptionException extensionPending() {
        return new SubscriptionException("EXTENSION_PENDING",
                "This subscription has extension days that have not been applied yet. Apply the extension first,"
                        + " then renew.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException verificationNotPending() {
        return new SubscriptionException("SUBSCRIPTION_NOT_PENDING",
                "This subscription has already been verified or rejected.", HttpStatus.CONFLICT);
    }

    /** Whoever holds a staff role cannot also approve their own purchase as a customer. */
    public static SubscriptionException ownSubscription() {
        return new SubscriptionException("OWN_SUBSCRIPTION",
                "You cannot verify your own subscription. Ask another member of staff.", HttpStatus.FORBIDDEN);
    }

    public static SubscriptionException reasonRequired() {
        return new SubscriptionException("REJECTION_REASON_REQUIRED",
                "Say why the subscription is rejected - the customer will see it.", HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException membershipHasActiveSubscription() {
        return new SubscriptionException("MEMBERSHIP_HAS_ACTIVE_SUBSCRIPTION",
                "This customer has an active subscription. Cancel it before removing them.", HttpStatus.CONFLICT);
    }

    // ---- V25: vacation

    public static SubscriptionException vacationStartInvalid(String message) {
        return new SubscriptionException("VACATION_START_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException vacationAlreadyOpen() {
        return new SubscriptionException("VACATION_ALREADY_OPEN",
                "This subscription already has a vacation. Resume it first.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException vacationNotSupportedForMeal() {
        return new SubscriptionException("VACATION_NOT_SUPPORTED_FOR_MEAL",
                "A meal-based subscription keeps its meals whatever happens, so it does not need a vacation.",
                HttpStatus.CONFLICT);
    }

    public static SubscriptionException vacationNotAllowed(String message) {
        return new SubscriptionException("VACATION_NOT_ALLOWED", message, HttpStatus.CONFLICT);
    }

    public static SubscriptionException vacationConcurrentChange() {
        return new SubscriptionException("VACATION_CONCURRENT_CHANGE",
                "This subscription changed while your request was processed. Refresh and try again.",
                HttpStatus.CONFLICT);
    }

    public static SubscriptionException noVacation() {
        return new SubscriptionException("NO_VACATION", "This subscription has no vacation.", HttpStatus.NOT_FOUND);
    }
}
