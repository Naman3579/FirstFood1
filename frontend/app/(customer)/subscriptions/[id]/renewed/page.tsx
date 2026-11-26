"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { CalendarDays, Check, Copy, Hash, Wallet } from "lucide-react";
import { Banner, Boundary } from "@/components/ui";
import { LedgerSummary, PaymentStatusBadge } from "@/components/domain/PaymentBits";
import { useQuery } from "@/lib/hooks";
import { paymentApi, PAYMENT_METHOD_LABEL, type Payment } from "@/lib/payment-api";
import { subscriptionApi } from "@/lib/subscription-api";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard can be blocked (insecure origin, permissions): the reference is still shown on screen.
    }
  }
  return (
    <button type="button" className="btn btn-sm" onClick={() => void copy()}>
      {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? "Copied" : "Copy"}
    </button>
  );
}

function LatestPayment({ p }: { p: Payment }) {
  return (
    <div className="card row between" style={{ gap: "1rem", background: "var(--surface-2, transparent)" }}>
      <div style={{ minWidth: 0 }}>
        <div className="small muted">{PAYMENT_METHOD_LABEL[p.paymentMethod]} · {formatMoney(p.amount, p.currency)} · {formatDateTime(p.submittedAt)}</div>
        <div className="strong truncate">{p.transactionReference ?? "No reference"}</div>
        <div style={{ marginTop: "0.25rem" }}><PaymentStatusBadge status={p.verificationStatus} /></div>
      </div>
      {p.transactionReference ? <CopyButton text={p.transactionReference} /> : null}
    </div>
  );
}

/** Where a renewal lands: what was bought, and the money position as the server computed it. */
export default function RenewedPage() {
  const { id } = useParams<{ id: string }>();
  const sub = useQuery((t) => subscriptionApi.myGet(t, id), [id]);
  const ledger = useQuery((t) => paymentApi.myLedger(t, id), [id]);
  const latest = ledger.data?.payments[0];

  return (
    <main className="page" style={{ maxWidth: "40rem" }}>
      <Boundary query={sub} rows={3}>
        {(s) => (
          <>
            <div className="stack stack-s" style={{ textAlign: "center", alignItems: "center" }}>
              <span className="avatar" style={{ width: "3.5rem", height: "3.5rem" }}>
                <Check size={28} />
              </span>
              <h1>Renewal successful</h1>
              <p className="muted">
                Your plan with <span className="strong">{s.providerName}</span> has been renewed.
              </p>
            </div>

            <section className="card stack">
              <div>
                <div className="small muted">Current plan</div>
                <h2>{s.terms.planName}</h2>
              </div>
              <dl className="kv">
                <dt><CalendarDays size={14} style={{ verticalAlign: "-2px" }} /> Validity</dt>
                <dd>{formatDate(s.startDate)} – {formatDate(s.effectiveExpiryDate)}</dd>
                <dt><Hash size={14} style={{ verticalAlign: "-2px" }} /> Subscription ID</dt>
                <dd className="num">{s.id.slice(0, 8).toUpperCase()}</dd>
              </dl>
            </section>

            <section className="card stack">
              <h2 className="row"><Wallet size={18} /> Financial ledger</h2>
              <Boundary query={ledger} rows={2}>
                {(l) => (
                  <>
                    <LedgerSummary ledger={l} />
                    {latest ? <LatestPayment p={latest} /> : null}
                    {l.pendingAmount > 0 ? (
                      <Banner tone="info">{formatMoney(l.pendingAmount, l.currency)} is waiting for the provider to verify it. It counts as paid once they do.</Banner>
                    ) : null}
                    {l.outstanding > 0 ? (
                      <p className="small muted">The balance can be paid any time. Add each payment from the subscription page.</p>
                    ) : null}
                  </>
                )}
              </Boundary>
            </section>

            <div className="stack stack-s">
              <Link href={`/subscriptions/${s.id}`} className="btn btn-primary btn-block">View this subscription</Link>
              <Link href="/home" className="btn btn-block">Return to home</Link>
            </div>
          </>
        )}
      </Boundary>
    </main>
  );
}
