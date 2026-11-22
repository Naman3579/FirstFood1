import { apiFetch, STAFF } from "./api-client";
import type { ProviderRole } from "./provider-api";

export type RoleAssignment = {
  id: string;
  providerId: string;
  accountId: string;
  accountPhone: string | null;
  role: ProviderRole;
  status: "ACTIVE" | "REVOKED";
  assignedBy: string | null;
  assignedAt: string;
  revokedAt: string | null;
  revokedBy: string | null;
};

export type OwnershipOfferStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "WITHDRAWN" | "EXPIRED";

/** The owner's view of an offer they made. */
export type OwnershipOffer = {
  id: string;
  providerId: string;
  targetPhone: string;
  status: OwnershipOfferStatus;
  createdAt: string;
  expiresAt: string;
  respondedAt: string | null;
};

/** What the person being offered a provider sees (the offering owner is only a phone hint). */
export type MyOwnershipOffer = {
  id: string;
  providerId: string;
  providerName: string;
  offeredByPhoneHint: string;
  createdAt: string;
  expiresAt: string;
};

/** Roles that can be handed out through the role endpoint (OWNER only changes through an accepted offer). */
export type AssignableRole = Exclude<ProviderRole, "OWNER">;

/**
 * One function per role-management endpoint. The acting account is always the
 * JWT - these calls never send an actor id, and the backend re-checks every
 * permission regardless of what the UI chose to show.
 */
export const roleApi = {
  list(accessToken: string, providerId: string): Promise<RoleAssignment[]> {
    return apiFetch<RoleAssignment[]>(`${STAFF}/providers/${providerId}/roles`, { accessToken });
  },

  assign(
    accessToken: string,
    providerId: string,
    input: { phone: string; role: AssignableRole },
  ): Promise<RoleAssignment> {
    return apiFetch<RoleAssignment>(`${STAFF}/providers/${providerId}/roles`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  revoke(accessToken: string, providerId: string, assignmentId: string): Promise<RoleAssignment> {
    return apiFetch<RoleAssignment>(`${STAFF}/providers/${providerId}/roles/${assignmentId}`, {
      method: "DELETE",
      accessToken,
    });
  },

  /** Texts a fresh code to the OWNER's own phone: needed to offer ownership or to close the provider. */
  requestStepUp(accessToken: string, providerId: string): Promise<void> {
    return apiFetch<void>(`${STAFF}/providers/${providerId}/owner-step-up`, { method: "POST", accessToken });
  },

  /** Offers ownership to a phone number. Nothing changes hands until that person accepts. */
  offerOwnership(accessToken: string, providerId: string, input: { phone: string; code: string }): Promise<OwnershipOffer> {
    return apiFetch<OwnershipOffer>(`${STAFF}/providers/${providerId}/ownership/offers`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  listOffers(accessToken: string, providerId: string): Promise<OwnershipOffer[]> {
    return apiFetch<OwnershipOffer[]>(`${STAFF}/providers/${providerId}/ownership/offers`, { accessToken });
  },

  withdrawOffer(accessToken: string, providerId: string, offerId: string): Promise<OwnershipOffer> {
    return apiFetch<OwnershipOffer>(`${STAFF}/providers/${providerId}/ownership/offers/${offerId}`, {
      method: "DELETE",
      accessToken,
    });
  },

  /** The receiving side: offers addressed to the signed-in account's own phone. */
  myOffers(accessToken: string): Promise<MyOwnershipOffer[]> {
    return apiFetch<MyOwnershipOffer[]>(`${STAFF}/ownership-offers`, { accessToken });
  },

  /** Returns [you as the new owner, the former owner now MANAGER]. */
  acceptOffer(accessToken: string, offerId: string): Promise<RoleAssignment[]> {
    return apiFetch<RoleAssignment[]>(`${STAFF}/ownership-offers/${offerId}/accept`, { method: "POST", accessToken });
  },

  declineOffer(accessToken: string, offerId: string): Promise<void> {
    return apiFetch<void>(`${STAFF}/ownership-offers/${offerId}/decline`, { method: "POST", accessToken });
  },
};
