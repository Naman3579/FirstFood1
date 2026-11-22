"use client";

import Link from "next/link";
import { useState } from "react";
import { Mail, Store } from "lucide-react";
import { Banner, Boundary, ConfirmDialog, Empty, Spinner } from "@/components/ui";
import { MembershipBadge } from "@/components/domain/status";
import { useAction, useQuery } from "@/lib/hooks";
import { membershipApi, type MyMembership } from "@/lib/membership-api";
import { subscriptionApi } from "@/lib/subscription-api";
import { formatDate } from "@/lib/format";

export default function MembershipsPage() {
  const memberships = useQuery((t) => membershipApi.myMemberships(t), []);
  const subs = useQuery((t) => subscriptionApi.mine(t), []);
  const invitations = useQuery((t) => membershipApi.myInvitations(t), []);
  const [leaving, setLeaving] = useState<MyMembership | null>(null);
  const { run, busy } = useAction();

  async function answer(invitationId: string, accept: boolean) {
    const done = await run(
      async (t) => {
        if (accept) {
          await membershipApi.acceptInvitation(t, invitationId);
        } else {
          await membershipApi.declineInvitation(t, invitationId);
        }
        return true;
      },
      { success: accept ? "You joined." : "Invitation declined." },
    );
    if (done !== undefined) {
      await Promise.all([invitations.reload(), memberships.reload()]);
    }
  }

  return (
    <main className="page" style={{ maxWidth: "48rem" }}>
      <div>
        <h1>My messes</h1>
        <p className="muted" style={{ marginTop: "0.25rem" }}>
          Providers you&apos;re a member of.
        </p>
      </div>

      {(invitations.data ?? []).length > 0 ? (
        <div className="stack">
          <h2 style={{ fontSize: "1.05rem" }}>Invitations</h2>
          <Banner tone="info">
            A provider only gets to see your name, phone number and delivery address after you accept. You can leave any time.
          </Banner>
          {(invitations.data ?? []).map((i) => (
            <div key={i.invitationId} className="card row between" style={{ gap: "1rem" }}>
              <div className="row row-nowrap" style={{ minWidth: 0 }}>
                <span className="avatar">
                  <Mail size={18} />
                </span>
                <div style={{ minWidth: 0 }}>
                  <div className="strong truncate">{i.providerName}</div>
                  <div className="small muted">Invited you · expires {formatDate(i.expiresAt.slice(0, 10))}</div>
                </div>
              </div>
              <div className="row">
                <button className="btn" disabled={busy} onClick={() => answer(i.invitationId, false)}>Decline</button>
                <button className="btn btn-primary" disabled={busy} onClick={() => answer(i.invitationId, true)}>
                  {busy ? <Spinner /> : null} Accept
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : null}
      <Boundary query={memberships} rows={3}>
        {(list) =>
          list.length === 0 ? (
            <div className="card">
              <Empty icon={<Store size={26} />} title="No memberships yet">
                When a provider invites your number, you can accept the invitation here.
              </Empty>
            </div>
          ) : (
            <div className="stack">
              {list.map((m) => {
                const count = subs.data?.filter((s) => s.providerId === m.providerId && (s.phase === "RUNNING" || s.phase === "UPCOMING")).length;
                return (
                  <div key={m.membershipId} className="card row between" style={{ gap: "1rem" }}>
                    <div className="row row-nowrap" style={{ minWidth: 0 }}>
                      <span className="avatar">
                        <Store size={18} />
                      </span>
                      <div style={{ minWidth: 0 }}>
                        <div className="strong truncate">{m.providerName}</div>
                        <div className="small muted">
                          Joined {formatDate(m.joinedAt.slice(0, 10))}
                          {m.leftAt ? ` · left ${formatDate(m.leftAt.slice(0, 10))}` : ""}
                        </div>
                      </div>
                    </div>
                    <div className="row">
                      {count ? (
                        <Link href="/home" className="small">
                          {count} active plan{count === 1 ? "" : "s"}
                        </Link>
                      ) : null}
                      <MembershipBadge status={m.status} />
                      {m.status === "ACTIVE" ? (
                        <button className="btn btn-sm" onClick={() => setLeaving(m)}>Leave</button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          )
        }
      </Boundary>

      {leaving ? (
        <ConfirmDialog
          title={`Leave ${leaving.providerName}?`}
          confirmLabel="Leave"
          danger
          busy={busy}
          onClose={() => setLeaving(null)}
          onConfirm={async () => {
            const done = await run((t) => membershipApi.leave(t, leaving.membershipId), { success: "You left." });
            if (done) {
              setLeaving(null);
              await memberships.reload();
            }
          }}
        >
          <p>
            {leaving.providerName} will no longer be able to see your phone number or delivery address. Any plan you have with them
            stays with them to settle, so speak to them about it if you have one running.
          </p>
        </ConfirmDialog>
      ) : null}
    </main>
  );
}
