package com.firstfood.membership;

import java.util.List;
import java.util.UUID;

/**
 * Provider-side customer management plus the customer's own view. Every provider-scoped
 * method authorizes through ProviderAccessService; the acting account always comes from
 * the authenticated principal, never from request data.
 */
public interface MembershipService {

    /**
     * Invites {@code phone} to become a customer (F-03). Requires MEMBERSHIP_MANAGE; the provider must not be closed and
     * must be accepting new customers. Creates a PENDING invitation and NOTHING ELSE: no membership, no profile, no
     * contact details released. The result is identical whether or not the number has an account, so this cannot be
     * used to find out who is registered. Re-inviting a number with an open invitation returns that invitation.
     * Capped per provider per day.
     */
    InvitationView inviteCustomer(UUID actorAccountId, UUID providerId, String phone);

    /** Requires MEMBERSHIP_VIEW. Every invitation of this provider, newest first (expired ones read as EXPIRED). */
    List<InvitationView> listInvitations(UUID actorAccountId, UUID providerId);

    /** Requires MEMBERSHIP_MANAGE. Cancels a still-open invitation. */
    InvitationView withdrawInvitation(UUID actorAccountId, UUID providerId, UUID invitationId);

    /** The open (pending, unexpired) invitations addressed to the caller's phone number. */
    List<MyInvitationView> listMyInvitations(UUID accountId);

    /**
     * The caller accepts an invitation addressed to their phone: only now does a membership exist and does the provider
     * see the customer's name, phone and address. Needs the caller's profile name (PERSON_NAME_REQUIRED otherwise).
     * An invitation addressed to anyone else is reported as not found.
     */
    MyMembershipView acceptInvitation(UUID accountId, UUID invitationId);

    /** The caller turns an invitation down. Nothing is created and the provider learns only that it was declined. */
    void declineInvitation(UUID accountId, UUID invitationId);

    /**
     * The customer leaves a provider (F-03: there was previously no customer-side way out). Ends the membership, after
     * which the provider no longer sees the phone number or address. Allowed even when the provider is closed.
     * Deliberately NOT subject to {@link MembershipDeactivationGuard}: those guards make staff cancel a running
     * subscription first, but a customer cannot cancel one, so applying them would trap exactly the person this exists
     * for. Any running subscription stays with the provider to settle.
     */
    MyMembershipView leave(UUID accountId, UUID membershipId);

    /** Requires MEMBERSHIP_VIEW. Active members only unless {@code includeInactive}. */
    List<CustomerView> listCustomers(UUID actorAccountId, UUID providerId, boolean includeInactive);

    /** Requires MEMBERSHIP_VIEW. A membership of another provider is reported as not found. */
    CustomerView getCustomer(UUID actorAccountId, UUID providerId, UUID membershipId);

    /** Ends the membership (history kept). Requires MEMBERSHIP_MANAGE; blocked on closed providers. */
    CustomerView deactivate(UUID actorAccountId, UUID providerId, UUID membershipId);

    /** The caller's own memberships (all their persons, all providers, current and past). */
    List<MyMembershipView> listMine(UUID accountId);

    /**
     * Self-service join (the customer portal's subscribe flow): the caller becomes a customer of the provider, or is
     * returned their existing active membership. Needs the caller's profile name (PERSON_NAME_REQUIRED otherwise),
     * a provider that is not closed and, for a NEW membership, one that is accepting new customers. Takes the
     * provider row lock, so it must run inside the caller's transaction.
     */
    MyMembershipView joinProvider(UUID accountId, UUID providerId);
}
