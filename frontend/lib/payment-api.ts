import { apiFetch, STAFF } from "./api-client";

/** How the customer says they paid. UPI and bank transfers need a reference; cash and other may not have one. */
export type PaymentMethod = "UPI" | "BANK_TRANSFER" | "CASH" | "OTHER";
/** PENDING = the customer's claim, not yet checked. Only VERIFIED payments count as paid. */
export type PaymentVerificationStatus = "PENDING" | "VERIFIED" | "REJECTED";

export type Payment = {
  id: string;
  subscriptionId: string;
  subscriberName: string;
  transactionReference: string | null;
  amount: number;
  currency: string;
  paymentMethod: PaymentMethod;
  /** What the customer CLAIMED ("YYYY-MM-DD" / "HH:mm:ss", India time) - not a verified fact. */
  paymentDate: string;
  paymentTime: string;
  verificationStatus: PaymentVerificationStatus;
  /** When the server received it. */
  submittedAt: string;
  verifiedAt: string | null;
  /** Staff only; always null for the customer. */
  verifiedByAccountId: string | null;
  rejectionReason: string | null;
};

/** The balance as the server computed it: `outstanding = price - confirmedPaid`; pending money is NOT subtracted. */
export type PaymentLedger = {
  subscriptionId: string;
  currency: string;
  price: number;
  confirmedPaid: number;
  pendingAmount: number;
  outstanding: number;
  /** How much more can still be submitted (price minus verified and pending). */
  remainingToSubmit: number;
  payments: Payment[];
};

export type SubmitPaymentInput = {
  method: PaymentMethod;
  transactionReference?: string;
  amount: number;
  /** "YYYY-MM-DD", "HH:mm" - India time. */
  paymentDate: string;
  paymentTime: string;
  /** Same key on a retry returns the first payment instead of creating a second. */
  idempotencyKey?: string;
};

/**
 * Customer endpoints are scoped to the caller's own subscriptions; staff endpoints need SUBSCRIPTION_VIEW to read and
 * SUBSCRIPTION_MANAGE to verify or reject. Every rule (balance, duplicates, who may decide) lives in the backend.
 */
export const paymentApi = {
  myLedger(accessToken: string, subscriptionId: string): Promise<PaymentLedger> {
    return apiFetch<PaymentLedger>(`/subscriptions/${subscriptionId}/payments`, { accessToken });
  },

  submit(accessToken: string, subscriptionId: string, input: SubmitPaymentInput): Promise<Payment> {
    return apiFetch<Payment>(`/subscriptions/${subscriptionId}/payments`, { method: "POST", accessToken, body: input });
  },

  ledger(accessToken: string, providerId: string, subscriptionId: string): Promise<PaymentLedger> {
    return apiFetch<PaymentLedger>(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/payments`, { accessToken });
  },

  verify(accessToken: string, providerId: string, subscriptionId: string, paymentId: string): Promise<Payment> {
    return apiFetch<Payment>(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/payments/${paymentId}/verify`, {
      method: "POST",
      accessToken,
      body: {},
    });
  },

  reject(accessToken: string, providerId: string, subscriptionId: string, paymentId: string, reason: string): Promise<Payment> {
    return apiFetch<Payment>(`${STAFF}/providers/${providerId}/subscriptions/${subscriptionId}/payments/${paymentId}/reject`, {
      method: "POST",
      accessToken,
      body: { reason },
    });
  },
};

export const PAYMENT_METHOD_LABEL: Record<PaymentMethod, string> = {
  UPI: "UPI",
  BANK_TRANSFER: "Bank transfer",
  CASH: "Cash",
  OTHER: "Other",
};

export const PAYMENT_STATUS_LABEL: Record<PaymentVerificationStatus, string> = {
  PENDING: "Pending verification",
  VERIFIED: "Verified",
  REJECTED: "Rejected",
};

/** A fresh request key. Reuse one key across retries of the SAME payment; make a new one for the next payment. */
export function newIdempotencyKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `pay-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
