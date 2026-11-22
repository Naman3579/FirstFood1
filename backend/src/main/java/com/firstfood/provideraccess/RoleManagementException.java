package com.firstfood.provideraccess;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in provider role management. Use the factories. */
public class RoleManagementException extends DomainException {

    private RoleManagementException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static RoleManagementException invalidRole() {
        return new RoleManagementException("ROLE_INVALID", "A valid role (MANAGER or WORKER) is required.",
                HttpStatus.BAD_REQUEST);
    }

    public static RoleManagementException ownerAssignmentNotAllowed() {
        return new RoleManagementException("OWNER_ASSIGNMENT_NOT_ALLOWED",
                "The OWNER role can only change hands through an ownership transfer.", HttpStatus.BAD_REQUEST);
    }

    /** Deliberately identical for unknown, malformed and suspended targets. */
    public static RoleManagementException targetAccountNotFound() {
        return new RoleManagementException("TARGET_ACCOUNT_NOT_FOUND",
                "No active account is registered with that phone number.", HttpStatus.NOT_FOUND);
    }

    public static RoleManagementException transferToSelf() {
        return new RoleManagementException("OWNERSHIP_TRANSFER_TO_SELF",
                "You already own this provider.", HttpStatus.BAD_REQUEST);
    }

    public static RoleManagementException alreadyAssigned() {
        return new RoleManagementException("ROLE_ALREADY_ASSIGNED",
                "This account already holds an active role on the provider.", HttpStatus.CONFLICT);
    }

    public static RoleManagementException assignmentNotFound() {
        return new RoleManagementException("ROLE_ASSIGNMENT_NOT_FOUND", "Role assignment not found.",
                HttpStatus.NOT_FOUND);
    }

    public static RoleManagementException alreadyRevoked() {
        return new RoleManagementException("ROLE_ALREADY_REVOKED", "This role assignment is already revoked.",
                HttpStatus.CONFLICT);
    }

    public static RoleManagementException ownerRevocationNotAllowed() {
        return new RoleManagementException("OWNER_REVOCATION_NOT_ALLOWED",
                "The OWNER cannot be revoked; transfer ownership instead.", HttpStatus.CONFLICT);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on that module). */
    public static RoleManagementException providerClosed() {
        return new RoleManagementException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static RoleManagementException offerTargetInvalid() {
        return new RoleManagementException("OWNERSHIP_TARGET_INVALID", "Enter a valid phone number.",
                HttpStatus.BAD_REQUEST);
    }

    public static RoleManagementException offerAlreadyPending() {
        return new RoleManagementException("OWNERSHIP_OFFER_PENDING",
                "An ownership offer is already waiting for a reply. Withdraw it before making another.",
                HttpStatus.CONFLICT);
    }

    /** Same answer for "no such offer", "someone else's offer" and "another provider's offer". */
    public static RoleManagementException offerNotFound() {
        return new RoleManagementException("OWNERSHIP_OFFER_NOT_FOUND", "Ownership offer not found.",
                HttpStatus.NOT_FOUND);
    }

    public static RoleManagementException offerNotPending() {
        return new RoleManagementException("OWNERSHIP_OFFER_NOT_PENDING",
                "This ownership offer has already been answered or withdrawn.", HttpStatus.CONFLICT);
    }

    public static RoleManagementException offerExpired() {
        return new RoleManagementException("OWNERSHIP_OFFER_EXPIRED",
                "This ownership offer has expired. Ask the owner to send a new one.", HttpStatus.CONFLICT);
    }

    /** The owner who made the offer no longer owns the provider, so the offer is void. */
    public static RoleManagementException offerStale() {
        return new RoleManagementException("OWNERSHIP_OFFER_STALE",
                "The owner who made this offer no longer owns the provider.", HttpStatus.CONFLICT);
    }
}
