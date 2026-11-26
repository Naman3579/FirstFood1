import { apiFetch } from "./api-client";

export type PauseStatus = "OPEN" | "RESUMED" | "CANCELLED";

export type Pause = {
  id: string;
  subscriptionId: string;
  startDate: string;
  status: PauseStatus;
  /** The start date has been reached. A scheduled vacation that has not begun can still be called off. */
  started: boolean;
  resumedOn: string | null;
  pausedDays: number | null;
  creditedDays: number | null;
  /** OPEN only: what resuming TODAY would give back, worked out by the server. */
  projectedCreditDays: number | null;
  projectedExpiryDate: string | null;
};

export type Vacation = {
  subscriptionId: string;
  canStart: boolean;
  /** Plain-language reason when canStart is false. */
  blockedReason: string | null;
  baseExpiryDate: string | null;
  effectiveExpiryDate: string | null;
  open: Pause | null;
  history: Pause[];
};

/**
 * Customer vacation on their OWN subscription. The return date is unknown: a vacation stays open until the customer
 * resumes. The server works out every date and every day given back; the client sends at most a start date.
 */
export const vacationApi = {
  status(accessToken: string, subscriptionId: string): Promise<Vacation> {
    return apiFetch<Vacation>(`/subscriptions/${subscriptionId}/vacation`, { accessToken });
  },

  /** No start date = today. A later date (up to 60 days ahead) schedules it. */
  start(accessToken: string, subscriptionId: string, startDate?: string): Promise<Pause> {
    return apiFetch<Pause>(`/subscriptions/${subscriptionId}/vacation`, {
      method: "POST",
      accessToken,
      body: startDate ? { startDate } : {},
    });
  },

  /** Idempotent: repeating it reports how the vacation was resolved and adds no day twice. */
  resume(accessToken: string, subscriptionId: string): Promise<Pause> {
    return apiFetch<Pause>(`/subscriptions/${subscriptionId}/vacation/resume`, { method: "POST", accessToken, body: {} });
  },
};
