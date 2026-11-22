"use client";

import { useState } from "react";
import { Lock, PauseCircle, PlayCircle, Users } from "lucide-react";
import { Banner, ConfirmDialog, Field, Spinner } from "@/components/ui";
import { OwnerStepUp } from "@/components/domain/OwnerStepUp";
import { ProviderForm } from "@/components/domain/ProviderForm";
import { PROVIDER_STATUS_LABEL, ProviderStatusBadge } from "@/components/domain/status";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction } from "@/lib/hooks";
import { allowedTransitions, providerApi, type ProviderStatus } from "@/lib/provider-api";
import { useAccount } from "@/components/shell/account";

export default function SettingsPage() {
  const { provider, providerId, can, setProvider } = useProvider();
  const { providers } = useAccount();
  const { run, busy } = useAction();
  const [closing, setClosing] = useState(false);
  const [reason, setReason] = useState("");
  const [stepUpCode, setStepUpCode] = useState("");
  const [codeError, setCodeError] = useState<string | null>(null);
  const [capacity, setCapacity] = useState(provider.maxActiveSubscriptions?.toString() ?? "");
  const editable = can("PROVIDER_EDIT") && provider.status !== "CLOSED";

  async function patch(input: Parameters<typeof providerApi.update>[2], success: string) {
    const updated = await run((t) => providerApi.update(t, providerId, input), { success });
    if (updated) {
      setProvider(updated);
      void providers.reload();
    }
    return updated;
  }

  const transitions = allowedTransitions(provider.status).filter((s) => s !== "CLOSED");
  const icon = (s: ProviderStatus) => (s === "ACTIVE" ? <PlayCircle size={16} /> : s === "FULL" ? <Users size={16} /> : <PauseCircle size={16} />);

  return (
    <main className="page" style={{ maxWidth: "46rem" }}>
      <div className="page-header">
        <div>
          <h1>Settings</h1>
          <p className="sub">Provider profile, availability and capacity.</p>
        </div>
      </div>
      {!editable ? <Banner>{provider.status === "CLOSED" ? "Closed providers can't be edited." : "You can view these settings but not change them."}</Banner> : null}

      <section className="card stack">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2>Availability</h2>
          <ProviderStatusBadge status={provider.status} />
        </div>
        {transitions.length > 0 && editable ? (
          <div className="row">
            {transitions.map((s) => (
              <button key={s} className="btn" disabled={busy} onClick={() => void patch({ status: s }, `Marked as ${PROVIDER_STATUS_LABEL[s].toLowerCase()}.`)}>
                {icon(s)} Mark {PROVIDER_STATUS_LABEL[s].toLowerCase()}
              </button>
            ))}
          </div>
        ) : null}
        <label className="check">
          <input
            type="checkbox"
            checked={provider.acceptingNewCustomers}
            disabled={!editable || busy}
            onChange={(e) => void patch({ acceptingNewCustomers: e.target.checked }, e.target.checked ? "Now accepting new customers." : "Paused new customers.")}
          />
          <span>
            <span className="strong">Accepting new customers</span>
            <span className="hint" style={{ display: "block" }}>Turn off to stop taking on new members without closing.</span>
          </span>
        </label>
      </section>

      <section className="card stack">
        <h2>Capacity</h2>
        <p className="small muted">The most active subscriptions you&apos;ll serve at once. Leave unlimited if you don&apos;t cap it.</p>
        <div className="row" style={{ alignItems: "flex-end" }}>
          <div style={{ width: "10rem" }}>
            <Field label="Max active subscriptions">
              {(id) => <input id={id} className="input num" inputMode="numeric" disabled={!editable} value={capacity} placeholder="Unlimited" onChange={(e) => setCapacity(e.target.value.replace(/\D/g, ""))} />}
            </Field>
          </div>
          <button
            className="btn btn-primary"
            disabled={!editable || busy || capacity === (provider.maxActiveSubscriptions?.toString() ?? "")}
            onClick={() => void patch(capacity ? { maxActiveSubscriptions: Number(capacity) } : { unlimitedCapacity: true }, "Capacity updated.")}
          >
            {busy ? <Spinner /> : null} Save
          </button>
        </div>
      </section>

      <section className="card stack">
        <h2>Profile</h2>
        {editable ? (
          <ProviderForm
            key={provider.updatedAt}
            initial={provider}
            submitLabel="Save changes"
            busy={busy}
            onSubmit={(v) => void patch({ ...v }, "Profile saved.")}
          />
        ) : (
          <dl className="kv">
            <dt>Name</dt><dd>{provider.name}</dd>
            <dt>Address</dt><dd>{provider.addressLine}, {provider.locality}, {provider.city} {provider.pincode}</dd>
            <dt>Contact</dt><dd>{provider.contactPhone ?? "—"}</dd>
            <dt>About</dt><dd>{provider.description ?? "—"}</dd>
          </dl>
        )}
      </section>

      {can("PROVIDER_CLOSE") && provider.status !== "CLOSED" ? (
        <section className="card stack" style={{ borderColor: "var(--danger)" }}>
          <h2 style={{ color: "var(--danger)" }}>Close provider</h2>
          <p className="small muted">Closing is permanent: no new subscriptions, customers or plans. Existing records stay readable. Need a break instead? Use <strong>Temporarily unavailable</strong> above - you can switch back any time.</p>
          <div><button className="btn btn-danger" onClick={() => setClosing(true)}><Lock size={16} /> Close this provider</button></div>
        </section>
      ) : null}

      {provider.status === "CLOSED" ? (
        <Banner tone="warn">Closed {provider.closedAt ? new Date(provider.closedAt).toLocaleDateString("en-IN") : ""}{provider.closureReason ? ` - ${provider.closureReason}` : ""}.</Banner>
      ) : null}

      {closing ? (
        <ConfirmDialog
          title="Close this provider?"
          confirmLabel="Close permanently"
          danger
          busy={busy}
          onClose={() => { setClosing(false); setStepUpCode(""); setCodeError(null); }}
          onConfirm={async () => {
            setCodeError(null);
            if (stepUpCode.length !== 6) return setCodeError("Enter the 6-digit code we texted you.");
            if (await patch({ status: "CLOSED", closureReason: reason.trim() || undefined, stepUpCode }, "Provider closed.")) {
              setClosing(false);
              setStepUpCode("");
            }
          }}
        >
          <p>This cannot be undone. Customers will no longer be able to start new plans.</p>
          <Field label="Reason (optional)">{(id) => <input id={id} className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
          <OwnerStepUp providerId={providerId} code={stepUpCode} onCode={setStepUpCode} error={codeError} />
        </ConfirmDialog>
      ) : null}
    </main>
  );
}
