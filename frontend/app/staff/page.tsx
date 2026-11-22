"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ChevronRight, MapPin, Plus, Store } from "lucide-react";
import { Avatar, Banner, Boundary, ConfirmDialog, Empty, Modal } from "@/components/ui";
import { StaffTopbar } from "@/components/shell/StaffTopbar";
import { ProviderForm } from "@/components/domain/ProviderForm";
import { providerTypesText, ProviderStatusBadge } from "@/components/domain/status";
import { useAccount } from "@/components/shell/account";
import { useAction, useQuery } from "@/lib/hooks";
import { roleApi, type MyOwnershipOffer } from "@/lib/role-api";
import { formatDateTime } from "@/lib/format";
import { providerApi } from "@/lib/provider-api";

export default function StaffHome() {
  const { providers } = useAccount();
  const router = useRouter();
  const [creating, setCreating] = useState(false);
  const { run, busy } = useAction();
  const offers = useQuery((t) => roleApi.myOffers(t), []);
  const [accepting, setAccepting] = useState<MyOwnershipOffer | null>(null);

  return (
    <>
      <StaffTopbar />
      <main className="page" style={{ maxWidth: "52rem" }}>
        <div className="page-header">
          <div>
            <h1>Your providers</h1>
            <p className="sub">Choose a kitchen to manage, or register a new one.</p>
          </div>
          <button className="btn btn-primary" onClick={() => setCreating(true)}>
            <Plus size={18} /> Register provider
          </button>
        </div>

        {(offers.data ?? []).map((o) => (
          <Banner
            key={o.id}
            tone="warn"
            action={
              <>
                <button className="btn btn-sm" disabled={busy} onClick={async () => {
                  await run((t) => roleApi.declineOffer(t, o.id).then(() => true), { success: "Offer declined." });
                  await offers.reload(); // also on failure: the offer may have expired or been withdrawn meanwhile
                }}>Decline</button>
                <button className="btn btn-sm btn-primary" disabled={busy} onClick={() => setAccepting(o)}>Review</button>
              </>
            }
          >
            The owner of <span className="strong">{o.providerName}</span> (number ending {o.offeredByPhoneHint}) wants to hand it over to you.
            Open until {formatDateTime(o.expiresAt)}.
          </Banner>
        ))}

        <Boundary query={providers} rows={3}>
          {(list) =>
            list.length === 0 ? (
              <div className="card">
                <Empty
                  icon={<Store size={26} />}
                  title="Run a mess, tiffin or hostel kitchen?"
                  action={
                    <button className="btn btn-primary" onClick={() => setCreating(true)}>
                      <Plus size={18} /> Register your first provider
                    </button>
                  }
                >
                  Set up plans, add customers and take attendance from one place. You can invite your team afterwards.
                </Empty>
              </div>
            ) : (
              <div className="grid grid-2">
                {list.map((p) => (
                  <Link key={p.id} href={`/staff/${p.id}`} className="card card-link stack">
                    <div className="row between row-nowrap" style={{ alignItems: "flex-start" }}>
                      <div className="row row-nowrap" style={{ minWidth: 0 }}>
                        <Avatar name={p.name} />
                        <div style={{ minWidth: 0 }}>
                          <div className="strong truncate">{p.name}</div>
                          <div className="small muted">
                            {providerTypesText(p.providerTypes)} · {p.myRole.charAt(0) + p.myRole.slice(1).toLowerCase()}
                          </div>
                        </div>
                      </div>
                      <ProviderStatusBadge status={p.status} />
                    </div>
                    <div className="small muted row" style={{ gap: "0.3rem" }}>
                      <MapPin size={14} /> {p.locality}, {p.city}
                    </div>
                    <div className="row between small" style={{ color: "var(--primary)", fontWeight: 600 }}>
                      Open workspace <ChevronRight size={16} />
                    </div>
                  </Link>
                ))}
              </div>
            )
          }
        </Boundary>

        {accepting ? (
          <ConfirmDialog
            title={`Become the owner of ${accepting.providerName}?`}
            confirmLabel="Accept ownership"
            busy={busy}
            onClose={() => setAccepting(null)}
            onConfirm={async () => {
              const done = await run((t) => roleApi.acceptOffer(t, accepting.id), { success: "You are now the owner." });
              setAccepting(null);
              await Promise.all([offers.reload(), providers.reload()]);
              if (done) router.push(`/staff/${accepting.providerId}`);
            }}
          >
            <p>The owner (number ending {accepting.offeredByPhoneHint}) hands <span className="strong">{accepting.providerName}</span> over to you. You get full control, including closing the provider and managing the team. The current owner becomes a manager.</p>
          </ConfirmDialog>
        ) : null}

        {creating ? (
          <Modal title="Register a provider" onClose={() => setCreating(false)} wide>
            <ProviderForm
              submitLabel="Create provider"
              busy={busy}
              onCancel={() => setCreating(false)}
              onSubmit={async (values) => {
                const created = await run((t) => providerApi.create(t, values), { success: "Provider created." });
                if (created) {
                  await providers.reload();
                  router.push(`/staff/${created.id}`);
                }
              }}
            />
          </Modal>
        ) : null}
      </main>
    </>
  );
}
