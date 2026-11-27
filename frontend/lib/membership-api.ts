import { apiFetch, STAFF } from "./api-client";

export type MembershipStatus = "ACTIVE" | "INACTIVE";

export type InvitationStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "WITHDRAWN" | "EXPIRED";

/**
 * A provider's invitation to a phone number. The provider sees the same thing whether or not the number has an account -
 * nothing here reveals that - and the customer only becomes a member (and their name, phone and address only become
 * visible to the provider) once they accept.
 */
export type Invitation = {
  invitationId: string;
  providerId: string;
  phone: string;
  status: InvitationStatus;
  createdAt: string;
  expiresAt: string;
  respondedAt: string | null;
};

/** An open invitation as the invited customer sees it. */
export type MyInvitation = {
  invitationId: string;
  providerId: string;
  providerName: string;
  invitedAt: string;
  expiresAt: string;
};

/** One row of a provider's customer list (a membership + its person). */
export type Customer = {
  membershipId: string;
  providerId: string;
  personId: string;
  fullName: string;
  /** Null once the customer has left: contact details are only visible for active memberships. */
  phone: string | null;
  /** The customer's saved address, or null (also null once they have left). */
  address: Address | null;
  status: MembershipStatus;
  joinedAt: string;
  leftAt: string | null;
  addedBy: string;
  leftBy: string | null;
};

/** A customer's address. Street address, area and city always go together; pincode and landmark are optional. */
export type Address = {
  addressLine: string;
  locality: string;
  city: string;
  pincode: string | null;
  landmark: string | null;
};

export type AddressInput = {
  addressLine: string;
  locality: string;
  city: string;
  pincode?: string;
  landmark?: string;
};

export type Person = {
  id: string;
  fullName: string;
  primaryPerson: boolean;
  /** Null until the customer saves one. */
  address: Address | null;
  createdAt: string;
};

/** A membership as the customer sees it (their own, across providers). */
export type MyMembership = {
  membershipId: string;
  providerId: string;
  providerName: string;
  personId: string;
  status: MembershipStatus;
  joinedAt: string;
  leftAt: string | null;
};

/**
 * One function per Phase 5 endpoint. The acting account is always the JWT - these
 * calls never send an actor id, and the backend re-checks every permission
 * regardless of what the UI chose to show.
 */
export const membershipApi = {
  list(accessToken: string, providerId: string, includeInactive: boolean): Promise<Customer[]> {
    return apiFetch<Customer[]>(`${STAFF}/providers/${providerId}/customers?includeInactive=${includeInactive}`, {
      accessToken,
    });
  },

  get(accessToken: string, providerId: string, membershipId: string): Promise<Customer> {
    return apiFetch<Customer>(`${STAFF}/providers/${providerId}/customers/${membershipId}`, { accessToken });
  },

  /**
   * Invites a phone number to become a customer. Nothing is created until they accept, and the answer is the same for
   * numbers with and without an account.
   */
  invite(accessToken: string, providerId: string, phone: string): Promise<Invitation> {
    return apiFetch<Invitation>(`${STAFF}/providers/${providerId}/customers`, {
      method: "POST",
      accessToken,
      body: { phone },
    });
  },

  invitations(accessToken: string, providerId: string): Promise<Invitation[]> {
    return apiFetch<Invitation[]>(`${STAFF}/providers/${providerId}/customers/invitations`, { accessToken });
  },

  withdrawInvitation(accessToken: string, providerId: string, invitationId: string): Promise<Invitation> {
    return apiFetch<Invitation>(`${STAFF}/providers/${providerId}/customers/invitations/${invitationId}/withdraw`, {
      method: "POST",
      accessToken,
    });
  },

  deactivate(accessToken: string, providerId: string, membershipId: string): Promise<Customer> {
    return apiFetch<Customer>(`${STAFF}/providers/${providerId}/customers/${membershipId}/deactivate`, {
      method: "POST",
      accessToken,
    });
  },

  /** 404 PERSON_NOT_FOUND until the account has a profile. */
  getMyPerson(accessToken: string): Promise<Person> {
    return apiFetch<Person>("/me/person", { accessToken });
  },

  saveMyPerson(accessToken: string, fullName: string): Promise<Person> {
    return apiFetch<Person>("/me/person", { method: "PUT", accessToken, body: { fullName } });
  },

  /** Saves the address; sending null removes it. */
  saveMyAddress(accessToken: string, address: AddressInput | null): Promise<Person> {
    return apiFetch<Person>("/me/person/address", { method: "PUT", accessToken, body: address ?? {} });
  },

  myMemberships(accessToken: string): Promise<MyMembership[]> {
    return apiFetch<MyMembership[]>("/me/memberships", { accessToken });
  },

  myInvitations(accessToken: string): Promise<MyInvitation[]> {
    return apiFetch<MyInvitation[]>("/me/invitations", { accessToken });
  },

  /** 400 PERSON_NAME_REQUIRED until the customer has saved their own name. */
  acceptInvitation(accessToken: string, invitationId: string): Promise<MyMembership> {
    return apiFetch<MyMembership>(`/me/invitations/${invitationId}/accept`, { method: "POST", accessToken });
  },

  declineInvitation(accessToken: string, invitationId: string): Promise<void> {
    return apiFetch<void>(`/me/invitations/${invitationId}/decline`, { method: "POST", accessToken });
  },

  /** Leaves a provider; they stop seeing this account's phone number and address. */
  leave(accessToken: string, membershipId: string): Promise<MyMembership> {
    return apiFetch<MyMembership>(`/me/memberships/${membershipId}/leave`, { method: "POST", accessToken });
  },
};
