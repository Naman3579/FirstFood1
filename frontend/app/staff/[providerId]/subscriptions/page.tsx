"use client";

import { VerificationBadge } from "@/components/domain/SubscriptionBits";
import Link from "next/link";
import { useMemo, useState } from "react";
import { ChevronRight, ClipboardList, Plus, RefreshCw } from "lucide-react";
import { Badge, Boundary, Empty, PageHeader, SearchBox, Segmented } from "@/components/ui";
import { PhaseBadge } from "@/components/domain/status";
import { CreateSubscriptionModal } from "@/components/domain/CreateSubscriptionModal";
import { useProvider } from "@/components/shell/ProviderShell";
import { useQuery } from "@/lib/hooks";
import { describeRemaining, subscriptionApi, type SubscriptionStatus } from "@/lib/subscription-api";
import { formatDate, formatMoney } from "@/lib/format";
import { useRouter } from "next/navigation";

type Filter = "LIVE" | SubscriptionStatus | "ALL";

export default function SubscriptionsPage() {
  const { providerId, can, provider } = useProvider();
  const router = useRouter();
  const [filter, setFilter] = useState<Filter>("LIVE");
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);
  // Always fetch all and filter in the browser: the lists are small and tabs then switch instantly.
  const subs = useQuery((t) => subscriptionApi.list(t, providerId), [providerId]);
  const manage = can("SUBSCRIPTION_MANAGE") && provider.status !== "CLOSED";

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (subs.data ?? [])
      .filter((s) => (filter === "ALL" ? true : filter === "LIVE" ? s.phase === "RUNNING" || s.phase === "UPCOMING" : s.status === filter))
      .filter((s) => !needle || s.customerName.toLowerCase().includes(needle) || s.terms.planName.toLowerCase().includes(needle))
      .sort((a, b) => (a.effectiveExpiryDate ?? "9999").localeCompare(b.effectiveExpiryDate ?? "9999"));
  }, [subs.data, filter, q]);

  return (
    <main className="page">
      <PageHeader
        title="Subscriptions"
        sub="Everyone's plans, soonest expiry first."
        actions={manage ? <button className="btn btn-primary" onClick={() => setCreating(true)}><Plus size={18} /> New subscription</button> : undefined}
      />
      <div className="filter-bar">
        <Segmented
          label="Filter by status"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "LIVE", label: "Active" },
            { value: "EXPIRED", label: "Expired" },
            { value: "CANCELLED", label: "Cancelled" },
            { value: "ALL", label: "All" },
          ]}
        />
        <SearchBox placeholder="Search customer or plan" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search subscriptions" />
      </div>

      <Boundary query={subs} rows={5}>
        {() =>
          rows.length === 0 ? (
            <div className="card"><Empty icon={<ClipboardList size={26} />} title="Nothing here">{q ? "No subscriptions match your search." : "No subscriptions in this view."}</Empty></div>
          ) : (
            <div className="card card-flush">
              <div className="list">
                {rows.map((s) => (
                  <Link key={s.id} href={`/staff/${providerId}/subscriptions/${s.id}`} className="list-item">
                    <span className="grow">
                      <span className="strong truncate" style={{ display: "block" }}>{s.customerName}</span>
                      <span className="small muted truncate" style={{ display: "block" }}>
                        {s.terms.planName} · {formatDate(s.startDate)} – {formatDate(s.effectiveExpiryDate)}
                      </span>
                      <span className="small faint">{describeRemaining(s)} · {formatMoney(s.terms.price, s.terms.currency)}</span>
                    </span>
                    <span className="stack stack-s" style={{ alignItems: "flex-end", "--gap": "0.3rem" } as React.CSSProperties}>
                      <PhaseBadge phase={s.phase} />
                      <VerificationBadge verification={s.verification} />
                      {s.renewable ? <Badge tone="accent"><RefreshCw size={11} /> Renew</Badge> : null}
                    </span>
                    <ChevronRight size={18} className="faint" />
                  </Link>
                ))}
              </div>
            </div>
          )
        }
      </Boundary>

      {creating ? <CreateSubscriptionModal providerId={providerId} onClose={() => setCreating(false)} onCreated={(s) => router.push(`/staff/${providerId}/subscriptions/${s.id}`)} /> : null}
    </main>
  );
}
