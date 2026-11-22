"use client";

import { useEffect, useState } from "react";
import { CalendarOff, Plus, XCircle } from "lucide-react";
import { Badge, Banner, Boundary, ConfirmDialog, Empty, Field, Modal, PageHeader, Spinner } from "@/components/ui";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { closureApi, type Closure, type ClosurePreview } from "@/lib/closure-api";
import { addDays, formatDate, todayInIndia } from "@/lib/format";

/**
 * Declaring a closure is the owner's explicit decision: "extend subscriptions for these days" has NO default, so
 * the form cannot be submitted until one of the two options is chosen. The preview is the server's dry run: the same
 * eligibility and day counts the declaration will apply.
 */
function DeclareModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { providerId } = useProvider();
  const today = todayInIndia();
  const [from, setFrom] = useState(addDays(today, 1));
  const [to, setTo] = useState(addDays(today, 1));
  const [reason, setReason] = useState("");
  const [extend, setExtend] = useState<boolean | null>(null);
  const [preview, setPreview] = useState<ClosurePreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const { run, busy } = useAction();
  const invalid = !from || !to || to < from;

  useEffect(() => {
    setPreview(null);
    setPreviewError(null);
    if (invalid || extend !== true) return;
    let live = true;
    void run((t) => closureApi.preview(t, providerId, from, to), { quiet: true }).then((p) => {
      if (!live) return;
      if (p) setPreview(p);
      else setPreviewError("Could not load the preview. Check the dates and try again.");
    });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to, extend, providerId]);

  async function submit() {
    if (extend === null) return;
    const res = await run((t) => closureApi.declare(t, providerId, { startDate: from, endDate: to, reason: reason.trim() || undefined, extendSubscriptions: extend }), {
      success: extend ? "Closure recorded and subscriptions extended." : "Closure recorded.",
    });
    if (res) {
      onDone();
      onClose();
    }
  }

  return (
    <Modal title="Declare a closure" onClose={onClose}>
      <p className="small muted">Choose the first and last day the kitchen is closed. A closure covers at most 31 days.</p>
      <div className="form-grid">
        <Field label="First closed day">{(id) => <input id={id} type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} />}</Field>
        <Field label="Last closed day" error={to < from ? "Must be on or after the first day" : null}>
          {(id) => <input id={id} type="date" className="input" value={to} min={from} onChange={(e) => setTo(e.target.value)} />}
        </Field>
      </div>
      <Field label="Reason (optional)">{(id) => <input id={id} className="input" maxLength={200} placeholder="Festival holiday" value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>

      <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="strong">Extend customers' subscriptions for these days?</legend>
        <label className="row" style={{ gap: "0.5rem", alignItems: "flex-start" }}>
          <input type="radio" name="extend" checked={extend === true} onChange={() => setExtend(true)} />
          <span><strong>Yes, extend.</strong> <span className="muted small">Every confirmed day plan running during the closure gets the closed days added to its end date.</span></span>
        </label>
        <label className="row" style={{ gap: "0.5rem", alignItems: "flex-start" }}>
          <input type="radio" name="extend" checked={extend === false} onChange={() => setExtend(false)} />
          <span><strong>No, just record it.</strong> <span className="muted small">No subscription changes.</span></span>
        </label>
      </fieldset>

      {extend === true && !invalid ? (
        preview ? (
          <Banner tone="info">
            {preview.affectedCount === 0
              ? "No subscription is eligible for these days."
              : `${preview.affectedCount} subscription(s) would be extended, ${preview.totalDays} day(s) in total.`}
          </Banner>
        ) : previewError ? <Banner tone="danger">{previewError}</Banner> : <Spinner />
      ) : null}
      {extend === true && preview && preview.affected.length > 0 ? (
        <ul className="small" style={{ maxHeight: "12rem", overflow: "auto", margin: 0, paddingLeft: "1.1rem" }}>
          {preview.affected.map((a) => (
            <li key={a.subscriptionId}>{a.customerName} · {a.planName}: {formatDate(a.previousExpiryDate)} → {formatDate(a.newExpiryDate)} (+{a.days})</li>
          ))}
        </ul>
      ) : null}

      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || invalid || extend === null} onClick={() => void submit()}>
          {busy ? <Spinner /> : <CalendarOff size={16} />} Declare closure
        </button>
      </div>
    </Modal>
  );
}

export default function ClosuresPage() {
  const { providerId, can, provider } = useProvider();
  const closures = useQuery((t) => closureApi.list(t, providerId), [providerId]);
  const { run, busy } = useAction();
  const [declaring, setDeclaring] = useState(false);
  const [cancelling, setCancelling] = useState<Closure | null>(null);
  const manage = can("SUBSCRIPTION_MANAGE") && provider.status !== "CLOSED";

  async function cancel(c: Closure) {
    if (await run((t) => closureApi.cancel(t, providerId, c.id), { success: "Closure cancelled." })) {
      setCancelling(null);
      await closures.reload();
    }
  }

  return (
    <main className="page" style={{ maxWidth: "50rem" }}>
      <PageHeader
        title="Closures"
        sub="Days the kitchen is closed, and whether customers were given those days back."
        actions={manage ? <button className="btn btn-primary" onClick={() => setDeclaring(true)}><Plus size={18} /> Declare closure</button> : undefined}
      />
      <Boundary query={closures} rows={3}>
        {(list) =>
          list.length === 0 ? (
            <Empty icon={<CalendarOff size={22} />} title="No closures yet">Declare a closure when the kitchen will be shut for a day or more.</Empty>
          ) : (
            <div className="stack">
              {list.map((c) => (
                <section key={c.id} className="card stack">
                  <div className="row between">
                    <div>
                      <div className="strong">{formatDate(c.startDate)}{c.endDate !== c.startDate ? ` - ${formatDate(c.endDate)}` : ""}</div>
                      <div className="small muted">{c.closureDays} day(s){c.reason ? ` · ${c.reason}` : ""}</div>
                    </div>
                    <span className="row" style={{ gap: "0.4rem" }}>
                      {c.status === "CANCELLED" ? <Badge tone="neutral">Cancelled</Badge> : c.extendSubscriptions ? <Badge tone="ok">Extended</Badge> : <Badge tone="info">Recorded only</Badge>}
                    </span>
                  </div>
                  {c.extendSubscriptions && c.status === "DECLARED" ? (
                    <p className="small muted">{c.affectedCount} subscription(s) extended, {c.totalDaysApplied} day(s) given back in total.</p>
                  ) : null}
                  {manage && c.status === "DECLARED" && c.affectedCount === 0 ? (
                    <div><button className="btn btn-sm" disabled={busy} onClick={() => setCancelling(c)}><XCircle size={16} /> Cancel closure</button></div>
                  ) : null}
                </section>
              ))}
            </div>
          )
        }
      </Boundary>
      {declaring ? <DeclareModal onClose={() => setDeclaring(false)} onDone={() => void closures.reload()} /> : null}
      {cancelling ? (
        <ConfirmDialog title="Cancel this closure?" confirmLabel="Cancel closure" danger busy={busy} onConfirm={() => void cancel(cancelling)} onClose={() => setCancelling(null)}>
          <p className="small">This closure extended no subscriptions, so cancelling it changes nothing for customers.</p>
        </ConfirmDialog>
      ) : null}
    </main>
  );
}
