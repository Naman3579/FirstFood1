"use client";

import { useRef, useState } from "react";
import { Check, Plus, ShieldAlert, X } from "lucide-react";
import { Badge, Banner, ConfirmDialog, Field, ListSkeleton, Modal, Segmented, Spinner } from "@/components/ui";
import { useAction, useQuery } from "@/lib/hooks";
import {
  newIdempotencyKey,
  paymentApi,
  PAYMENT_METHOD_LABEL,
  PAYMENT_STATUS_LABEL,
  type Payment,
  type PaymentLedger,
  type PaymentMethod,
  type PaymentVerificationStatus,
  type SubmitPaymentInput,
} from "@/lib/payment-api";
import { formatDate, formatDateTime, formatMoney, nowTimeInIndia, todayInIndia } from "@/lib/format";

/* ------------------------------------------------------------------ the form draft (shared by the modal and the subscribe screen) */

/** What the customer has typed so far. Everything is a string until it is validated and sent. */
export type PaymentDraft = {
  method: PaymentMethod;
  amount: string;
  reference: string;
  date: string;
  time: string;
};

export type PaymentDraftErrors = { amount?: string; reference?: string; when?: string };

const METHOD_OPTIONS: { value: PaymentMethod; label: string }[] = [
  { value: "UPI", label: PAYMENT_METHOD_LABEL.UPI },
  { value: "BANK_TRANSFER", label: PAYMENT_METHOD_LABEL.BANK_TRANSFER },
  { value: "CASH", label: PAYMENT_METHOD_LABEL.CASH },
  { value: "OTHER", label: PAYMENT_METHOD_LABEL.OTHER },
];

const AMOUNT_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;
const REFERENCE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 /_.:#-]{0,149}$/;

/** A blank draft: paid just now, by UPI, for the given amount (the most common case). */
export function newDraft(defaultAmount: number): PaymentDraft {
  return {
    method: "UPI",
    amount: defaultAmount > 0 ? String(defaultAmount) : "",
    reference: "",
    date: todayInIndia(),
    time: nowTimeInIndia(),
  };
}

/** Same canonical form the server uses: letters and digits only, upper-case. */
function canonicalReference(ref: string): string {
  return ref.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
}

/** Per-method format, mirroring the server (PaymentReference). Returns a message, or undefined when fine. */
function referenceFormatError(method: PaymentMethod, ref: string): string | undefined {
  const key = canonicalReference(ref);
  if (method === "UPI") {
    return /^[0-9]{12}$/.test(key) ? undefined : "A UPI transaction ID (UTR) is exactly 12 digits. Spaces and dashes are fine.";
  }
  if (method === "BANK_TRANSFER") {
    return /^[A-Z0-9]{8,35}$/.test(key) ? undefined : "A bank transfer reference (UTR) is 8 to 35 letters and digits.";
  }
  return key ? undefined : "The receipt number needs at least one letter or digit.";
}

function referenceRequired(method: PaymentMethod): boolean {
  return method === "UPI" || method === "BANK_TRANSFER";
}

/**
 * Catches what can be caught before a round trip. The server repeats every one of these checks (and owns the real
 * rules: duplicates, overpayment); this only saves the customer a wait.
 */
export function validateDraft(d: PaymentDraft, maxAmount: number, currency: string): PaymentDraftErrors {
  const errors: PaymentDraftErrors = {};
  const amount = d.amount.trim();
  if (!AMOUNT_PATTERN.test(amount) || Number(amount) <= 0) {
    errors.amount = "Enter the amount you paid, e.g. 2000 or 2000.50.";
  } else if (Number(amount) > maxAmount) {
    errors.amount = `That is more than the ${formatMoney(maxAmount, currency)} still to be covered.`;
  }
  const ref = d.reference.trim();
  if (!ref && referenceRequired(d.method)) {
    errors.reference = "Enter the transaction ID / UTR so the provider can check it.";
  } else if (ref && !REFERENCE_PATTERN.test(ref)) {
    errors.reference = "Use letters, digits and / _ . : # - only.";
  } else if (ref) {
    errors.reference = referenceFormatError(d.method, ref);
  }
  if (!d.date || !d.time) {
    errors.when = "Enter the date and time of the payment.";
  } else if (d.date > todayInIndia() || (d.date === todayInIndia() && d.time > nowTimeInIndia())) {
    errors.when = "The payment can't be in the future.";
  }
  return errors;
}

export function hasErrors(e: PaymentDraftErrors): boolean {
  return Boolean(e.amount || e.reference || e.when);
}

export function draftToInput(d: PaymentDraft, idempotencyKey: string): SubmitPaymentInput {
  const ref = d.reference.trim();
  return {
    method: d.method,
    transactionReference: ref || undefined,
    amount: Number(d.amount.trim()),
    paymentDate: d.date,
    paymentTime: d.time,
    idempotencyKey,
  };
}

/** The payment form fields. Controlled; the parent owns the draft and decides when to show errors. */
export function PaymentFields({
  draft,
  onChange,
  errors,
  maxAmount,
  currency,
}: {
  draft: PaymentDraft;
  onChange: (next: PaymentDraft) => void;
  errors: PaymentDraftErrors;
  maxAmount: number;
  currency: string;
}) {
  return (
    <div className="stack">
      <Field label="Payment method">
        {() => <Segmented label="Payment method" value={draft.method} onChange={(method) => onChange({ ...draft, method })} options={METHOD_OPTIONS} />}
      </Field>
      <Field label={`Amount paid (${currency})`} error={errors.amount} hint={`Up to ${formatMoney(maxAmount, currency)}. Part payments are fine - add the rest later.`}>
        {(id) => (
          <input
            id={id}
            className="input"
            inputMode="decimal"
            placeholder="2000"
            value={draft.amount}
            aria-invalid={errors.amount ? true : undefined}
            onChange={(e) => onChange({ ...draft, amount: e.target.value.replace(/[^\d.]/g, "") })}
          />
        )}
      </Field>
      <Field
        label={referenceRequired(draft.method) ? "Transaction ID / UTR" : "Receipt number (optional)"}
        error={errors.reference}
        hint={draft.method === "UPI" ? "The 12-digit UTR / reference number from your payment app." : referenceRequired(draft.method) ? "From your bank statement or transfer receipt." : "If the provider gave you a receipt, enter its number."}
      >
        {(id) => (
          <input
            id={id}
            className="input"
            maxLength={150}
            placeholder={draft.method === "UPI" ? "123456789012" : ""}
            autoComplete="off"
            value={draft.reference}
            aria-invalid={errors.reference ? true : undefined}
            onChange={(e) => onChange({ ...draft, reference: e.target.value })}
          />
        )}
      </Field>
      <div className="form-grid">
        <Field label="Payment date" error={errors.when}>
          {(id) => <input id={id} type="date" className="input" max={todayInIndia()} value={draft.date} onChange={(e) => onChange({ ...draft, date: e.target.value })} />}
        </Field>
        <Field label="Payment time">
          {(id) => <input id={id} type="time" className="input" value={draft.time} onChange={(e) => onChange({ ...draft, time: e.target.value })} />}
        </Field>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ "paid already / pay later" choice (subscribe + renew) */

/** What the customer chose while buying: nothing to record yet, or a payment they have already made. */
export type PaymentIntent = { mode: "later" | "paid"; draft: PaymentDraft };

export function newIntent(price: number): PaymentIntent {
  return { mode: "later", draft: newDraft(price) };
}

/** Errors for the form, but only when the customer chose to record a payment now. */
export function intentErrors(intent: PaymentIntent, price: number, currency: string): PaymentDraftErrors {
  return intent.mode === "paid" ? validateDraft(intent.draft, price, currency) : {};
}

/**
 * The optional "Payment" section of the subscribe / renew dialogs. Paying later is the default, so skipping it costs
 * nothing; whatever is entered here is only a claim that the provider verifies later.
 */
export function PaymentChoice({
  intent,
  onChange,
  errors,
  price,
  currency,
}: {
  intent: PaymentIntent;
  onChange: (next: PaymentIntent) => void;
  errors: PaymentDraftErrors;
  price: number;
  currency: string;
}) {
  const entered = Number(intent.draft.amount);
  const balance = Number.isFinite(entered) && entered > 0 ? Math.max(0, price - entered) : price;
  return (
    <section className="stack stack-s" aria-label="Payment details">
      <h3>Payment details</h3>
      <Segmented
        label="When do you pay?"
        value={intent.mode}
        onChange={(mode) => onChange({ ...intent, mode })}
        options={[
          { value: "later", label: "I'll pay later" },
          { value: "paid", label: "I have already paid" },
        ]}
      />
      {intent.mode === "paid" ? (
        <>
          <PaymentFields draft={intent.draft} onChange={(draft) => onChange({ ...intent, draft })} errors={errors} maxAmount={price} currency={currency} />
          <p className="small muted">
            {formatMoney(Number.isFinite(entered) ? entered : 0, currency)} to be verified by the provider · balance {formatMoney(balance, currency)}
          </p>
        </>
      ) : (
        <p className="small muted">You can add a payment any time from the subscription page, as many times as you need.</p>
      )}
    </section>
  );
}

/**
 * Called AFTER the subscription exists. The subscription is the commercial event; the payment is attached to its id.
 * If saving the payment fails the subscription stays and the customer is told where to retry. Returns whether the
 * payment (if any) was saved.
 */
export async function attachIntendedPayment(
  run: ReturnType<typeof useAction>["run"],
  subscriptionId: string,
  intent: PaymentIntent,
  idempotencyKey: string,
): Promise<boolean> {
  if (intent.mode !== "paid") return true;
  const saved = await run((t) => paymentApi.submit(t, subscriptionId, draftToInput(intent.draft, idempotencyKey)), {
    success: "Payment submitted. It counts once the provider verifies it.",
  });
  return saved !== undefined;
}

/* ------------------------------------------------------------------ status + summary bits */

const STATUS_TONE: Record<PaymentVerificationStatus, "warn" | "ok" | "danger"> = {
  PENDING: "warn",
  VERIFIED: "ok",
  REJECTED: "danger",
};

export function PaymentStatusBadge({ status }: { status: PaymentVerificationStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{PAYMENT_STATUS_LABEL[status]}</Badge>;
}

export function LedgerSummary({ ledger }: { ledger: PaymentLedger }) {
  return (
    <dl className="kv">
      <dt>Plan price</dt>
      <dd className="num">{formatMoney(ledger.price, ledger.currency)}</dd>
      <dt>Paid &amp; verified</dt>
      <dd className="num">{formatMoney(ledger.confirmedPaid, ledger.currency)}</dd>
      {ledger.pendingAmount > 0 ? (
        <>
          <dt>Waiting for verification</dt>
          <dd className="num">{formatMoney(ledger.pendingAmount, ledger.currency)}</dd>
        </>
      ) : null}
      <dt className="strong">Outstanding balance</dt>
      <dd className="num strong">{formatMoney(ledger.outstanding, ledger.currency)}</dd>
    </dl>
  );
}

/** One payment row. `actions` is the staff verify/reject pair, or nothing for the customer. */
function PaymentRow({ p, actions }: { p: Payment; actions?: React.ReactNode }) {
  return (
    <div className="list-item" style={{ alignItems: "flex-start" }}>
      <div className="grow" style={{ minWidth: 0 }}>
        <div className="row" style={{ gap: "0.5rem" }}>
          <span className="strong num">{formatMoney(p.amount, p.currency)}</span>
          <span className="small muted">{PAYMENT_METHOD_LABEL[p.paymentMethod]}</span>
          <PaymentStatusBadge status={p.verificationStatus} />
        </div>
        {p.transactionReference ? <div className="small num truncate">Ref: {p.transactionReference}</div> : null}
        <div className="small muted">
          Paid {formatDate(p.paymentDate)} at {p.paymentTime.slice(0, 5)} (as stated by the customer) · submitted {formatDateTime(p.submittedAt)}
        </div>
        {p.verificationStatus === "REJECTED" && p.rejectionReason ? (
          <div className="small" style={{ color: "var(--danger)", marginTop: "0.2rem" }}>Rejected: {p.rejectionReason}</div>
        ) : null}
        {p.verificationStatus === "VERIFIED" && p.verifiedAt ? <div className="small muted">Verified {formatDateTime(p.verifiedAt)}</div> : null}
      </div>
      {actions}
    </div>
  );
}

/* ------------------------------------------------------------------ customer */

/** "Add payment" form for an existing subscription - the customer paying later, or in instalments. */
function AddPaymentModal({ subscriptionId, ledger, onClose, onSaved }: { subscriptionId: string; ledger: PaymentLedger; onClose: () => void; onSaved: () => void }) {
  const [draft, setDraft] = useState(() => newDraft(ledger.remainingToSubmit));
  const [showErrors, setShowErrors] = useState(false);
  // One key for this form: if the request is retried (double tap, flaky network) the server returns the same payment.
  const key = useRef(newIdempotencyKey());
  const { run, busy } = useAction();
  const errors = validateDraft(draft, ledger.remainingToSubmit, ledger.currency);

  async function save() {
    setShowErrors(true);
    if (hasErrors(errors)) return;
    const saved = await run((t) => paymentApi.submit(t, subscriptionId, draftToInput(draft, key.current)), {
      success: "Payment submitted. It counts once the provider verifies it.",
    });
    if (saved) {
      onSaved();
      onClose();
    }
  }

  return (
    <Modal title="Add payment" onClose={onClose}>
      <p className="muted small">Tell us about a payment you have already made. The provider will check it before it counts as paid.</p>
      <PaymentFields draft={draft} onChange={setDraft} errors={showErrors ? errors : {}} maxAmount={ledger.remainingToSubmit} currency={ledger.currency} />
      <div className="modal-actions">
        <button className="btn" onClick={onClose} disabled={busy}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void save()} disabled={busy}>
          {busy ? <Spinner /> : <Check size={16} />} Submit payment
        </button>
      </div>
    </Modal>
  );
}

/** Payments & Transactions on the customer's subscription page. */
export function CustomerPaymentsCard({ subscriptionId }: { subscriptionId: string }) {
  const ledger = useQuery((t) => paymentApi.myLedger(t, subscriptionId), [subscriptionId]);
  const [adding, setAdding] = useState(false);
  const l = ledger.data;

  return (
    <section className="card stack">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2>Payments &amp; transactions</h2>
        {l && l.remainingToSubmit > 0 ? (
          <button className="btn btn-sm" onClick={() => setAdding(true)}>
            <Plus size={16} /> Add payment
          </button>
        ) : null}
      </div>
      {ledger.error ? <Banner tone="danger">{ledger.error}</Banner> : null}
      {!l ? (
        ledger.error ? null : <ListSkeleton rows={2} />
      ) : (
        <>
          <LedgerSummary ledger={l} />
          {l.payments.length === 0 ? (
            <p className="small muted">No payments added yet. Paid already, or paying in instalments? Use “Add payment” whenever you pay.</p>
          ) : (
            <div className="list" style={{ margin: "0 -1.1rem" }}>
              {l.payments.map((p) => (
                <PaymentRow key={p.id} p={p} />
              ))}
            </div>
          )}
          <p className="small faint">A payment you add only counts as paid after the provider verifies it.</p>
        </>
      )}
      {adding && l ? <AddPaymentModal subscriptionId={subscriptionId} ledger={l} onClose={() => setAdding(false)} onSaved={() => void ledger.reload()} /> : null}
    </section>
  );
}

/* ------------------------------------------------------------------ staff */

/** Payments on the staff subscription page: the claims to check, and the verified/rejected outcome. */
export function StaffPaymentsCard({ providerId, subscriptionId, canManage }: { providerId: string; subscriptionId: string; canManage: boolean }) {
  const ledger = useQuery((t) => paymentApi.ledger(t, providerId, subscriptionId), [providerId, subscriptionId]);
  const { run, busy } = useAction();
  const [rejecting, setRejecting] = useState<Payment | null>(null);
  const [reason, setReason] = useState("");
  const l = ledger.data;

  async function verify(p: Payment) {
    if (await run((t) => paymentApi.verify(t, providerId, subscriptionId, p.id), { success: "Payment verified." })) await ledger.reload();
  }

  async function reject() {
    if (!rejecting) return;
    const done = await run((t) => paymentApi.reject(t, providerId, subscriptionId, rejecting.id, reason.trim()), { success: "Payment rejected." });
    if (done) {
      setRejecting(null);
      setReason("");
      await ledger.reload();
    }
  }

  return (
    <section className="card stack">
      <h2>Payments &amp; transactions</h2>
      {ledger.error ? <Banner tone="danger">{ledger.error}</Banner> : null}
      {!l ? (
        ledger.error ? null : <ListSkeleton rows={2} />
      ) : (
        <>
          <LedgerSummary ledger={l} />
          <p className="small faint row" style={{ gap: "0.4rem" }}>
            <ShieldAlert size={14} /> The transaction ID and the payment date and time are what the customer typed. Check them against your bank or UPI statement before verifying.
          </p>
          {l.payments.length === 0 ? (
            <p className="small muted">The customer hasn&apos;t submitted any payment for this subscription.</p>
          ) : (
            <div className="list" style={{ margin: "0 -1.1rem" }}>
              {l.payments.map((p) => (
                <PaymentRow
                  key={p.id}
                  p={p}
                  actions={
                    canManage && p.verificationStatus === "PENDING" ? (
                      <div className="row" style={{ gap: "0.4rem" }}>
                        <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void verify(p)}>
                          <Check size={14} /> Verify
                        </button>
                        <button className="btn btn-sm" disabled={busy} onClick={() => setRejecting(p)}>
                          <X size={14} /> Reject
                        </button>
                      </div>
                    ) : null
                  }
                />
              ))}
            </div>
          )}
        </>
      )}
      {rejecting ? (
        <ConfirmDialog
          title="Reject this payment?"
          confirmLabel="Reject payment"
          danger
          busy={busy}
          onClose={() => { setRejecting(null); setReason(""); }}
          onConfirm={() => { if (reason.trim()) void reject(); }}
        >
          <p>
            {formatMoney(rejecting.amount, rejecting.currency)} · {rejecting.transactionReference ?? PAYMENT_METHOD_LABEL[rejecting.paymentMethod]}. The customer will see your reason and can submit it again.
          </p>
          <Field label="Reason" hint="Required. For example: amount doesn't match the statement.">
            {(id) => <input id={id} className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </Field>
        </ConfirmDialog>
      ) : null}
    </section>
  );
}
