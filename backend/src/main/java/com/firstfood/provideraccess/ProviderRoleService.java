package com.firstfood.provideraccess;

import java.util.List;
import java.util.UUID;

/**
 * Provider role lifecycle (Phase 4 Checkpoint 1). The acting account always
 * comes from the authenticated principal, never from a request body.
 *
 * Every operation authorizes through
 * {@link ProviderAccessService#requirePermission} - no role comparisons here.
 */
public interface ProviderRoleService {

    /**
     * OWNER assigns MANAGER or WORKER to the account registered under
     * {@code targetPhone}. OWNER cannot be assigned here (transfer only).
     */
    RoleAssignmentView assignRole(UUID actorAccountId, UUID providerId, String targetPhone, ProviderRole role);

    /** OWNER revokes a MANAGER/WORKER assignment. OWNER assignments are not revocable. */
    RoleAssignmentView revokeRole(UUID actorAccountId, UUID providerId, UUID assignmentId);

    /** OWNER or MANAGER may list the provider's ACTIVE assignments. */
    List<RoleAssignmentView> findRoles(UUID actorAccountId, UUID providerId);

    /** OWNER asks for a fresh verification code, sent to the owner's OWN registered phone (never a typed number). */
    void requestOwnerStepUp(UUID actorAccountId, UUID providerId);

    /**
     * OWNER offers the provider to the account registered under {@code targetPhone}. Needs a fresh step-up
     * {@code code}. Nothing changes hands yet and nothing is looked up: the target must accept
     * ({@link #acceptOwnershipOffer}). One open offer per provider. The owner is sent a notice.
     */
    OwnershipOfferView offerOwnership(UUID actorAccountId, UUID providerId, String targetPhone, String code);

    /** OWNER lists the offers made for this provider, newest first. */
    List<OwnershipOfferView> listOwnershipOffers(UUID actorAccountId, UUID providerId);

    /** OWNER withdraws a pending offer. */
    OwnershipOfferView withdrawOwnershipOffer(UUID actorAccountId, UUID providerId, UUID offerId);

    /** Open offers addressed to the signed-in account's phone. */
    List<MyOwnershipOfferView> listMyOwnershipOffers(UUID accountId);

    /**
     * The target accepts: atomically becomes OWNER; the offering owner becomes MANAGER and is sent a notice. Any prior
     * assignment of the acceptor is superseded. Returns the new OWNER's and the former OWNER's assignments.
     */
    List<RoleAssignmentView> acceptOwnershipOffer(UUID accountId, UUID offerId);

    void declineOwnershipOffer(UUID accountId, UUID offerId);

    /** True if the account holds exactly this role, ACTIVE, on the provider. No actor check (internal use). */
    boolean hasRole(UUID providerId, UUID accountId, ProviderRole role);
}
