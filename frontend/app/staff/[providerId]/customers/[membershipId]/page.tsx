"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { MapPin, ChevronRight, Phone, Plus, UserMinus } from "lucide-react";
import { Avatar, BackLink, Boundary, ConfirmDialog, Empty } from "@/components/ui";
import { MembershipBadge, PhaseBadge } from "@/components/domain/status";
import { CreateSubscriptionModal } from "@/components/domain/CreateSubscriptionModal";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { membershipApi } from "@/lib/membership-api";
import { subscriptionApi } from "@/lib/subscription-api";
import { formatDate, formatMoney, formatDateTime } from "@/lib/format";
import { describeRemaining } from "@/lib/subscription-api";
import { useRouter } from "next/navigation";

export default function CustomerPage() {
  const { membershipId } = useParams<{ membershipId: string }>();
  const { providerId, can, provider } = useProvider();
  const router = useRouter();
  const customer = useQuery((t) => membershipApi.get(t, providerId, membershipId), [providerId, membershipId]);
  const subs = useQuery((t) => subscriptionApi.list(t, providerId, { membershipId }), [providerId, membershipId], { enabled: can("SUBSCRIPTION_VIEW") });
  const [deactivating, setDeactivating] = useState(false);
  const [creating, setCreating] = useState(false);
  const { run, busy } = useAction();
  const open = provider.status !== "CLOSED";

  return (
    <main className="page" style={{ maxWidth: "52rem" }}>
      <BackLink href={`/staff/${providerId}/customers`}>Customers</BackLink>
      <Boundary query={customer} rows={2}>
        {(c) => (
          <>
            <div className="card row between" style={{ gap: "1rem" }}>
              <div className="row row-nowrap" style={{ minWidth: 0 }}>
                <Avatar name={c.fullName} />
                <div style={{ minWidth: 0 }}>
                  <h1 style={{ fontSize: "1.4rem" }} className="truncate">{c.fullName}</h1>
                  <div className="small muted row" style={{ gap: "0.3rem" }}><Phone size={13} /> <span className="num">{c.phone ?? "—"}</span></div>
                  {c.address ? <div className="small muted row" style={{ gap: "0.3rem", alignItems: "flex-start" }}><MapPin size={13} /> <span>{[c.address.addressLine, c.address.locality, c.address.city, c.address.pincode].filter(Boolean).join(", ")}{c.address.landmark ? ` (near ${c.address.landmark})` : ""}</span></div> : null}
                </div>
              </div>
              <div className="row">
                <MembershipBadge status={c.status} />
                {c.status === "ACTIVE" && can("MEMBERSHIP_MANAGE") && open ? (
                  <button className="btn btn-sm" onClick={() => setDeactivating(true)}><UserMinus size={14} /> Remove</button>
                ) : null}
              </div>
              <p className="small muted" style={{ flexBasis: "100%" }}>
                Joined {formatDateTime(c.joinedAt)}{c.leftAt ? ` · left ${formatDateTime(c.leftAt)}` : ""}
              </p>
            </div>

            {can("SUBSCRIPTION_VIEW") ? (
              <section className="stack stack-s">
                <div className="section-title">
                  <h2>Subscriptions</h2>
                  {c.status === "ACTIVE" && can("SUBSCRIPTION_MANAGE") && open ? (
                    <button className="btn btn-primary btn-sm" onClick={() => setCreating(true)}><Plus size={16} /> New</button>
                  ) : null}
                </div>
                <Boundary query={subs} rows={2}>
                  {(list) =>
                    list.length === 0 ? (
                      <div className="card"><Empty title="No subscriptions yet">Start one to begin tracking attendance.</Empty></div>
                    ) : (
                      <div className="card card-flush">
                        <div className="list">
                          {list.map((s) => (
                            <Link key={s.id} href={`/staff/${providerId}/subscriptions/${s.id}`} className="list-item">
                              <span className="grow">
                                <span className="strong truncate" style={{ display: "block" }}>{s.terms.planName}</span>
                                <span className="small muted truncate" style={{ display: "block" }}>
                                  {formatDate(s.startDate)} – {formatDate(s.effectiveExpiryDate)} · {describeRemaining(s)} · {formatMoney(s.terms.price, s.terms.currency)}
                                </span>
                              </span>
                              <PhaseBadge phase={s.phase} />
                              <ChevronRight size={18} className="faint" />
                            </Link>
                          ))}
                        </div>
                      </div>
                    )
                  }
                </Boundary>
              </section>
            ) : null}

            {deactivating ? (
              <ConfirmDialog
                title="Remove this customer?"
                confirmLabel="Remove customer"
                danger
                busy={busy}
                onClose={() => setDeactivating(false)}
                onConfirm={async () => {
                  const done = await run((t) => membershipApi.deactivate(t, providerId, membershipId), { success: "Customer removed." });
                  if (done) {
                    setDeactivating(false);
                    router.push(`/staff/${providerId}/customers`);
                  }
                }}
              >
                <p>{c.fullName} will be marked as a former customer. This isn&apos;t possible while they still have an active subscription - cancel it first.</p>
              </ConfirmDialog>
            ) : null}
            {creating ? (
              <CreateSubscriptionModal
                providerId={providerId}
                membershipId={membershipId}
                onClose={() => setCreating(false)}
                onCreated={(s) => router.push(`/staff/${providerId}/subscriptions/${s.id}`)}
              />
            ) : null}
          </>
        )}
      </Boundary>
    </main>
  );
}
