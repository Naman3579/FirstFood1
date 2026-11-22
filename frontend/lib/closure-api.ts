import { apiFetch, STAFF } from "./api-client";

export type ClosureStatus = "DECLARED" | "CANCELLED";

export type ClosureAffected = {
  subscriptionId: string;
  membershipId: string;
  customerName: string;
  planName: string;
  previousExpiryDate: string;
  newExpiryDate: string;
  days: number;
};

export type ClosurePreview = {
  startDate: string;
  endDate: string;
  closureDays: number;
  affectedCount: number;
  totalDays: number;
  affected: ClosureAffected[];
};

export type Closure = {
  id: string;
  providerId: string;
  startDate: string;
  endDate: string;
  closureDays: number;
  reason: string | null;
  /** The owner's explicit choice: were eligible subscriptions extended for these days? */
  extendSubscriptions: boolean;
  status: ClosureStatus;
  processing: "COMPLETED" | "NOT_REQUESTED" | "CANCELLED";
  createdBy: string;
  createdAt: string;
  cancelledAt: string | null;
  affectedCount: number;
  totalDaysApplied: number;
  affected: ClosureAffected[];
};

export type DeclareClosureInput = {
  startDate: string;
  endDate: string;
  reason?: string;
  /** Required: there is no default. true = extend every eligible DAY subscription; false = record the closure only. */
  extendSubscriptions: boolean;
};

/** Provider closures. The server decides who is eligible and by how many days; the client only chooses dates and whether to extend. */
export const closureApi = {
  list(accessToken: string, providerId: string): Promise<Closure[]> {
    return apiFetch<Closure[]>(`${STAFF}/providers/${providerId}/closures`, { accessToken });
  },

  preview(accessToken: string, providerId: string, from: string, to: string): Promise<ClosurePreview> {
    return apiFetch<ClosurePreview>(`${STAFF}/providers/${providerId}/closures/preview?from=${from}&to=${to}`, { accessToken });
  },

  /** Repeating the same declaration returns the recorded closure and changes nothing. */
  declare(accessToken: string, providerId: string, input: DeclareClosureInput): Promise<Closure> {
    return apiFetch<Closure>(`${STAFF}/providers/${providerId}/closures`, { method: "POST", accessToken, body: input });
  },

  /** Only a closure that extended nothing can be cancelled. */
  cancel(accessToken: string, providerId: string, closureId: string): Promise<Closure> {
    return apiFetch<Closure>(`${STAFF}/providers/${providerId}/closures/${closureId}/cancel`, { method: "POST", accessToken, body: {} });
  },
};
