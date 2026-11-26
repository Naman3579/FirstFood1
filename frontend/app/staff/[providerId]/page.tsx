"use client";

import Link from "next/link";
import { CalendarCheck2, ChevronRight, ClipboardList, Plus, RefreshCw, Timer, UserCheck, UserPlus, UserX, Users } from "lucide-react";
import { Avatar, Badge, Banner, PageHeader, Skeleton, StatTile } from "@/components/ui";
import { providerTypesText, ProviderStatusBadge } from "@/components/domain/status";
import { useProvider } from "@/components/shell/ProviderShell";
import { useQuery } from "@/lib/hooks";
import { attendanceApi } from "@/lib/attendance-api";
import { membershipApi } from "@/lib/membership-api";
import { subscriptionApi } from "@/lib/subscription-api";
import { formatDayShort, formatDate, todayInIndia } from "@/lib/format";

export default function Dashboard() {
  const { provider, providerId, can } = useProvider();
  const today = todayInIndia();
  const base = `/staff/${providerId}`;
  const sheet = useQuery((t) => attendanceApi.dailySheet(t, providerId, today), [providerId, today], { enabled: can("ATTENDANCE_VIEW") });
  const subs = useQuery((t) => subscriptionApi.list(t, providerId), [providerId], { enabled: can("SUBSCRIPTION_VIEW") });
  const customers = useQuery((t) => membershipApi.list(t, providerId, false), [providerId], { enabled: can("MEMBERSHIP_VIEW") });

  const live = (subs.data ?? []).filter((s) => s.phase === "RUNNING" || s.phase === "UPCOMING");
  const soon = live
    .filter((s) => s.phase === "RUNNING" && s.remainingDays !== null && s.remainingDays <= 3)
    .sort((a, b) => (a.remainingDays ?? 0) - (b.remainingDays ?? 0));
  const renewals = (subs.data ?? []).filter((s) => s.renewable);
  const cap = provider.maxActiveSubscriptions;
  const running = (subs.data ?? []).filter((s) => s.phase === "RUNNING").length;

  return (
    <main className="page">
      <PageHeader title={<span className="row" style={{ gap: "0.6rem" }}>{provider.name} <ProviderStatusBadge status={provider.status} /></span>} sub={`${providerTypesText(provider.providerTypes)} · ${provider.locality}, ${provider.city}`} />

      {!provider.acceptingNewCustomers && provider.status !== "CLOSED" ? <Banner tone="warn">You&apos;re not accepting new customers right now. <Link href={`${base}/settings`}>Change in settings</Link></Banner> : null}

      <div className="row">
        {can("MEMBERSHIP_MANAGE") && provider.status !== "CLOSED" ? <Link href={`${base}/customers`} className="btn"><UserPlus size={16} /> Add customer</Link> : null}
        {can("SUBSCRIPTION_MANAGE") && provider.status !== "CLOSED" ? <Link href={`${base}/subscriptions`} className="btn"><Plus size={16} /> New subscription</Link> : null}
        {can("ATTENDANCE_VIEW") ? <Link href={`${base}/attendance`} className="btn btn-primary"><CalendarCheck2 size={16} /> Today&apos;s attendance</Link> : null}
      </div>

      <div className="grid grid-stats">
        {can("ATTENDANCE_VIEW") ? (
          <>
            {sheet.data ? <StatTile label="Eating today" value={sheet.data.present} icon={<UserCheck size={15} />} sub={`of ${sheet.data.expected} expected`} /> : <Skeleton height="5.5rem" />}
            {sheet.data ? <StatTile label="Not eating" value={sheet.data.absent} icon={<UserX size={15} />} sub={formatDayShort(today)} /> : <Skeleton height="5.5rem" />}
          </>
        ) : null}
        {can("SUBSCRIPTION_VIEW") ? (subs.data ? <StatTile label="Running plans" value={running} icon={<ClipboardList size={15} />} sub={cap ? `capacity ${cap}` : "no capacity limit"} /> : <Skeleton height="5.5rem" />) : null}
        {can("MEMBERSHIP_VIEW") ? (customers.data ? <StatTile label="Customers" value={customers.data.length} icon={<Users size={15} />} sub="Active members" /> : <Skeleton height="5.5rem" />) : null}
      </div>

      {can("SUBSCRIPTION_VIEW") ? (
        <div className="grid grid-2">
          <section className="card stack">
            <div className="section-title"><h2 className="row" style={{ gap: "0.4rem" }}><Timer size={18} /> Ending soon</h2><Link className="small" href={`${base}/subscriptions`}>All</Link></div>
            {subs.data === undefined ? <Skeleton height="4rem" /> : soon.length === 0 ? <p className="small muted">Nobody&apos;s plan ends in the next 3 days.</p> : (
              <div className="list" style={{ margin: "0 -1.1rem" }}>
                {soon.slice(0, 6).map((s) => (
                  <Link key={s.id} href={`${base}/subscriptions/${s.id}`} className="list-item">
                    <Avatar name={s.customerName} />
                    <span className="grow"><span className="strong truncate" style={{ display: "block" }}>{s.customerName}</span><span className="small muted">Ends {formatDate(s.effectiveExpiryDate)}</span></span>
                    <Badge tone="warn">{s.remainingDays === 0 ? "Last day" : `${s.remainingDays}d left`}</Badge>
                    <ChevronRight size={16} className="faint" />
                  </Link>
                ))}
              </div>
            )}
          </section>
          <section className="card stack">
            <div className="section-title"><h2 className="row" style={{ gap: "0.4rem" }}><RefreshCw size={18} /> Ready to renew</h2></div>
            {subs.data === undefined ? <Skeleton height="4rem" /> : renewals.length === 0 ? <p className="small muted">No ended plans waiting on a renewal.</p> : (
              <div className="list" style={{ margin: "0 -1.1rem" }}>
                {renewals.slice(0, 6).map((s) => (
                  <Link key={s.id} href={`${base}/subscriptions/${s.id}`} className="list-item">
                    <Avatar name={s.customerName} />
                    <span className="grow"><span className="strong truncate" style={{ display: "block" }}>{s.customerName}</span><span className="small muted">{s.terms.planName} · ended {formatDate(s.effectiveExpiryDate)}</span></span>
                    <Badge tone="accent">Renew</Badge>
                  </Link>
                ))}
              </div>
            )}
          </section>
        </div>
      ) : null}
    </main>
  );
}
