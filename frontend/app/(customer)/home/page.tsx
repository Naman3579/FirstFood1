"use client";

import { VerificationBadge } from "@/components/domain/SubscriptionBits";
import Link from "next/link";
import { ArrowRight, CalendarOff, Check, ChevronRight, History, Soup, Sun } from "lucide-react";
import { Banner, Boundary, Empty, ListSkeleton, Spinner } from "@/components/ui";
import { PhaseBadge } from "@/components/domain/status";
import { Progress } from "@/components/domain/SubscriptionBits";
import { useAccount } from "@/components/shell/account";
import { useAction, useQuery } from "@/lib/hooks";
import { attendanceApi, LOCKED_REASON_TEXT } from "@/lib/attendance-api";
import { subscriptionApi, type MySubscription } from "@/lib/subscription-api";
import { daysBetween, formatDate, formatMoney, todayInIndia } from "@/lib/format";
import { useSession } from "@/lib/session";

function totals(s: MySubscription): { used: number; total: number; unit: string; left: number | null } {
  if (s.consumptionType === "MEAL") {
    const total = s.terms.purchasedMeals ?? 0;
    const left = s.remainingMeals ?? 0;
    return { total, used: total - left, unit: "meals", left };
  }
  const total = s.effectiveExpiryDate ? daysBetween(s.startDate, s.effectiveExpiryDate) + 1 : (s.terms.purchasedDays ?? 0);
  const left = s.remainingDays ?? 0;
  return { total, used: Math.max(0, total - left), unit: "days", left };
}

function TodayCard({ sub }: { sub: MySubscription }) {
  const today = todayInIndia();
  const day = useQuery((t) => attendanceApi.myDays(t, sub.id, today, today).then((d) => d[0] ?? null), [sub.id, today]);
  const { run, busy } = useAction();
  const d = day.data;
  if (day.data === undefined && !day.error) return <div className="skeleton" style={{ height: "4.5rem" }} />;
  if (!d) return null;
  const absent = d.status === "ABSENT";

  async function toggle() {
    if (!d) return;
    const ok = absent && d.absenceId
      ? await run((t) => attendanceApi.cancelAbsence(t, sub.id, d.absenceId as string), { success: "Welcome back - meal on for today." })
      : await run((t) => attendanceApi.declare(t, sub.id, today), { success: "Marked as not eating today." });
    if (ok) await day.reload();
  }

  return (
    <div className="card row between" style={{ gap: "1rem" }}>
      <div className="row row-nowrap">
        <span className="avatar" style={absent ? { background: "var(--danger-soft)", color: "var(--danger)" } : undefined}>
          {absent ? <CalendarOff size={18} /> : <Sun size={18} />}
        </span>
        <div>
          <div className="strong">{absent ? "Not eating today" : "Eating today"}</div>
          <div className="small muted">
            {d.changeable ? (absent ? "You can still change your mind." : "Away today? Let the kitchen know.") : d.lockedReason ? LOCKED_REASON_TEXT[d.lockedReason] : ""}
          </div>
        </div>
      </div>
      {d.changeable ? (
        <button className={`btn btn-sm ${absent ? "btn-primary" : ""}`} onClick={() => void toggle()} disabled={busy}>
          {busy ? <Spinner /> : absent ? <Check size={16} /> : <CalendarOff size={16} />}
          {absent ? "Eat today" : "Skip today"}
        </button>
      ) : null}
    </div>
  );
}

function HeroSubscription({ sub }: { sub: MySubscription }) {
  const { used, total, unit, left } = totals(sub);
  return (
    <Link href={`/subscriptions/${sub.id}`} className="card hero card-link" style={{ color: "#fff" }}>
      <div className="row between" style={{ alignItems: "flex-start" }}>
        <div>
          <div className="small muted">{sub.providerName}</div>
          <h2 style={{ fontSize: "1.4rem", marginTop: "0.15rem" }}>{sub.terms.planName}</h2>
        </div>
        <span className="row" style={{ gap: "0.4rem" }}><VerificationBadge verification={sub.verification} /><PhaseBadge phase={sub.phase} /></span>
      </div>
      <div style={{ margin: "1.25rem 0 0.6rem" }} className="row between">
        <div>
          <span className="num" style={{ fontSize: "2.5rem", fontWeight: 800, fontFamily: "var(--font-display)", lineHeight: 1 }}>
            {sub.phase === "UPCOMING" ? total : left}
          </span>{" "}
          <span className="muted">{sub.phase === "UPCOMING" ? `${unit} starting ${formatDate(sub.startDate)}` : `${unit} left`}</span>
        </div>
        <ArrowRight size={22} aria-hidden />
      </div>
      <Progress value={used} max={total} label={`${used} of ${total} ${unit} used`} />
      <div className="row between small muted" style={{ marginTop: "0.6rem" }}>
        <span>{formatDate(sub.startDate)}</span>
        <span>{sub.effectiveExpiryDate ? `Ends ${formatDate(sub.effectiveExpiryDate)}` : "No end date"}</span>
      </div>
    </Link>
  );
}

function SubscriptionRow({ sub }: { sub: MySubscription }) {
  return (
    <Link href={`/subscriptions/${sub.id}`} className="list-item">
      <span className="avatar">
        <Soup size={18} />
      </span>
      <span className="grow">
        <span className="strong truncate" style={{ display: "block" }}>
          {sub.terms.planName}
        </span>
        <span className="small muted truncate" style={{ display: "block" }}>
          {sub.providerName} · {formatDate(sub.startDate)} – {formatDate(sub.effectiveExpiryDate)}
        </span>
      </span>
      <span className="row" style={{ gap: "0.4rem" }}><VerificationBadge verification={sub.verification} /><PhaseBadge phase={sub.phase} /></span>
      <ChevronRight size={18} className="faint" />
    </Link>
  );
}

export default function HomePage() {
  const { person } = useAccount();
  const { profile } = useSession();
  const subs = useQuery((t) => subscriptionApi.mine(t), []);
  const first = person.data?.fullName?.split(" ")[0];

  return (
    <main className="page" style={{ maxWidth: "48rem" }}>
      <div>
        <h1>Namaste{first ? `, ${first}` : ""} 🙏</h1>
        <p className="muted" style={{ marginTop: "0.25rem" }}>
          Here&apos;s what&apos;s on your plate.
        </p>
      </div>

      {person.data === null ? (
        <Banner
          tone="warn"
          action={
            <Link href="/account" className="btn btn-sm">
              Add name
            </Link>
          }
        >
          Add your name so providers know who you are{profile ? ` (${profile.phone ?? profile.email ?? ""})` : ""}.
        </Banner>
      ) : null}

      <Boundary query={subs} skeleton={<ListSkeleton rows={2} />}>
        {(all) => {
          const live = all.filter((s) => s.phase === "RUNNING" || s.phase === "UPCOMING");
          const past = all.filter((s) => s.phase === "ENDED" || s.phase === "CANCELLED");
          const primary = live.find((s) => s.phase === "RUNNING") ?? live[0];
          const others = live.filter((s) => s !== primary);
          if (all.length === 0) {
            return (
              <div className="card">
                <Empty icon={<Soup size={26} />} title="No subscriptions yet">
                  Ask your mess or tiffin provider to add you with this phone number. Your plan will appear here the moment they do.
                </Empty>
              </div>
            );
          }
          return (
            <>
              {primary ? (
                <>
                  <HeroSubscription sub={primary} />
                  {primary.phase === "RUNNING" ? <TodayCard sub={primary} /> : null}
                </>
              ) : (
                <Banner>You have no active plan right now. Ask your provider to renew or start a new one.</Banner>
              )}
              {others.length > 0 ? (
                <section className="stack stack-s">
                  <h2>Other plans</h2>
                  <div className="card card-flush">
                    <div className="list">
                      {others.map((s) => (
                        <SubscriptionRow key={s.id} sub={s} />
                      ))}
                    </div>
                  </div>
                </section>
              ) : null}
              {past.length > 0 ? (
                <section className="stack stack-s">
                  <h2 className="row" style={{ gap: "0.4rem" }}>
                    <History size={18} /> History
                  </h2>
                  <div className="card card-flush">
                    <div className="list">
                      {past.map((s) => (
                        <SubscriptionRow key={s.id} sub={s} />
                      ))}
                    </div>
                  </div>
                  <p className="small faint">
                    Total value of past plans: {formatMoney(past.reduce((n, s) => n + s.terms.price, 0))}
                  </p>
                </section>
              ) : null}
            </>
          );
        }}
      </Boundary>
    </main>
  );
}
