"use client";

import { VerificationBadge } from "@/components/domain/SubscriptionBits";
import { useParams } from "next/navigation";
import { useMemo, useState } from "react";
import { CalendarOff, CalendarPlus, Check, Lock, PlaneTakeoff, Undo2 } from "lucide-react";
import { BackLink, Badge, Banner, Boundary, ConfirmDialog, Field, ListSkeleton, Modal, Spinner } from "@/components/ui";
import { useToast } from "@/components/ui/toast";
import { PhaseBadge } from "@/components/domain/status";
import { Progress, TermsList } from "@/components/domain/SubscriptionBits";
import { CustomerPaymentsCard } from "@/components/domain/PaymentBits";
import { RenewCard } from "@/components/domain/RenewBits";
import { MonthCalendar, monthBounds, monthOf, type CalCell } from "@/components/domain/MonthCalendar";
import { useAction, useQuery } from "@/lib/hooks";
import { attendanceApi, LOCKED_REASON_TEXT, type MyAbsence } from "@/lib/attendance-api";
import { BLOCK_TEXT, extensionApi } from "@/lib/extension-api";
import { subscriptionApi, type MySubscription } from "@/lib/subscription-api";
import { vacationApi } from "@/lib/vacation-api";
import { addDays, daysBetween, formatDate, formatDayShort, formatDateTime, formatMoney, todayInIndia } from "@/lib/format";

function AbsenceRange({ sub, onClose, onDone }: { sub: MySubscription; onClose: () => void; onDone: () => void }) {
  const today = todayInIndia();
  const [from, setFrom] = useState(addDays(today, 1));
  const [to, setTo] = useState(addDays(today, 3));
  const [reason, setReason] = useState("");
  const { run, busy } = useAction();
  const toast = useToast();
  const invalid = !from || !to || to < from;

  async function submit() {
    const res = await run((t) => attendanceApi.declare(t, sub.id, from, to, reason.trim() || undefined));
    if (res) {
      if (res.anyCreated) toast.success(`Marked ${res.absences.length} day(s) as not eating.`);
      else toast.info("Those days were already marked as not eating.");
      onDone();
      onClose();
    }
  }

  return (
    <Modal title="Away for a few days" onClose={onClose}>
      <p className="muted small">Choose the first and last day you won&apos;t eat. Days the provider has already locked are skipped.</p>
      <div className="form-grid">
        <Field label="From">{(id) => <input id={id} type="date" className="input" value={from} min={sub.startDate} max={sub.effectiveExpiryDate ?? undefined} onChange={(e) => setFrom(e.target.value)} />}</Field>
        <Field label="To" error={to && to < from ? "Must be on or after the start" : null}>
          {(id) => <input id={id} type="date" className="input" value={to} min={from} max={sub.effectiveExpiryDate ?? undefined} onChange={(e) => setTo(e.target.value)} />}
        </Field>
      </div>
      <Field label="Reason (optional)">{(id) => <input id={id} className="input" maxLength={500} placeholder="Travelling home" value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      {!invalid ? <p className="small muted">{daysBetween(from, to) + 1} day(s) will be marked as not eating.</p> : null}
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || invalid} onClick={() => void submit()}>
          {busy ? <Spinner /> : <CalendarOff size={16} />} Mark as away
        </button>
      </div>
    </Modal>
  );
}

/**
 * Vacation with no return date: it stays open until the customer resumes, then the days that were not served are added
 * back to the end date. Every number shown here (days given back, the new end date) comes from the server.
 */
function VacationCard({ sub, onChanged }: { sub: MySubscription; onChanged: () => void }) {
  const status = useQuery((t) => vacationApi.status(t, sub.id), [sub.id, sub.pausedSince, sub.effectiveExpiryDate]);
  const { run, busy } = useAction();
  const toast = useToast();
  const today = todayInIndia();
  const [starting, setStarting] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [when, setWhen] = useState<"today" | "later">("today");
  const [startDate, setStartDate] = useState(addDays(today, 1));

  async function refresh() {
    await status.reload();
    onChanged();
  }
  async function start() {
    const res = await run((t) => vacationApi.start(t, sub.id, when === "later" ? startDate : undefined));
    if (res) {
      toast.success(res.started ? "Your vacation has started." : `Your vacation is scheduled for ${formatDate(res.startDate)}.`);
      setStarting(false);
      await refresh();
    }
  }
  async function resume() {
    const res = await run((t) => vacationApi.resume(t, sub.id));
    if (res) {
      if (res.status === "CANCELLED") toast.info("The scheduled vacation was called off.");
      else toast.success(res.creditedDays ? `Welcome back! ${res.creditedDays} day(s) were added to your plan.` : "Welcome back!");
      setResuming(false);
      await refresh();
    }
  }

  const v = status.data;
  const open = v?.open ?? null;
  return (
    <section className="card stack">
      <div className="card-head">
        <h2>Vacation</h2>
        {open ? <Badge tone={open.started ? "warn" : "info"}>{open.started ? "On vacation" : "Scheduled"}</Badge> : null}
      </div>
      {status.error ? <Banner tone="danger">{status.error}</Banner> : null}
      {!v ? <Spinner /> : open ? (
        <>
          <p className="small">
            {open.started
              ? `You have been on vacation since ${formatDate(open.startDate)}. Meals are paused and you do not need to tell us when you will be back.`
              : `Your vacation is scheduled to begin on ${formatDate(open.startDate)}.`}
          </p>
          {open.started ? (
            <p className="small muted">
              If you resume today, {open.projectedCreditDays ?? 0} day(s) are added to your plan
              {open.projectedExpiryDate ? <>, which would then end on <strong>{formatDate(open.projectedExpiryDate)}</strong></> : null}.
            </p>
          ) : null}
          <div>
            <button className="btn btn-primary" disabled={busy} onClick={() => setResuming(true)}>
              {busy ? <Spinner /> : <Undo2 size={16} />} {open.started ? "I'm back - resume" : "Call off this vacation"}
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="small muted">
            Going away and not sure when you will be back? Pause the plan. Days you are away are added to the end of your plan when you resume.
          </p>
          {v.canStart ? (
            <div><button className="btn" onClick={() => setStarting(true)}><PlaneTakeoff size={16} /> Start a vacation</button></div>
          ) : v.blockedReason ? <p className="small faint">{v.blockedReason}</p> : null}
        </>
      )}
      {v && v.history.length > 0 ? (
        <>
          <hr className="divider" />
          <ul className="stack small" style={{ listStyle: "none", padding: 0, margin: 0 }}>
            {v.history.map((p) => (
              <li key={p.id} className="row between">
                <span>
                  {formatDate(p.startDate)}{p.resumedOn ? ` - back ${formatDate(p.resumedOn)}` : ""}
                  {p.status === "CANCELLED" ? " (called off)" : ""}
                </span>
                {p.status === "RESUMED" ? <span className="muted">{p.creditedDays ?? 0} day(s) added</span> : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {starting ? (
        <ConfirmDialog title="Start a vacation" confirmLabel="Start vacation" busy={busy} onConfirm={() => void start()} onClose={() => setStarting(false)}>
          <p className="small muted">You will not be served from the start date until you resume. You do not need to choose a return date.</p>
          <div className="row">
            <label className="row" style={{ gap: "0.4rem" }}><input type="radio" checked={when === "today"} onChange={() => setWhen("today")} /> Starting today</label>
            <label className="row" style={{ gap: "0.4rem" }}><input type="radio" checked={when === "later"} onChange={() => setWhen("later")} /> On a later date</label>
          </div>
          {when === "later" ? (
            <Field label="First day away">{(id) => <input id={id} type="date" className="input" value={startDate} min={addDays(today, 1)} max={addDays(today, 60)} onChange={(e) => setStartDate(e.target.value)} />}</Field>
          ) : null}
        </ConfirmDialog>
      ) : null}
      {resuming && open ? (
        <ConfirmDialog title={open.started ? "Resume your plan?" : "Call off this vacation?"} confirmLabel={open.started ? "Resume today" : "Call off"} busy={busy} onConfirm={() => void resume()} onClose={() => setResuming(false)}>
          <p className="small">
            {open.started
              ? `Meals start again today. ${open.projectedCreditDays ?? 0} day(s) will be added to the end of your plan.`
              : "Nothing was withheld, so your plan does not change."}
          </p>
        </ConfirmDialog>
      ) : null}
    </section>
  );
}

function Attendance({ sub }: { sub: MySubscription }) {
  const today = todayInIndia();
  const lastDay = sub.effectiveExpiryDate;
  const startMonth = monthOf(sub.startDate);
  const endMonth = lastDay ? monthOf(lastDay) : undefined;
  const initial = today < sub.startDate ? startMonth : lastDay && today > lastDay ? monthOf(lastDay) : monthOf(today);
  const [month, setMonth] = useState(initial);
  const [selected, setSelected] = useState<string | null>(null);
  const [range, setRange] = useState(false);
  const { run, busy } = useAction();

  const { first, last } = monthBounds(month);
  const from = first < sub.startDate ? sub.startDate : first;
  const to = lastDay && last > lastDay ? lastDay : last;
  const days = useQuery((t) => attendanceApi.myDays(t, sub.id, from, to), [sub.id, from, to], { enabled: from <= to });
  const absences = useQuery((t) => attendanceApi.myAbsences(t, sub.id), [sub.id]);

  const cells = useMemo(() => {
    const m = new Map<string, CalCell>();
    days.data?.forEach((d) => m.set(d.date, { status: d.status, locked: !d.changeable }));
    return m;
  }, [days.data]);
  const sel = selected ? days.data?.find((d) => d.date === selected) : undefined;

  async function refresh() {
    await Promise.all([days.reload(), absences.reload()]);
  }

  async function skip(date: string) {
    if (await run((t) => attendanceApi.declare(t, sub.id, date), { success: `Marked ${formatDayShort(date)} as not eating.` })) await refresh();
  }
  async function undo(absenceId: string, success = "Absence cancelled - meal is back on.") {
    if (await run((t) => attendanceApi.cancelAbsence(t, sub.id, absenceId), { success })) await refresh();
  }

  const upcoming = (absences.data ?? []).filter((a: MyAbsence) => a.status === "DECLARED" && a.date >= today);

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>Attendance</h2>
          {sub.phase === "RUNNING" || sub.phase === "UPCOMING" ? (
            <button className="btn btn-sm" onClick={() => setRange(true)}>
              <CalendarPlus size={16} /> Away for days
            </button>
          ) : null}
        </div>
        <p className="small muted" style={{ marginBottom: "0.9rem" }}>
          You&apos;re counted as eating every day unless you tell us otherwise.
        </p>
        {days.error ? <Banner tone="danger">{days.error}</Banner> : null}
        <MonthCalendar month={month} onMonth={(m) => { setMonth(m); setSelected(null); }} cells={cells} selected={selected} onSelect={setSelected} rangeStart={sub.startDate} rangeEnd={lastDay} minMonth={startMonth} maxMonth={endMonth} />

        {selected ? (
          <div className="card" style={{ marginTop: "1rem", background: "var(--surface-2)", boxShadow: "none" }}>
            <div className="row between">
              <div>
                <div className="strong">{formatDayShort(selected)}</div>
                {sel ? (
                  <div className="row" style={{ marginTop: "0.25rem" }}>
                    <Badge tone={sel.status === "ABSENT" ? "danger" : "ok"}>{sel.status === "ABSENT" ? "Not eating" : "Eating"}</Badge>
                    {sel.providerSet ? <Badge tone="info">Set by provider</Badge> : null}
                  </div>
                ) : days.loading ? <Spinner /> : <div className="small muted">No details for this day.</div>}
                {sel?.reason ? <p className="small muted" style={{ marginTop: "0.4rem" }}>“{sel.reason}”</p> : null}
              </div>
              {sel?.changeable ? (
                sel.status === "ABSENT" && sel.absenceId ? (
                  <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void undo(sel.absenceId as string)}>
                    {busy ? <Spinner /> : <Undo2 size={16} />} Eat this day
                  </button>
                ) : (
                  <button className="btn btn-sm" disabled={busy} onClick={() => void skip(selected)}>
                    {busy ? <Spinner /> : <CalendarOff size={16} />} Not eating
                  </button>
                )
              ) : null}
            </div>
            {sel && !sel.changeable && sel.lockedReason ? (
              <p className="small muted row" style={{ marginTop: "0.6rem", gap: "0.4rem" }}>
                <Lock size={14} /> {LOCKED_REASON_TEXT[sel.lockedReason]}
              </p>
            ) : null}
          </div>
        ) : (
          <p className="small faint" style={{ marginTop: "1rem" }}>Tap a day to see or change it.</p>
        )}
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Upcoming absences</h2>
        </div>
        {absences.data === undefined ? (
          <ListSkeleton rows={2} />
        ) : upcoming.length === 0 ? (
          <p className="muted small">Nothing planned - you&apos;ll eat every day.</p>
        ) : (
          <div className="list" style={{ margin: "0 -1.1rem" }}>
            {upcoming.map((a) => (
              <div key={a.id} className="list-item">
                <div className="grow">
                  <div className="strong">{formatDayShort(a.date)}</div>
                  <div className="small muted truncate">{a.reason || "No reason given"}</div>
                </div>
                <button className="btn btn-sm" disabled={busy} onClick={() => void undo(a.id)}>
                  <Undo2 size={14} /> Cancel
                </button>
              </div>
            ))}
          </div>
        )}
        {(absences.data ?? []).some((a) => a.status === "OVERRIDDEN") ? (
          <Banner tone="info">Some absences were changed by your provider. Tap those days in the calendar for details.</Banner>
        ) : null}
      </section>

      {range ? <AbsenceRange sub={sub} onClose={() => setRange(false)} onDone={() => void refresh()} /> : null}
    </div>
  );
}

function ExtensionCard({ sub }: { sub: MySubscription }) {
  const status = useQuery((t) => extensionApi.myStatus(t, sub.id), [sub.id]);
  const events = useQuery((t) => extensionApi.myEvents(t, sub.id), [sub.id]);
  const s = status.data;
  if (!s) return null;
  if (s.blockedReason && s.blockedReason !== "NOT_ALLOWED_BY_POLICY" && s.eventCount === 0) return null;
  return (
    <section className="card stack">
      <h2>Extension for absences</h2>
      {s.blockedReason ? (
        <p className="muted small">{BLOCK_TEXT[s.blockedReason]}</p>
      ) : (
        <>
          <p className="small muted">
            Stay away {s.minConsecutiveAbsenceDays}+ days in a row and the days you miss are added to the end of your plan.
          </p>
          <dl className="kv">
            <dt>Days counted</dt>
            <dd className="num">{s.countedAbsentDays}</dd>
            <dt>Extension earned</dt>
            <dd className="num">{s.appliedExtensionDays + s.pendingExtensionDays} day(s)</dd>
            <dt>Applied so far</dt>
            <dd className="num">{s.appliedExtensionDays} day(s)</dd>
            {s.pendingExtensionDays > 0 ? (
              <>
                <dt>Waiting to apply</dt>
                <dd className="num">{s.pendingExtensionDays} day(s) → ends {formatDate(s.projectedExpiryDate)}</dd>
              </>
            ) : null}
            {s.capped ? (
              <>
                <dt>Limit</dt>
                <dd>Capped at {formatDate(s.maximumExpiryDate)}</dd>
              </>
            ) : null}
          </dl>
        </>
      )}
      {events.data && events.data.length > 0 ? (
        <>
          <hr className="divider" />
          <ul className="timeline">
            {events.data.map((e) => (
              <li key={e.id}>
                <div className="strong">
                  +{e.appliedExtensionDays} day{e.appliedExtensionDays === 1 ? "" : "s"} · {formatDate(e.previousExpiryDate)} → {formatDate(e.newExpiryDate)}
                </div>
                <div className="small muted">
                  {formatDateTime(e.createdAt)} · {e.triggerSource === "SYSTEM" ? "Automatic" : "By provider"}
                  {e.capped ? " · hit the limit" : ""}
                </div>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}

export default function SubscriptionPage() {
  const { id } = useParams<{ id: string }>();
  const sub = useQuery((t) => subscriptionApi.myGet(t, id), [id]);

  return (
    <main className="page" style={{ maxWidth: "48rem" }}>
      <BackLink href="/home">Home</BackLink>
      <Boundary query={sub} rows={3}>
        {(s) => {
          const total = s.consumptionType === "MEAL" ? (s.terms.purchasedMeals ?? 0) : s.effectiveExpiryDate ? daysBetween(s.startDate, s.effectiveExpiryDate) + 1 : (s.terms.purchasedDays ?? 0);
          const left = s.consumptionType === "MEAL" ? (s.remainingMeals ?? 0) : (s.remainingDays ?? 0);
          const extended = s.terms.purchasedDays && s.effectiveExpiryDate ? daysBetween(s.startDate, s.effectiveExpiryDate) + 1 - s.terms.purchasedDays : 0;
          return (
            <>
              <div className="card hero stack" style={{ color: "#fff" }}>
                <div className="row between" style={{ alignItems: "flex-start" }}>
                  <div>
                    <div className="small muted">{s.providerName}</div>
                    <h1 style={{ marginTop: "0.15rem" }}>{s.terms.planName}</h1>
                  </div>
                  <span className="row" style={{ gap: "0.4rem" }}><VerificationBadge verification={s.verification} /><PhaseBadge phase={s.phase} /></span>
                </div>
                {s.verification?.status === "PENDING" ? (
                  <Banner tone="info">
                    Waiting for {s.providerName} to confirm this subscription.
                    {s.verification.expiresAt ? ` If it is not confirmed by ${new Date(s.verification.expiresAt).toLocaleString()}, the request is cancelled automatically.` : ""}
                  </Banner>
                ) : null}
                {s.verification?.status === "EXPIRED" ? <Banner tone="warn">{s.providerName} did not confirm this subscription in time, so the request was cancelled. You can ask again.</Banner> : null}
                {s.verification?.status === "REJECTED" ? <Banner tone="danger">{s.providerName} did not accept this subscription{s.verification.rejectionReason ? `: ${s.verification.rejectionReason}` : "."}</Banner> : null}
                {s.onVacation ? <Banner tone="info">You are on vacation since {formatDate(s.pausedSince)}. The days left below are frozen until you resume.</Banner> : null}
                {s.phase === "RUNNING" || s.phase === "UPCOMING" ? (
                  <>
                    <div>
                      <span className="num" style={{ fontSize: "2.5rem", fontWeight: 800, fontFamily: "var(--font-display)", lineHeight: 1 }}>{s.phase === "UPCOMING" ? total : left}</span>{" "}
                      <span className="muted">{s.consumptionType === "MEAL" ? "meals" : "days"} {s.phase === "UPCOMING" ? "in this plan" : "left"}</span>
                    </div>
                    <Progress value={Math.max(0, total - left)} max={total} label="Plan progress" />
                  </>
                ) : (
                  <p className="muted">{s.phase === "CANCELLED" ? `Cancelled ${formatDate(s.cancelledAt?.slice(0, 10))}` : "This plan has ended."}</p>
                )}
              </div>

              <RenewCard sub={s} />

              <section className="card stack">
                <h2>Plan details</h2>
                <dl className="kv">
                  <dt>Type</dt>
                  <dd>{s.consumptionType === "MEAL" ? `${s.terms.purchasedMeals} meals` : `${s.terms.purchasedDays} days`}</dd>
                  <dt>Starts</dt>
                  <dd>{formatDate(s.startDate)}</dd>
                  <dt>Ends</dt>
                  <dd>
                    {formatDate(s.effectiveExpiryDate)}
                    {extended > 0 ? <span className="muted small"> (+{extended} extended)</span> : null}
                  </dd>
                  <dt>Price</dt>
                  <dd className="num">{formatMoney(s.terms.price, s.terms.currency)}</dd>
                </dl>
                <hr className="divider" />
                <TermsList terms={s.terms} />
              </section>

              <CustomerPaymentsCard subscriptionId={s.id} />

              {s.consumptionType === "DAY" && (s.onVacation || s.pausedSince || s.canStartVacation) ? <VacationCard sub={s} onChanged={() => void sub.reload()} /> : null}
              <Attendance sub={s} />
              {s.consumptionType === "DAY" ? <ExtensionCard sub={s} /> : null}
              <p className="small faint" style={{ textAlign: "center" }}>
                <Check size={12} style={{ verticalAlign: "-2px" }} /> Terms shown are the ones you signed up with.
              </p>
            </>
          );
        }}
      </Boundary>
    </main>
  );
}
