import { apiFetch, STAFF } from "./api-client";
import type { ConsumptionType } from "./plan-api";

/** Stored lifecycle. ACTIVE may already be over by date: use `phase` for what a person should see. */
export type SubscriptionStatus = "ACTIVE" | "EXPIRED" | "CANCELLED";
/** Derived by the server for "today" in the business time zone. */
export type SubscriptionPhase = "UPCOMING" | "RUNNING" | "ENDED" | "CANCELLED";

/**
 * Whether the provider's staff have confirmed a new subscription. A customer's own purchase starts PENDING.
 * EXPIRED (F-06): nobody confirmed it in time, so the system closed it and freed its seat; the customer can ask again.
 */
export type VerificationStatus = "PENDING" | "VERIFIED" | "REJECTED" | "EXPIRED";

export type Verification = {
  status: VerificationStatus;
  requestedAt: string;
  decidedAt: string | null;
  rejectionReason: string | null;
  /** While PENDING: when the request lapses if nobody confirms it. Null once decided. */
  expiresAt: string | null;
};

/** What the subscription was sold under - frozen at sale time, never re-read from the plan. */
export type SubscriptionTerms = {
  planName: string;
  price: number;
  currency: string;
  purchasedDays: number | null;
  purchasedMeals: number | null;
  policyVersion: number;
  extensionAllowed: boolean;
  minConsecutiveAbsenceDays: number | null;
  sameDayAbsenceAllowed: boolean;
  absenceCutoffTime: string | null;
  maxCalendarWindowDays: number | null;
  capturedAt: string;
};

/** A subscription as provider staff see it. Dates are calendar days ("YYYY-MM-DD"). */
export type Subscription = {
  id: string;
  providerId: string;
  membershipId: string;
  personId: string;
  customerName: string;
  planId: string;
  consumptionType: ConsumptionType;
  status: SubscriptionStatus;
  phase: SubscriptionPhase;
  startDate: string;
  baseExpiryDate: string | null;
  effectiveExpiryDate: string | null;
  maximumExpiryDate: string | null;
  remainingDays: number | null;
  remainingMeals: number | null;
  renewedFromSubscriptionId: string | null;
  renewed: boolean;
  /** Server-decided: show Renew / Cancel from these, never from dates. */
  renewable: boolean;
  cancellable: boolean;
  createdBy: string;
  createdAt: string;
  expiredAt: string | null;
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancellationReason: string | null;
  verification: Verification | null;
  terms: SubscriptionTerms;
  /** V25: start of the open vacation (null = none; a future date = scheduled). */
  pausedSince: string | null;
  onVacation: boolean;
  /** V25: days given back by resumed vacations and applied provider closures (not absence extensions). */
  adjustmentDays: number;
};

/** A subscription as its customer sees it, across providers. */
export type MySubscription = {
  id: string;
  providerId: string;
  providerName: string;
  personId: string;
  planId: string;
  consumptionType: ConsumptionType;
  status: SubscriptionStatus;
  phase: SubscriptionPhase;
  startDate: string;
  effectiveExpiryDate: string | null;
  remainingDays: number | null;
  remainingMeals: number | null;
  cancelledAt: string | null;
  /** Already followed by a renewal. */
  renewed: boolean;
  /** Ended and not yet renewed. The server still re-checks every rule when you renew. */
  renewable: boolean;
  /** Has the provider confirmed it yet? Null only for rows that predate verification. */
  verification: Verification | null;
  terms: SubscriptionTerms;
  /** Start of the open vacation (null = none; a future date = scheduled). */
  pausedSince: string | null;
  /** On vacation today: the end date shown is not final until the customer resumes. */
  onVacation: boolean;
  /** A hint only; the server re-checks every rule when a vacation is started. */
  canStartVacation: boolean;
};

export type CreateSubscriptionInput = {
  membershipId: string;
  planId: string;
  /** "YYYY-MM-DD"; omitted = today. Price and terms are never sent: the server snapshots the plan. */
  startDate?: string;
};

export type RenewSubscriptionInput = { planId?: string; startDate?: string };

/**
 * One function per Phase 7 endpoint. The acting account is always the JWT; the backend decides
 * every rule (overlap, capacity, dates, who may sell) - nothing here duplicates them.
 * There is deliberately no delete: a subscription ends as expired or cancelled and stays on record.
 */
export const subscriptionApi = {
  /** Optional server-side filters: one customer's subscriptions, or only one lifecycle status. */
  list(
    accessToken: string,
    providerId: string,
    filter: { membershipId?: string; status?: SubscriptionStatus } = {},
  ): Promise<Subscription[]> {
    const q = new URLSearchParams();
    if (filter.membershipId) q.set("membershipId", filter.membershipId);
    if (filter.status) q.set("status", filter.status);
    const qs = q.toString();
    return apiFetch<Subscription[]>(`${STAFF}/providers/${providerId}/subscriptions${qs ? `?${qs}` : ""}`, { accessToken });
  },

  get(accessToken: string, providerId: string, id: string): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions/${id}`, { accessToken });
  },

  myGet(accessToken: string, id: string): Promise<MySubscription> {
    return apiFetch<MySubscription>(`/subscriptions/${id}`, { accessToken });
  },

  create(accessToken: string, providerId: string, input: CreateSubscriptionInput): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  /** Staff confirm a subscription the customer bought for themselves. */
  verify(accessToken: string, providerId: string, id: string): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions/${id}/verify`, {
      method: "POST",
      accessToken,
    });
  },

  /** Staff refuse a pending subscription: it is cancelled and the customer sees the reason. */
  reject(accessToken: string, providerId: string, id: string, reason: string): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions/${id}/reject`, {
      method: "POST",
      accessToken,
      body: { reason },
    });
  },

  cancel(accessToken: string, providerId: string, id: string, reason?: string): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions/${id}/cancel`, {
      method: "POST",
      accessToken,
      body: reason ? { reason } : {},
    });
  },

  renew(
    accessToken: string,
    providerId: string,
    id: string,
    input: RenewSubscriptionInput = {},
  ): Promise<Subscription> {
    return apiFetch<Subscription>(`${STAFF}/providers/${providerId}/subscriptions/${id}/renew`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  /** The customer renews their OWN ended subscription: a new sale at today's price and policy. */
  renewMine(accessToken: string, id: string, input: RenewSubscriptionInput = {}): Promise<MySubscription> {
    return apiFetch<MySubscription>(`/subscriptions/${id}/renew`, { method: "POST", accessToken, body: input });
  },

  mine(accessToken: string): Promise<MySubscription[]> {
    return apiFetch<MySubscription[]>("/subscriptions", { accessToken });
  },
};

/** "2026-09-30" -> local date string, without the one-day shift `new Date("2026-09-30")` can cause. */
export function formatDay(day: string | null): string {
  if (!day) {
    return "—";
  }
  const [y, m, d] = day.split("-").map(Number);
  if (y === undefined || m === undefined || d === undefined) {
    return day;
  }
  return new Date(y, m - 1, d).toLocaleDateString();
}

export const PHASE_LABEL: Record<SubscriptionPhase, string> = {
  UPCOMING: "Upcoming",
  RUNNING: "Running",
  ENDED: "Ended",
  CANCELLED: "Cancelled",
};

/** "12 days left" / "30 meals left" / "ended" - display only. */
export function describeRemaining(s: {
  phase: SubscriptionPhase;
  consumptionType: ConsumptionType;
  remainingDays: number | null;
  remainingMeals: number | null;
}): string {
  if (s.phase === "ENDED") return "Ended";
  if (s.phase === "CANCELLED") return "Cancelled";
  const parts: string[] = [];
  if (s.consumptionType === "MEAL" && s.remainingMeals !== null) {
    parts.push(`${s.remainingMeals} meals left`);
  }
  if (s.remainingDays !== null) {
    parts.push(`${s.remainingDays} days${s.consumptionType === "MEAL" ? " in window" : " left"}`);
  }
  return parts.join(" · ") || "No expiry";
}
