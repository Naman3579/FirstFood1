import { apiFetch, STAFF } from "./api-client";

export type AttendanceStatus = "PRESENT" | "ABSENT";
export type AttendanceSource = "SYSTEM" | "CUSTOMER" | "OWNER_CORRECTION";
export type AbsenceStatus = "DECLARED" | "CANCELLED" | "OVERRIDDEN";
/** Why a customer cannot change a day right now - decided by the server, shown as-is. */
export type ChangeBlock =
  | "PAST"
  | "SAME_DAY_NOT_ALLOWED"
  | "CUTOFF_PASSED"
  | "PROVIDER_CORRECTION"
  | "SUBSCRIPTION_NOT_ACTIVE";

export type DailySheetRow = {
  subscriptionId: string;
  membershipId: string;
  personId: string;
  customerName: string;
  planName: string;
  startDate: string;
  effectiveExpiryDate: string | null;
  status: AttendanceStatus;
  source: AttendanceSource;
  /** True when nothing is recorded: present only because attendance is opt-out. */
  assumed: boolean;
  absenceId: string | null;
  reason: string | null;
};

export type DailySheet = {
  date: string;
  expected: number;
  present: number;
  absent: number;
  rows: DailySheetRow[];
};

export type AttendanceHistoryEntry = {
  id: string;
  status: AttendanceStatus;
  source: AttendanceSource;
  absenceId: string | null;
  recordedBy: string | null;
  recordedAt: string;
  reason: string | null;
  supersedesId: string | null;
  current: boolean;
  supersededAt: string | null;
};

export type MyAttendanceDay = {
  date: string;
  status: AttendanceStatus;
  assumed: boolean;
  absenceId: string | null;
  reason: string | null;
  providerSet: boolean;
  changeable: boolean;
  lockedReason: ChangeBlock | null;
};

export type MyAbsence = {
  id: string;
  subscriptionId: string;
  date: string;
  status: AbsenceStatus;
  source: AttendanceSource;
  reason: string | null;
  declaredAt: string;
  cancelledAt: string | null;
  overriddenAt: string | null;
  overrideReason: string | null;
};

/** One day of a subscription as provider staff see it (no price/terms: attendance staff may be WORKERs). */
export type ProviderAttendanceDay = {
  date: string;
  status: AttendanceStatus;
  source: AttendanceSource;
  assumed: boolean;
  absenceId: string | null;
  reason: string | null;
  recordedBy: string | null;
  recordedAt: string | null;
};

/** An absence declaration as staff see it, including who acted on it. */
export type ProviderAbsence = {
  id: string;
  subscriptionId: string;
  date: string;
  status: AbsenceStatus;
  source: AttendanceSource;
  reason: string | null;
  declaredBy: string | null;
  declaredAt: string;
  cancelledAt: string | null;
  cancelledBy: string | null;
  overriddenAt: string | null;
  overriddenBy: string | null;
  overrideReason: string | null;
};

export type DeclareAbsenceResult = { absences: MyAbsence[]; anyCreated: boolean };

export const LOCKED_REASON_TEXT: Record<ChangeBlock, string> = {
  PAST: "This day has passed.",
  SAME_DAY_NOT_ALLOWED: "This provider does not allow same-day changes.",
  CUTOFF_PASSED: "The provider's cutoff time for today has passed.",
  PROVIDER_CORRECTION: "Set by the provider - ask them to change it.",
  SUBSCRIPTION_NOT_ACTIVE: "This subscription has ended or was cancelled.",
};

/**
 * One function per Phase 8 endpoint. Whether a day may still be changed is decided by the backend
 * (policy snapshot, cutoff, provider corrections) and arrives as `changeable` / `lockedReason`;
 * nothing here re-implements those rules.
 */
export const attendanceApi = {
  dailySheet(accessToken: string, providerId: string, date?: string): Promise<DailySheet> {
    const query = date ? `?date=${date}` : "";
    return apiFetch<DailySheet>(`${STAFF}/providers/${providerId}/attendance${query}`, { accessToken });
  },

  history(
    accessToken: string,
    providerId: string,
    subscriptionId: string,
    date: string,
  ): Promise<AttendanceHistoryEntry[]> {
    return apiFetch<AttendanceHistoryEntry[]>(
      `${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/attendance/${date}/history`,
      { accessToken },
    );
  },

  /** Provider staff decide a day. A reason is mandatory. */
  correct(
    accessToken: string,
    providerId: string,
    subscriptionId: string,
    date: string,
    status: AttendanceStatus,
    reason: string,
  ): Promise<unknown> {
    return apiFetch(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/attendance/${date}`, {
      method: "PUT",
      accessToken,
      body: { status, reason },
    });
  },

  /** GET /providers/{p}/subscriptions/{s}/attendance - one subscription's days as staff see them. */
  providerDays(
    accessToken: string,
    providerId: string,
    subscriptionId: string,
    from?: string,
    to?: string,
  ): Promise<ProviderAttendanceDay[]> {
    const query = from && to ? `?from=${from}&to=${to}` : "";
    return apiFetch<ProviderAttendanceDay[]>(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/attendance${query}`, {
      accessToken,
    });
  },

  /** GET /providers/{p}/subscriptions/{s}/absences - every declaration, with the staff accounts that acted. */
  providerAbsences(accessToken: string, providerId: string, subscriptionId: string): Promise<ProviderAbsence[]> {
    return apiFetch<ProviderAbsence[]>(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/absences`, { accessToken });
  },

  /** GET /subscriptions/{s}/absence - the customer's own declarations. */
  myAbsences(accessToken: string, subscriptionId: string): Promise<MyAbsence[]> {
    return apiFetch<MyAbsence[]>(`/subscriptions/${subscriptionId}/absence`, { accessToken });
  },

  myDays(accessToken: string, subscriptionId: string, from?: string, to?: string): Promise<MyAttendanceDay[]> {
    const query = from && to ? `?from=${from}&to=${to}` : "";
    return apiFetch<MyAttendanceDay[]>(`/subscriptions/${subscriptionId}/attendance${query}`, { accessToken });
  },

  declare(
    accessToken: string,
    subscriptionId: string,
    fromDate: string,
    toDate?: string,
    reason?: string,
  ): Promise<DeclareAbsenceResult> {
    return apiFetch<DeclareAbsenceResult>(`/subscriptions/${subscriptionId}/absence`, {
      method: "POST",
      accessToken,
      body: { fromDate, ...(toDate ? { toDate } : {}), ...(reason ? { reason } : {}) },
    });
  },

  cancelAbsence(accessToken: string, subscriptionId: string, absenceId: string): Promise<MyAbsence> {
    return apiFetch<MyAbsence>(`/subscriptions/${subscriptionId}/absence/${absenceId}/cancel`, {
      method: "POST",
      accessToken,
      body: {},
    });
  },
};

/** Today as "YYYY-MM-DD" in the browser's own calendar (display default only; the server decides "today"). */
export function isoDay(date: Date): string {
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${m}-${d}`;
}
