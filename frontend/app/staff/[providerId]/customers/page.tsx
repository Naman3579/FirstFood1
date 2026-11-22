"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ChevronRight, Clock, Plus, Users } from "lucide-react";
import { Avatar, Badge, Banner, Boundary, Empty, Modal, PageHeader, SearchBox, Spinner } from "@/components/ui";
import { MembershipBadge } from "@/components/domain/status";
import { PhoneField } from "@/components/domain/PhoneInput";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { membershipApi } from "@/lib/membership-api";
import { formatDate, isValidPhone, maskPhone, normalizePhone } from "@/lib/format";

export default function CustomersPage() {
  const { providerId, can, provider } = useProvider();
  const [inactive, setInactive] = useState(false);
  const [q, setQ] = useState("");
  const customers = useQuery((t) => membershipApi.list(t, providerId, inactive), [providerId, inactive]);
  const invitations = useQuery((t) => membershipApi.invitations(t, providerId), [providerId]);
  const [adding, setAdding] = useState(false);
  const [phoneInput, setPhoneInput] = useState("");
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const { run, busy } = useAction();
  const manage = can("MEMBERSHIP_MANAGE") && provider.status !== "CLOSED";
  const pending = (invitations.data ?? []).filter((i) => i.status === "PENDING");

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const list = customers.data ?? [];
    return needle ? list.filter((c) => c.fullName.toLowerCase().includes(needle) || (c.phone ?? "").includes(needle)) : list;
  }, [customers.data, q]);

  function close() {
    setAdding(false);
    setPhoneInput("");
    setPhoneError(null);
  }

  return (
    <main className="page">
      <PageHeader
        title="Customers"
        sub="People you serve. You invite a customer by phone number; they appear here once they accept the invitation in their own app."
        actions={manage ? <button className="btn btn-primary" onClick={() => setAdding(true)}><Plus size={18} /> Invite customer</button> : undefined}
      />
      <div className="filter-bar">
        <SearchBox placeholder="Search name or phone" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search customers" />
        <label className="check" style={{ alignItems: "center" }}>
          <input type="checkbox" checked={inactive} onChange={(e) => setInactive(e.target.checked)} /> Include former
        </label>
      </div>

      {pending.length > 0 ? (
        <div className="card card-flush">
          <div className="list">
            {pending.map((i) => (
              <div key={i.invitationId} className="list-item">
                <span className="avatar"><Clock size={16} /></span>
                <span className="grow">
                  <span className="strong num" style={{ display: "block" }}>{maskPhone(i.phone)}</span>
                  <span className="small muted">Waiting for them to accept · expires {formatDate(i.expiresAt.slice(0, 10))}</span>
                </span>
                <Badge tone="warn">Invited</Badge>
                {manage ? (
                  <button
                    className="btn btn-sm"
                    disabled={busy}
                    onClick={async () => {
                      const done = await run((t) => membershipApi.withdrawInvitation(t, providerId, i.invitationId), { success: "Invitation withdrawn." });
                      if (done) await invitations.reload();
                    }}
                  >
                    Withdraw
                  </button>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      ) : null}

      <Boundary query={customers} rows={5}>
        {() =>
          filtered.length === 0 ? (
            <div className="card">
              <Empty icon={<Users size={26} />} title={q ? "No matches" : "No customers yet"} action={!q && manage ? <button className="btn btn-primary" onClick={() => setAdding(true)}><Plus size={18} /> Invite your first customer</button> : undefined}>
                {q ? "Try a different name or number." : "Invite customers to start subscriptions and take attendance. They need to accept before they show up here."}
              </Empty>
            </div>
          ) : (
            <div className="card card-flush">
              <div className="list">
                {filtered.map((c) => (
                  <Link key={c.membershipId} href={`/staff/${providerId}/customers/${c.membershipId}`} className="list-item">
                    <Avatar name={c.fullName} />
                    <span className="grow">
                      <span className="strong truncate" style={{ display: "block" }}>{c.fullName}</span>
                      <span className="small muted num">{maskPhone(c.phone)}</span>
                    </span>
                    <MembershipBadge status={c.status} />
                    <ChevronRight size={18} className="faint" />
                  </Link>
                ))}
              </div>
            </div>
          )
        }
      </Boundary>

      {adding ? (
        <Modal title="Invite a customer" onClose={close}>
          <PhoneField value={phoneInput} onChange={setPhoneInput} error={phoneError} autoFocus hint="They will see your invitation when they sign in to the customer portal, and choose whether to accept." />
          <Banner tone="info">
            Nothing is shared until they accept: you will not see their name, phone number or address before then. You cannot tell whether a number already has an account.
          </Banner>
          <div className="modal-actions">
            <button className="btn" onClick={close}>Cancel</button>
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                const phone = normalizePhone(phoneInput);
                if (!isValidPhone(phone)) return setPhoneError("Enter a valid 10-digit mobile number.");
                const sent = await run((t) => membershipApi.invite(t, providerId, phone), { success: "Invitation sent." });
                if (sent) {
                  close();
                  await invitations.reload();
                  await customers.reload();
                }
              }}
            >
              {busy ? <Spinner /> : null} Send invitation
            </button>
          </div>
        </Modal>
      ) : null}
    </main>
  );
}
