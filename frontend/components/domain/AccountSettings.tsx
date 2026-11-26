"use client";

import { useState, type FormEvent } from "react";
import { BadgeCheck, LogOut, Mail, MapPin, Phone, ShieldAlert, UserRound } from "lucide-react";
import { Badge, Banner, Field, Modal, Spinner } from "@/components/ui";
import { PhoneField } from "@/components/domain/PhoneInput";
import { ApiStatus } from "@/components/domain/ApiStatus";
import { useAccount } from "@/components/shell/account";
import { ApiError } from "@/lib/api-client";
import { env } from "@/lib/env";
import { authApi, type Portal } from "@/lib/auth-api";
import { errorMessage, useAction } from "@/lib/hooks";
import { formatDateTime, isSmsSupportedPhone, isValidPhone, normalizePhone, SMS_REGION_MESSAGE } from "@/lib/format";
import { membershipApi } from "@/lib/membership-api";
import { useSession } from "@/lib/session";

/** A customer's name lives on their "person" record (what providers see); staff have only the account name. */
function NameCard({ portal }: { portal: Portal }) {
  const { person } = useAccount();
  const { profile } = useSession();
  const [name, setName] = useState<string | null>(null);
  const { run, busy } = useAction();

  if (portal === "staff") {
    return (
      <section className="card stack">
        <h2 className="row">
          <UserRound size={18} /> Your name
        </h2>
        <dl className="kv">
          <dt>Name</dt>
          <dd>{profile?.fullName ?? "—"}</dd>
        </dl>
        <p className="small muted">This is the name your team sees. You chose it when you created your staff account.</p>
      </section>
    );
  }

  const current = person.data?.fullName ?? "";
  const value = name ?? current;
  const dirty = value.trim() !== current && value.trim().length >= 2;

  async function save() {
    const saved = await run((t) => membershipApi.saveMyPerson(t, value.trim()), { success: "Name saved." });
    if (saved) {
      person.setData(saved);
      setName(null);
    }
  }

  return (
    <form className="card stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <h2 className="row">
        <UserRound size={18} /> Your name
      </h2>
      <p className="small muted">This is the name providers see on their customer list.</p>
      <Field label="Full name">
        {(id) => <input id={id} className="input" maxLength={100} autoComplete="name" value={value} onChange={(e) => setName(e.target.value)} placeholder="e.g. Rahul Sharma" />}
      </Field>
      <div>
        <button className="btn btn-primary" disabled={!dirty || busy}>
          {busy ? <Spinner /> : null}Save name
        </button>
      </div>
    </form>
  );
}

/** Where the customer wants food delivered / who the provider sees on their list. Customers only. */
function AddressCard() {
  const { person } = useAccount();
  const saved = person.data?.address ?? null;
  const [form, setForm] = useState<{ addressLine: string; locality: string; city: string; pincode: string; landmark: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { run, busy } = useAction();

  if (!person.data) return null;
  const value = form ?? {
    addressLine: saved?.addressLine ?? "",
    locality: saved?.locality ?? "",
    city: saved?.city ?? "",
    pincode: saved?.pincode ?? "",
    landmark: saved?.landmark ?? "",
  };
  const set = (k: keyof typeof value, v: string) => setForm({ ...value, [k]: v });
  const empty = Object.values(value).every((v) => !v.trim());
  const dirty = form !== null;

  async function save() {
    if (!empty && (!value.addressLine.trim() || !value.locality.trim() || !value.city.trim())) {
      return setError("Enter the street address, area and city.");
    }
    if (value.pincode && !/^[1-9][0-9]{5}$/.test(value.pincode)) return setError("Pincode must be 6 digits.");
    setError(null);
    const result = await run(
      (t) => membershipApi.saveMyAddress(t, empty ? null : { addressLine: value.addressLine.trim(), locality: value.locality.trim(), city: value.city.trim(), pincode: value.pincode || undefined, landmark: value.landmark.trim() || undefined }),
      { success: empty ? "Address removed." : "Address saved." },
    );
    if (result) {
      person.setData(result);
      setForm(null);
    }
  }

  return (
    <form className="card stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <h2 className="row"><MapPin size={18} /> Your address</h2>
      <p className="small muted">Providers you subscribe to can see this, for example to deliver your tiffin. Leave it empty if you do not want to share one.</p>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      <Field label="Street address">{(id) => <input id={id} className="input" maxLength={255} autoComplete="street-address" value={value.addressLine} onChange={(e) => set("addressLine", e.target.value)} placeholder="Flat / house no., building, street" />}</Field>
      <div className="grid grid-2">
        <Field label="Area / locality">{(id) => <input id={id} className="input" maxLength={120} value={value.locality} onChange={(e) => set("locality", e.target.value)} />}</Field>
        <Field label="City">{(id) => <input id={id} className="input" maxLength={100} autoComplete="address-level2" value={value.city} onChange={(e) => set("city", e.target.value)} />}</Field>
      </div>
      <div className="grid grid-2">
        <Field label="Pincode (optional)">{(id) => <input id={id} className="input" inputMode="numeric" maxLength={6} autoComplete="postal-code" value={value.pincode} onChange={(e) => set("pincode", e.target.value.replace(/\D/g, ""))} />}</Field>
        <Field label="Landmark (optional)">{(id) => <input id={id} className="input" maxLength={150} value={value.landmark} onChange={(e) => set("landmark", e.target.value)} />}</Field>
      </div>
      <div>
        <button className="btn btn-primary" disabled={!dirty || busy}>{busy ? <Spinner /> : null}Save address</button>
      </div>
    </form>
  );
}

/**
 * Email is a two-step change: a code goes to the NEW address and nothing is stored until it is verified, so an
 * address on the account is always one the person controls.
 */
function EmailCard() {
  const { profile, setProfile, call } = useSession();
  const [email, setEmail] = useState("");
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);

  const current = profile?.email ?? "";
  const verified = !!current && !!profile?.emailVerified;
  const showForm = editing || !current;

  async function send(e?: FormEvent) {
    e?.preventDefault();
    const address = email.trim().toLowerCase();
    if (!/^\S+@\S+\.\S+$/.test(address)) return setError("Enter a valid email address.");
    setError(null);
    setBusy(true);
    try {
      await call((t) => authApi.requestEmailChange(t, address));
      setSentTo(address);
      setCode("");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function verify(e: FormEvent) {
    e.preventDefault();
    if (!sentTo || code.length !== 6) return;
    setError(null);
    setBusy(true);
    try {
      const updated = await call((t) => authApi.verifyEmailChange(t, sentTo, code));
      setProfile(updated);
      setSentTo(null);
      setEmail("");
      setCode("");
      setEditing(false);
    } catch (err) {
      setError(errorMessage(err));
      if (err instanceof ApiError && err.code === "INVALID_OTP") setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 className="row">
          <Mail size={18} /> Email
        </h2>
        {current ? (
          verified ? (
            <Badge tone="ok">
              <BadgeCheck size={13} /> Verified
            </Badge>
          ) : (
            <Badge tone="warn">
              <ShieldAlert size={13} /> Not verified
            </Badge>
          )
        ) : null}
      </div>

      {current && !showForm ? (
        <>
          <dl className="kv">
            <dt>Address</dt>
            <dd>{current}</dd>
          </dl>
          {!verified ? <p className="small muted">This address was saved before verification existed. Verify it to use it for receipts and notices.</p> : null}
          <div>
            <button
              className="btn"
              onClick={() => {
                setEditing(true);
                setEmail(verified ? "" : current);
                setError(null);
              }}
            >
              {verified ? "Change email" : "Verify email"}
            </button>
          </div>
        </>
      ) : sentTo ? (
        <form className="stack" onSubmit={verify}>
          <p className="muted small">
            We emailed a 6-digit code to <span className="strong">{sentTo}</span>. It expires in 5 minutes.
          </p>
          {error ? <Banner tone="danger">{error}</Banner> : null}
          <Field label="Code">
            {(id) => (
              <input id={id} className="input otp" inputMode="numeric" autoComplete="one-time-code" maxLength={6} autoFocus value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} />
            )}
          </Field>
          <div className="row">
            <button className="btn btn-primary" disabled={busy || code.length !== 6}>
              {busy ? <Spinner /> : null}Verify email
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void send()}>
              Resend code
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => { setSentTo(null); setError(null); }}>
              Use a different address
            </button>
          </div>
        </form>
      ) : (
        <form className="stack" onSubmit={send} noValidate>
          {error ? <Banner tone="danger">{error}</Banner> : null}
          <Field label={current ? "New email address" : "Email address"} hint="Optional - for receipts and notices. We'll email you a code to confirm it's yours.">
            {(id) => <input id={id} type="email" className="input" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />}
          </Field>
          <div className="row">
            <button className="btn btn-primary" disabled={busy || !email.trim()}>
              {busy ? <Spinner /> : null}Send code
            </button>
            {editing && current ? (
              <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => { setEditing(false); setError(null); }}>
                Cancel
              </button>
            ) : null}
          </div>
        </form>
      )}
    </section>
  );
}

/** Adds a number without a code (launch mode). It is saved as "not verified" until text-message codes exist. */
function AddPhone({ onClose, initial }: { onClose: () => void; initial?: string }) {
  const { setProfile } = useSession();
  const [input, setInput] = useState(initial ?? "");
  const [error, setError] = useState<string | null>(null);
  const { run, busy } = useAction();

  async function save() {
    const normalized = normalizePhone(input);
    if (!isValidPhone(normalized)) return setError("Enter a valid 10-digit mobile number.");
    setError(null);
    const updated = await run((t) => authApi.addPhone(t, normalized), { success: "Phone number saved." });
    if (updated) {
      setProfile(updated);
      onClose();
    }
  }

  return (
    <Modal title={initial ? "Change phone number" : "Add phone number"} onClose={onClose}>
      <PhoneField label="Phone number" value={input} onChange={setInput} error={error} autoFocus hint="Saved without a code for now. We'll ask you to verify it once text-message codes are available." />
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>{busy ? <Spinner /> : null}Save</button>
      </div>
    </Modal>
  );
}

function ChangePhone({ onClose, initial }: { onClose: () => void; initial?: string }) {
  const { setProfile } = useSession();
  const [step, setStep] = useState<"enter" | "verify">("enter");
  const [input, setInput] = useState(initial?.replace(/^\+91/, "") ?? "");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const { run, busy } = useAction();

  async function send() {
    const normalized = normalizePhone(input);
    if (!isValidPhone(normalized)) return setError("Enter a valid 10-digit mobile number.");
    if (!isSmsSupportedPhone(normalized)) return setError(SMS_REGION_MESSAGE); // F-05: the code could never be sent
    setError(null);
    const sent = await run(async (t) => {
      await authApi.requestPhoneChange(t, normalized);
      return true;
    });
    if (!sent) return;
    setPhone(normalized);
    setStep("verify");
  }

  async function verify() {
    const updated = await run((t) => authApi.verifyPhoneChange(t, phone, code), { success: "Phone number updated." });
    if (updated) {
      setProfile(updated);
      onClose();
    }
  }

  return (
    <Modal title={initial ? "Verify phone number" : "Change phone number"} onClose={onClose}>
      {step === "enter" ? (
        <>
          <PhoneField label={initial ? "Phone number" : "New phone number"} value={input} onChange={setInput} error={error} autoFocus />
          <div className="modal-actions">
            <button className="btn" onClick={onClose}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void send()}>{busy ? <Spinner /> : null}Send code</button>
          </div>
        </>
      ) : (
        <>
          <p className="muted">Enter the 6-digit code sent to <span className="strong">{phone}</span>.</p>
          <Field label="Code">{(id) => <input id={id} className="input otp" inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} />}</Field>
          <div className="modal-actions">
            <button className="btn" onClick={() => setStep("enter")}>Back</button>
            <button className="btn btn-primary" disabled={busy || code.length !== 6} onClick={() => void verify()}>{busy ? <Spinner /> : null}Verify</button>
          </div>
        </>
      )}
    </Modal>
  );
}

/** Account page, shared by both portals: identity and verified contact details. */
export function AccountSettings({ portal }: { portal: Portal }) {
  const { profile, logout } = useSession();
  const [phoneDialog, setPhoneDialog] = useState<null | "add" | "change" | "verify">(null);

  return (
    <>
      {profile?.status === "SUSPENDED" ? <Banner tone="danger">This account is suspended. Contact support.</Banner> : null}

      <section className="card stack">
        <h2 className="row">
          <Phone size={18} /> Phone
        </h2>
        <dl className="kv">
          <dt>Number</dt>
          <dd className="num">
            {profile?.phone ?? "Not added"}{" "}
            {profile?.phone ? (
              profile.phoneVerified ? (
                <Badge tone="ok">
                  <BadgeCheck size={13} /> Verified
                </Badge>
              ) : (
                <Badge tone="warn">Not verified</Badge>
              )
            ) : null}
          </dd>
          <dt>Member since</dt>
          <dd>{profile ? formatDateTime(profile.createdAt) : "—"}</dd>
        </dl>
        {profile?.phone && !profile.phoneVerified ? (
          <p className="small muted">
            {env.phoneOtpEnabled
              ? "Verify this number with a code. Unverified numbers can't be used to sign in."
              : "We'll ask you to verify this number once text-message codes are available."}
          </p>
        ) : null}
        <div className="row" style={{ gap: "0.5rem", flexWrap: "wrap" }}>
          {profile?.phone && !profile.phoneVerified && env.phoneOtpEnabled ? (
            <button className="btn btn-primary" onClick={() => setPhoneDialog("verify")}>Verify number</button>
          ) : null}
          {env.phoneOtpEnabled || !profile?.phone || !profile.phoneVerified ? (
            <button className="btn" onClick={() => setPhoneDialog(env.phoneOtpEnabled && (!profile?.phone || profile.phoneVerified) ? "change" : "add")}>
              {profile?.phone ? "Change number" : "Add number"}
            </button>
          ) : null}
        </div>
        {!env.phoneOtpEnabled && profile?.phone && profile.phoneVerified ? (
          <p className="small muted">Changing a verified number needs a text-message code, which isn&apos;t available yet.</p>
        ) : null}
      </section>

      <NameCard portal={portal} />
      {portal === "customer" ? <AddressCard /> : null}
      <EmailCard />

      <button className="btn btn-block" onClick={() => void logout()}>
        <LogOut size={18} /> Sign out
      </button>
      <ApiStatus />
      {phoneDialog === "add" ? <AddPhone onClose={() => setPhoneDialog(null)} initial={profile?.phone ?? undefined} /> : null}
      {phoneDialog === "change" ? <ChangePhone onClose={() => setPhoneDialog(null)} /> : null}
      {phoneDialog === "verify" && profile?.phone ? <ChangePhone onClose={() => setPhoneDialog(null)} initial={profile.phone} /> : null}
    </>
  );
}
