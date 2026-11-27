package com.firstfood.membership;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in person/membership management. Use the factories. */
public class MembershipException extends DomainException {

    private MembershipException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static MembershipException personNameRequired() {
        return new MembershipException("PERSON_NAME_REQUIRED",
                "Add your full name to your profile first.", HttpStatus.BAD_REQUEST);
    }

    /** Deliberately identical for unknown, malformed and suspended numbers (same as role assignment). */
    public static MembershipException targetAccountNotFound() {
        return new MembershipException("TARGET_ACCOUNT_NOT_FOUND",
                "No active account is registered with that phone number.", HttpStatus.NOT_FOUND);
    }

    public static MembershipException personNotFound() {
        return new MembershipException("PERSON_NOT_FOUND", "Person not found.", HttpStatus.NOT_FOUND);
    }

    public static MembershipException invalidAddress(String message) {
        return new MembershipException("INVALID_ADDRESS", message, HttpStatus.BAD_REQUEST);
    }

    public static MembershipException membershipNotFound() {
        return new MembershipException("MEMBERSHIP_NOT_FOUND", "Membership not found.", HttpStatus.NOT_FOUND);
    }

    public static MembershipException alreadyActive() {
        return new MembershipException("MEMBERSHIP_ALREADY_ACTIVE",
                "This customer is already an active member of the provider.", HttpStatus.CONFLICT);
    }

    public static MembershipException alreadyInactive() {
        return new MembershipException("MEMBERSHIP_ALREADY_INACTIVE",
                "This membership has already ended.", HttpStatus.CONFLICT);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its exception type). */
    public static MembershipException providerClosed() {
        return new MembershipException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static MembershipException providerNotAccepting() {
        return new MembershipException("PROVIDER_NOT_ACCEPTING_CUSTOMERS",
                "This provider is not accepting new customers right now.", HttpStatus.CONFLICT);
    }

    public static MembershipException invitationNotFound() {
        return new MembershipException("INVITATION_NOT_FOUND", "Invitation not found.", HttpStatus.NOT_FOUND);
    }

    public static MembershipException invitationNotPending() {
        return new MembershipException("INVITATION_NOT_PENDING",
                "This invitation has already been answered or withdrawn.", HttpStatus.CONFLICT);
    }

    public static MembershipException invitationExpired() {
        return new MembershipException("INVITATION_EXPIRED",
                "This invitation has expired. Ask the provider to invite you again.", HttpStatus.GONE);
    }

    /** A malformed number is knowable without any lookup, so telling the caller is not an account-existence oracle. */
    public static MembershipException invalidPhone() {
        return new MembershipException("INVALID_PHONE", "Enter a valid phone number.", HttpStatus.BAD_REQUEST);
    }
}
