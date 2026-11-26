"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { Ban, CalendarClock, Check, Pencil, RefreshCw, Sparkles, X } from "lucide-react";
import { BackLink, Badge, Banner, Boundary, ConfirmDialog, Field, Modal, Spinner } from "@/components/ui";
import { PhaseBadge } from "@/components/domain/status";
import { Progress, TermsList, VerificationBadge } from "@/components/domain/SubscriptionBits";
import { StaffPaymentsCard } from "@/components/domain/PaymentBits";
import { AttendanceCorrection } from "@/components/domain/AttendanceCorrection";
import { MonthCalendar, monthBounds, monthOf, type CalCell } from "@/components/domain/MonthCalendar";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { attendanceApi } from "@/lib/attendance-api";
import { BLOCK_TEXT, extensionApi } from "@/lib/extension-api";
import { planApi, describeQuantity } from "@/lib/plan-api";
import { describeRemaining, subscriptionApi, type Subscription } from "@/lib/subscription-api";
import { addDays, daysBetween, formatDate, formatDateTime, formatDayShort, formatMoney, todayInIndia } from "@/lib/format";

function RenewModal({ sub, providerId, onClose, onRenewed }: { sub: Subscription; providerId: string; onClose: () => void; onRenewed: (s: Subscription) => void }) {
  const plans = useQuery((t) => planApi.list(t, providerId, false), [providerId]);
  const [planId, setPlanId] = useState("");
  // The server starts a renewal today unless told otherwise, and never before the day after the old plan ended.
  const today = todayInIndia();
  const earliest = sub.effectiveExpiryDate && addDays(sub.effectiveExpiryDate, 1) > today ? addDays(sub.effectiveExpiryDate, 1) : today;
  const [start, setStart] = useState("");
  const { run, busy } = useAction();

  return (
    <Modal title="Renew subscription" onClose={onClose}>
      <p className="muted small">The renewal starts {earliest === today ? "today" : formatDate(earliest)} unless you pick a later date. Leave the plan as is to repeat {sub.terms.planName}.</p>
      <Field label="Plan">
        {(id) => (
          <select id={id} className="select" value={planId} onChange={(e) => setPlanId(e.target.value)} disabled={plans.data === undefined}>
            <option value="">Same plan - {sub.terms.planName}</option>
            {(plans.data ?? []).map((p) => (
              <option key={p.id} value={p.id}>{p.name} · {describeQuantity(p)} · {formatMoney(p.price, p.currency)}</option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Start date (optional)">{(id) => <input id={id} type="date" className="input" value={start} min={earliest} onChange={(e) => setStart(e.target.value)} />}</Field>
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button
          className="btn btn-primary"
          disabled={busy}
          onClick={async () => {
            const renewed = await run((t) => subscriptionApi.renew(t, providerId, sub.id, { planId: planId || undefined, startDate: start || undefined }), { success: "Subscription renewed." });
            if (renewed) onRenewed(renewed);
          }}
        >
          {busy ? <Spinner /> : <RefreshCw size={16} />} Renew
        </button>
      </div>
    </Modal>
  );
}

function ExtensionPanel({ sub, onApplied }: { sub: Subscription; onApplied: () => void }) {
  const { providerId, can } = useProvider();
  const status = useQuery((t) => extensionApi.status(t, providerId, sub.id), [providerId, sub.id]);
  const events = useQuery((t) => extensionApi.events(t, providerId, sub.id), [providerId, sub.id]);
  const { run, busy } = useAction();
  const s = status.data;
  if (!s) return status.error ? <Banner tone="danger">{status.error}</Banner> : null;

  async function apply() {
    const res = await run((t) => extensionApi.apply(t, providerId, sub.id));
    if (res) {
      status.setData(res.status);
      await events.reload();
      onApplied();
    }
  }

  return (
    <section className="card stack">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 className="row"><Sparkles size={18} /> Extension for absences</h2>
        {s.canApply && can("EXTENSION_MANAGE") ? (
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void apply()}>
            {busy ? <Spinner /> : null} Apply +{s.pendingExtensionDays} day{s.pendingExtensionDays === 1 ? "" : "s"}
          </button>
        ) : null}
      </div>
      {s.blockedReason ? (
        <p className="muted small">{BLOCK_TEXT[s.blockedReason]}</p>
      ) : (
        <>
          <p className="small muted">Absences of {s.minConsecutiveAbsenceDays}+ days in a row are added to the end. Counted through {formatDate(s.countedThrough)}.</p>
          <div className="grid grid-stats">
            <div className="stat"><span className="stat-label">Absent days</span><span className="stat-value">{s.countedAbsentDays}</span></div>
            <div className="stat"><span className="stat-label">Eligible</span><span className="stat-value">{s.eligibleAbsenceDays}</span></div>
            <div className="stat"><span className="stat-label">Applied</span><span className="stat-value">{s.appliedExtensionDays}</span></div>
            <div className="stat"><span className="stat-label">Pending</span><span className="stat-value">{s.pendingExtensionDays}</span></div>
          </div>
          {s.qualifyingRuns.length > 0 ? (
            <div className="small">
              <span className="muted">Qualifying stretches: </span>
              {s.qualifyingRuns.map((r) => `${formatDayShort(r.from)} → ${formatDayShort(r.to)} (${r.days}d)`).join(" · ")}
            </div>
          ) : null}
          {s.pendingExtensionDays > 0 ? <Banner tone="info">Applying moves the end date from {formatDate(s.effectiveExpiryDate)} to {formatDate(s.projectedExpiryDate)}{s.capped ? ` (capped at ${formatDate(s.maximumExpiryDate)})` : ""}.</Banner> : null}
          {s.overApplied ? <Banner tone="warn">{s.overAppliedDays} extension day(s) were applied that are no longer supported by the attendance records.</Banner> : null}
        </>
      )}
      {events.data && events.data.length > 0 ? (
        <>
          <hr className="divider" />
          <ul className="timeline">
            {events.data.map((e) => (
              <li key={e.id}>
                <div className="strong">#{e.sequenceNo} · +{e.appliedExtensionDays}d · {formatDate(e.previousExpiryDate)} → {formatDate(e.newExpiryDate)} {e.kind === "OVER_APPLIED" ? <Badge tone="warn">Over-applied</Badge> : null}</div>
                <div className="small muted">{formatDateTime(e.createdAt)} · {e.triggerSource === "SYSTEM" ? "Automatic" : "Staff"}{e.capped ? " · capped" : ""}</div>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}

function AttendanceSection({ sub }: { sub: Subscription }) {
  const { providerId, can } = useProvider();
  const today = todayInIndia();
  const last = sub.effectiveExpiryDate;
  const [month, setMonth] = useState(today < sub.startDate ? monthOf(sub.startDate) : last && today > last ? monthOf(last) : monthOf(today));
  const [selected, setSelected] = useState<string | null>(null);
  const { first, last: monthLast } = monthBounds(month);
  const from = first < sub.startDate ? sub.startDate : first;
  const to = last && monthLast > last ? last : monthLast;
  const days = useQuery((t) => attendanceApi.providerDays(t, providerId, sub.id, from, to), [providerId, sub.id, from, to], { enabled: from <= to });
  const absences = useQuery((t) => attendanceApi.providerAbsences(t, providerId, sub.id), [providerId, sub.id]);
  const cells = useMemo(() => {
    const m = new Map<string, CalCell>();
    days.data?.forEach((d) => m.set(d.date, { status: d.status, locked: d.source === "OWNER_CORRECTION" }));
    return m;
  }, [days.data]);
  const sel = selected ? days.data?.find((d) => d.date === selected) : undefined;

  return (
    <section className="card stack">
      <h2>Attendance</h2>
      <MonthCalendar month={month} onMonth={(m) => { setMonth(m); setSelected(null); }} cells={cells} selected={selected} onSelect={setSelected} rangeStart={sub.startDate} rangeEnd={last} minMonth={monthOf(sub.startDate)} maxMonth={last ? monthOf(last) : undefined} />
      {sel && selected ? (
        <div className="card row between" style={{ background: "var(--surface-2)", boxShadow: "none" }}>
          <div>
            <div className="strong">{formatDayShort(selected)}</div>
            <div className="row" style={{ gap: "0.4rem", marginTop: "0.25rem" }}>
              <Badge tone={sel.status === "PRESENT" ? "ok" : "danger"}>{sel.status === "PRESENT" ? "Present" : "Absent"}</Badge>
              <span className="small muted">{sel.assumed ? "Default (not recorded)" : sel.source === "CUSTOMER" ? "Declared by customer" : sel.source === "OWNER_CORRECTION" ? "Corrected by staff" : "System"}</span>
            </div>
            {sel.reason ? <p className="small muted" style={{ marginTop: "0.3rem" }}>“{sel.reason}”</p> : null}
          </div>
          <CorrectButton sub={sub} date={selected} status={sel.status} canManage={can("ATTENDANCE_MANAGE")} onSaved={() => { void days.reload(); void absences.reload(); }} />
        </div>
      ) : (
        <p className="small faint">Select a day to see its history or correct it.</p>
      )}
      {absences.data && absences.data.length > 0 ? (
        <details>
          <summary className="strong" style={{ cursor: "pointer" }}>Declared absences ({absences.data.filter((a) => a.status === "DECLARED").length} active)</summary>
          <ul className="list" style={{ marginTop: "0.5rem" }}>
            {absences.data.map((a) => (
              <li key={a.id} className="small">
                <span className="grow">{formatDayShort(a.date)}{a.reason ? ` · ${a.reason}` : ""}</span>
                <Badge tone={a.status === "DECLARED" ? "danger" : "neutral"}>{a.status.toLowerCase()}</Badge>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

function CorrectButton({ sub, date, status, canManage, onSaved }: { sub: Subscription; date: string; status: "PRESENT" | "ABSENT"; canManage: boolean; onSaved: () => void }) {
  const { providerId } = useProvider();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Pencil size={14} /> {canManage ? "Correct" : "History"}</button>
      {open ? <AttendanceCorrection providerId={providerId} subscriptionId={sub.id} customerName={sub.customerName} date={date} current={status} canManage={canManage} onClose={() => setOpen(false)} onSaved={onSaved} /> : null}
    </>
  );
}

export default function StaffSubscriptionPage() {
  const { id } = useParams<{ id: string }>();
  const { providerId, can, provider } = useProvider();
  const router = useRouter();
  const sub = useQuery((t) => subscriptionApi.get(t, providerId, id), [providerId, id]);
  const [renewing, setRenewing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const { run, busy } = useAction();
  const manage = can("SUBSCRIPTION_MANAGE") && provider.status !== "CLOSED";

  return (
    <main className="page" style={{ maxWidth: "52rem" }}>
      <BackLink href={`/staff/${providerId}/subscriptions`}>Subscriptions</BackLink>
      <Boundary query={sub} rows={3}>
        {(s) => {
          const total = s.consumptionType === "MEAL" ? (s.terms.purchasedMeals ?? 0) : s.effectiveExpiryDate ? daysBetween(s.startDate, s.effectiveExpiryDate) + 1 : 0;
          const left = s.consumptionType === "MEAL" ? (s.remainingMeals ?? 0) : (s.remainingDays ?? 0);
          return (
            <>
              <div className="card stack">
                <div className="row between" style={{ alignItems: "flex-start" }}>
                  <div style={{ minWidth: 0 }}>
                    <h1 className="truncate">{s.customerName}</h1>
                    <p className="muted">{s.terms.planName} · <Link href={`/staff/${providerId}/customers/${s.membershipId}`}>View customer</Link></p>
                  </div>
                  <span className="row" style={{ gap: "0.4rem" }}><VerificationBadge verification={s.verification} /><PhaseBadge phase={s.phase} /></span>
                </div>
                {s.verification?.status === "PENDING" ? (
                  <Banner tone="warn">
                    <div className="stack stack-s">
                      <span>The customer bought this themselves. Confirm it once you have agreed the plan and start date, or reject it with a reason.</span>
                      {manage ? (
                        <span className="row">
                          <button className="btn btn-primary btn-sm" disabled={busy} onClick={async () => { const done = await run((t) => subscriptionApi.verify(t, providerId, id), { success: "Subscription confirmed." }); if (done) sub.setData(done); }}>{busy ? <Spinner /> : <Check size={14} />} Confirm</button>
                          <button className="btn btn-sm" disabled={busy} onClick={() => { setReason(""); setRejecting(true); }}><X size={14} /> Reject</button>
                        </span>
                      ) : null}
                    </div>
                  </Banner>
                ) : null}
                {s.verification?.status === "EXPIRED" ? <Banner tone="warn">Not confirmed in time: this request was cancelled automatically and its seat and dates were freed. The customer can ask again.</Banner> : null}
                {s.verification?.status === "REJECTED" ? <Banner tone="danger">Rejected{s.verification.rejectionReason ? ` - “${s.verification.rejectionReason}”` : ""}.</Banner> : null}
                {s.phase === "RUNNING" || s.phase === "UPCOMING" ? (
                  <>
                    <div className="row between small"><span className="strong">{describeRemaining(s)}</span><span className="muted">Ends {formatDate(s.effectiveExpiryDate)}</span></div>
                    <Progress value={Math.max(0, total - left)} max={total} label="Plan progress" />
                  </>
                ) : null}
                {manage && (s.renewable || s.cancellable) ? (
                  <div className="row">
                    {s.renewable ? <button className="btn btn-primary" onClick={() => setRenewing(true)}><RefreshCw size={16} /> Renew</button> : null}
                    {s.cancellable ? <button className="btn" onClick={() => setCancelling(true)}><Ban size={16} /> Cancel subscription</button> : null}
                  </div>
                ) : null}
                {s.renewed ? <Banner tone="ok">This plan has already been renewed.</Banner> : null}
                {s.status === "CANCELLED" ? <Banner tone="warn">Cancelled {formatDateTime(s.cancelledAt)}{s.cancellationReason ? ` - “${s.cancellationReason}”` : ""}.</Banner> : null}
              </div>

              <section className="card stack">
                <h2>Details</h2>
                <dl className="kv">
                  <dt>Plan type</dt><dd>{s.consumptionType === "MEAL" ? `${s.terms.purchasedMeals} meals` : `${s.terms.purchasedDays} days`}</dd>
                  <dt>Started</dt><dd>{formatDate(s.startDate)}</dd>
                  <dt>Original end</dt><dd>{formatDate(s.baseExpiryDate)}</dd>
                  <dt>Current end</dt><dd>{formatDate(s.effectiveExpiryDate)}</dd>
                  <dt>Latest possible end</dt><dd>{formatDate(s.maximumExpiryDate)}</dd>
                  <dt>Price</dt><dd className="num">{formatMoney(s.terms.price, s.terms.currency)}</dd>
                  <dt>Created</dt><dd>{formatDateTime(s.createdAt)}</dd>
                </dl>
                <hr className="divider" />
                <h3 className="row" style={{ gap: "0.4rem" }}><CalendarClock size={16} /> Terms (policy v{s.terms.policyVersion})</h3>
                <TermsList terms={s.terms} />
              </section>

              <StaffPaymentsCard providerId={providerId} subscriptionId={s.id} canManage={manage} />

              {can("ATTENDANCE_VIEW") ? <AttendanceSection sub={s} /> : null}
              {s.consumptionType === "DAY" && can("EXTENSION_VIEW") ? <ExtensionPanel sub={s} onApplied={() => void sub.reload()} /> : null}

              {renewing ? <RenewModal sub={s} providerId={providerId} onClose={() => setRenewing(false)} onRenewed={(n) => { setRenewing(false); router.push(`/staff/${providerId}/subscriptions/${n.id}`); }} /> : null}
              {rejecting ? (
                <ConfirmDialog
                  title="Reject this subscription?"
                  confirmLabel="Reject"
                  danger
                  busy={busy}
                  onClose={() => setRejecting(false)}
                  onConfirm={async () => {
                    if (!reason.trim()) return;
                    const done = await run((t) => subscriptionApi.reject(t, providerId, id, reason.trim()), { success: "Subscription rejected." });
                    if (done) {
                      sub.setData(done);
                      setRejecting(false);
                    }
                  }}
                >
                  <p>{s.customerName}&apos;s subscription will be cancelled and they will see your reason.</p>
                  <Field label="Reason">{(fid) => <input id={fid} className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
                </ConfirmDialog>
              ) : null}
              {cancelling ? (
                <ConfirmDialog
                  title="Cancel this subscription?"
                  confirmLabel="Cancel subscription"
                  danger
                  busy={busy}
                  onClose={() => setCancelling(false)}
                  onConfirm={async () => {
                    const done = await run((t) => subscriptionApi.cancel(t, providerId, id, reason.trim() || undefined), { success: "Subscription cancelled." });
                    if (done) {
                      sub.setData(done);
                      setCancelling(false);
                    }
                  }}
                >
                  <p>{s.customerName} will stop being served from today. This can&apos;t be undone.</p>
                  <Field label="Reason (optional)">{(fid) => <input id={fid} className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
                </ConfirmDialog>
              ) : null}
            </>
          );
        }}
      </Boundary>
    </main>
  );
}
