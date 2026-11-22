"use client";

import { useState } from "react";
import { Crown, ShieldCheck, UserMinus, UserPlus } from "lucide-react";
import { Avatar, Badge, Banner, Boundary, ConfirmDialog, Field, Modal, PageHeader, Spinner } from "@/components/ui";
import { PhoneField } from "@/components/domain/PhoneInput";
import { OwnerStepUp } from "@/components/domain/OwnerStepUp";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { roleApi, type AssignableRole, type RoleAssignment } from "@/lib/role-api";
import { formatDateTime, isValidPhone, normalizePhone } from "@/lib/format";
import { useSession } from "@/lib/session";

const ROLE_INFO: Record<string, string> = {
  OWNER: "Full control, including closing the provider and managing the team. Closing and handing over ownership need a code texted to the owner.",
  MANAGER: "Runs day-to-day: customers, plans, subscriptions, attendance and extensions.",
  WORKER: "Takes and corrects attendance and can look up customers.",
};

export default function TeamPage() {
  const { providerId, can, provider } = useProvider();
  const { profile } = useSession();
  const roles = useQuery((t) => roleApi.list(t, providerId), [providerId]);
  const canTransfer = can("OWNER_TRANSFER");
  const offers = useQuery((t) => roleApi.listOffers(t, providerId), [providerId], { enabled: canTransfer });
  const pendingOffer = offers.data?.find((o) => o.status === "PENDING");
  const { run, busy } = useAction();
  const [stepUpCode, setStepUpCode] = useState("");
  const [codeError, setCodeError] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [revoking, setRevoking] = useState<RoleAssignment | null>(null);
  const [transfer, setTransfer] = useState(false);
  const [phoneInput, setPhoneInput] = useState("");
  const [role, setRole] = useState<AssignableRole>("WORKER");
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const writable = provider.status !== "CLOSED";

  function reset() {
    setAssigning(false);
    setTransfer(false);
    setPhoneInput("");
    setPhoneError(null);
    setStepUpCode("");
    setCodeError(null);
    setRole("WORKER");
  }

  return (
    <main className="page" style={{ maxWidth: "52rem" }}>
      <PageHeader
        title="Team"
        sub="People who can work in this provider. Each person signs in to the staff portal with their own phone number."
        actions={
          writable ? (
            <>
              {canTransfer && !pendingOffer ? (
                <button className="btn" onClick={() => setTransfer(true)}>
                  <Crown size={16} /> Hand over ownership
                </button>
              ) : null}
              {can("ROLE_ASSIGN") ? (
                <button className="btn btn-primary" onClick={() => setAssigning(true)}>
                  <UserPlus size={18} /> Add member
                </button>
              ) : null}
            </>
          ) : undefined
        }
      />

      <div className="grid grid-3">
        {Object.entries(ROLE_INFO).map(([r, text]) => (
          <div key={r} className="card" style={{ boxShadow: "none" }}>
            <div className="strong row" style={{ gap: "0.4rem" }}><ShieldCheck size={16} /> {r.charAt(0) + r.slice(1).toLowerCase()}</div>
            <p className="small muted" style={{ marginTop: "0.35rem" }}>{text}</p>
          </div>
        ))}
      </div>

      {pendingOffer ? (
        <Banner
          tone="warn"
          action={
            <button className="btn btn-sm" disabled={withdrawing} onClick={async () => {
              setWithdrawing(true);
              const done = await run((t) => roleApi.withdrawOffer(t, providerId, pendingOffer.id), { success: "Offer withdrawn." });
              setWithdrawing(false);
              if (done) await offers.reload();
            }}>
              Withdraw offer
            </button>
          }
        >
          You offered ownership to <span className="strong num">{pendingOffer.targetPhone}</span>. Nothing changes until they accept
          (offer open until {formatDateTime(pendingOffer.expiresAt)}). You stay the owner meanwhile.
        </Banner>
      ) : null}

      <Boundary query={roles} rows={3}>
        {(list) => {
          const active = list.filter((r) => r.status === "ACTIVE");
          const past = list.filter((r) => r.status === "REVOKED");
          return (
            <>
              <div className="card card-flush">
                <ul className="list">
                  {active.map((a) => (
                    <li key={a.id}>
                      <Avatar name={a.accountPhone ?? "?"} />
                      <div className="grow">
                        <div className="strong num truncate">{a.accountPhone ?? "Unknown number"}{a.accountPhone === profile?.phone ? <span className="muted"> (you)</span> : null}</div>
                        <div className="small muted">Added {formatDateTime(a.assignedAt)}</div>
                      </div>
                      <Badge tone={a.role === "OWNER" ? "accent" : a.role === "MANAGER" ? "info" : "neutral"}>{a.role.charAt(0) + a.role.slice(1).toLowerCase()}</Badge>
                      {can("ROLE_REVOKE") && a.role !== "OWNER" && writable ? (
                        <button className="btn btn-sm btn-ghost btn-icon" aria-label={`Remove ${a.accountPhone}`} onClick={() => setRevoking(a)}>
                          <UserMinus size={16} />
                        </button>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </div>
              {past.length > 0 ? (
                <details className="card">
                  <summary className="strong" style={{ cursor: "pointer" }}>Former members ({past.length})</summary>
                  <ul className="list" style={{ marginTop: "0.75rem" }}>
                    {past.map((a) => (
                      <li key={a.id} className="small">
                        <span className="grow num">{a.accountPhone}</span>
                        <span className="muted">{a.role.toLowerCase()} · removed {formatDateTime(a.revokedAt)}</span>
                      </li>
                    ))}
                  </ul>
                </details>
              ) : null}
            </>
          );
        }}
      </Boundary>

      {assigning ? (
        <Modal title="Add a team member" onClose={reset}>
          <PhoneField value={phoneInput} onChange={setPhoneInput} error={phoneError} autoFocus hint="They need a staff account first - they can create one on the staff sign-in page with this number." />
          <Field label="Role">
            {(id) => (
              <select id={id} className="select" value={role} onChange={(e) => setRole(e.target.value as AssignableRole)}>
                <option value="WORKER">Worker - attendance only</option>
                <option value="MANAGER">Manager - run the kitchen</option>
              </select>
            )}
          </Field>
          <div className="modal-actions">
            <button className="btn" onClick={reset}>Cancel</button>
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                const phone = normalizePhone(phoneInput);
                if (!isValidPhone(phone)) return setPhoneError("Enter a valid 10-digit mobile number.");
                const added = await run((t) => roleApi.assign(t, providerId, { phone, role }), { success: "Team member added." });
                if (added) {
                  reset();
                  await roles.reload();
                }
              }}
            >
              {busy ? <Spinner /> : null} Add member
            </button>
          </div>
        </Modal>
      ) : null}

      {revoking ? (
        <ConfirmDialog
          title="Remove team member?"
          confirmLabel="Remove"
          danger
          busy={busy}
          onClose={() => setRevoking(null)}
          onConfirm={async () => {
            if (await run((t) => roleApi.revoke(t, providerId, revoking.id), { success: "Access removed." })) {
              setRevoking(null);
              await roles.reload();
            }
          }}
        >
          <p><span className="strong num">{revoking.accountPhone}</span> will lose access to this provider immediately.</p>
        </ConfirmDialog>
      ) : null}

      {transfer ? (
        <Modal title="Hand over ownership" onClose={reset}>
          <Banner tone="warn">
            This sends an offer. Nothing changes until that person signs in and accepts; then they become the owner and you become a manager.
          </Banner>
          <PhoneField label="New owner's phone" value={phoneInput} onChange={setPhoneInput} error={phoneError} autoFocus hint="They need a staff account to accept." />
          <OwnerStepUp providerId={providerId} code={stepUpCode} onCode={setStepUpCode} error={codeError} />
          <div className="modal-actions">
            <button className="btn" onClick={reset}>Cancel</button>
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={async () => {
                const phone = normalizePhone(phoneInput);
                setCodeError(null);
                if (!isValidPhone(phone)) return setPhoneError("Enter a valid 10-digit mobile number.");
                if (stepUpCode.length !== 6) return setCodeError("Enter the 6-digit code we texted you.");
                const offer = await run((t) => roleApi.offerOwnership(t, providerId, { phone, code: stepUpCode }), {
                  success: "Offer sent. Ownership moves only when they accept.",
                });
                if (offer) {
                  reset();
                  await offers.reload();
                }
              }}
            >
              {busy ? <Spinner /> : <Crown size={16} />} Send offer
            </button>
          </div>
        </Modal>
      ) : null}
    </main>
  );
}
