"use client";

import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ArrowLeft, Leaf, Mail, Smartphone } from "lucide-react";
import { Banner, Brand, Field, Spinner } from "@/components/ui";
import { ApiStatus } from "@/components/domain/ApiStatus";
import { BotChallenge } from "@/components/auth/BotChallenge";
import { GoogleButton } from "@/components/auth/GoogleButton";
import { ApiError } from "@/lib/api-client";
import { env } from "@/lib/env";
import type { Identifier, Portal } from "@/lib/auth-api";
import { errorMessage } from "@/lib/hooks";
import { detectIdentifierKind, isSmsSupportedPhone, isValidEmail, isValidPhone, normalizeEmail, normalizePhone, SMS_REGION_MESSAGE } from "@/lib/format";
import { useSession } from "@/lib/session";
import { PORTAL_COPY } from "./portal-copy";

const RESEND_SECONDS = 30;
type Tab = "signin" | "signup";

const PHONE_OFF_MESSAGE = "Mobile number sign-in isn't available yet. Please use your email address instead.";

/** Turns what was typed into something we can send a code to, or says what is wrong with it. */
function resolveIdentifier(raw: string): { id: Identifier } | { error: string } {
  const kind = detectIdentifierKind(raw);
  if (kind === "phone") {
    if (!env.phoneOtpEnabled) return { error: PHONE_OFF_MESSAGE };
    const normalized = normalizePhone(raw);
    if (!isValidPhone(normalized)) return { error: "Enter a valid 10-digit mobile number." };
    // Same rule the server applies (F-05); checked here so nothing is spent on a number that can never get a code.
    if (!isSmsSupportedPhone(normalized)) return { error: SMS_REGION_MESSAGE };
    return { id: { type: "phone", value: normalized } };
  }
  const email = normalizeEmail(raw);
  if (kind === "empty" || !isValidEmail(email)) return { error: "Enter a valid email address." };
  return { id: { type: "email", value: email } };
}

/**
 * The sign-in / create-account screen for ONE portal (customer at "/", staff at "/staff").
 *
 *   Sign in:         email or mobile -> Send code -> code -> sign in
 *   Create account:  email or mobile + name -> Send code -> code -> sign up
 *
 * One box takes either an email or a mobile number (an "@" means email). Email codes always work; mobile codes are an
 * SMS, which stays switched off (env.phoneOtpEnabled) until an SMS provider and DLT registration exist - typing a number
 * then explains that and points to email instead of failing at the server.
 *
 * The OTP only proves you own the email or number; the two endpoints decide what it opens. The backend rejects a wrong-door
 * attempt WITHOUT using up the code, so when sign-in finds no account we switch to "Create account" and when sign-up
 * finds one we sign the person in - in both cases with the code they already typed, never a second SMS.
 *
 * F-05: asking for a code sends an SMS or email, so while the backend's bot challenge is on (a Turnstile site key is configured) the
 * "Send code" and "Resend code" buttons wait for a human-check token. A token is good for ONE request, so it is spent and
 * replaced after every attempt, successful or not.
 *
 * `existing` is set when the person is already signed in (on the other portal) but has no registration here: their
 * email or number and name are known, so only a fresh code is needed.
 */
export function PortalAuth({ portal, existing, notice }: { portal: Portal; existing?: { identifier: Identifier; fullName: string | null }; notice?: ReactNode }) {
  const copy = PORTAL_COPY[portal];
  const Icon = copy.icon;
  const { requestOtp, signIn, signUp, signInWithGoogle, signUpWithGoogle, logout } = useSession();

  const [tab, setTab] = useState<Tab>(existing ? "signup" : "signin");
  const [step, setStep] = useState<"form" | "code">("form");
  const [idInput, setIdInput] = useState(existing ? existing.identifier.value : "");
  // What the code was actually sent to (set once a send succeeds).
  const [identifier, setIdentifier] = useState<Identifier | null>(null);
  const knownName = existing?.fullName?.trim() || "";
  const [name, setName] = useState(knownName);
  // True when the name was already collected on the first screen, so the code screen must not ask for it again.
  const [nameCollected, setNameCollected] = useState(false);
  const [code, setCode] = useState("");
  // True once the code has been accepted as "the code to use" and the person is only being asked for their name.
  // The code field is then hidden (and the typed code kept in state), so it cannot be edited any more.
  const [codeLocked, setCodeLocked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const codeRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  // F-05 human check. `challengeToken` is null until the widget hands one over; `challengeKey` asks it for a fresh one.
  const challengeRequired = env.turnstileSiteKey !== "";
  const [challengeToken, setChallengeToken] = useState<string | null>(null);
  const [challengeKey, setChallengeKey] = useState(0);
  const waitingForChallenge = challengeRequired && challengeToken === null;

  // Google needs no code, and it would replace the session of someone signed in on the other portal, so it is only for a fresh visit.
  const googleOn = env.googleClientId !== "" && !existing;
  const kind = detectIdentifierKind(idInput);
  const phoneBlocked = kind === "phone" && !env.phoneOtpEnabled;
  const sentTo = identifier?.type === "email" ? "email" : "number";
  const copyTab = tab === "signin" ? copy.signin : copy.signup;
  const nameOk = name.trim().length >= 2 && name.trim().length <= 100;
  const needsName = tab === "signup" && !knownName;
  // Show the name box on the code screen only when it is still missing there (sign-in turned out to need an account).
  // This must NOT depend on what has been typed: keying it to nameOk unmounted the input after two letters.
  const askNameOnCodeStep = needsName && !nameCollected;

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  useEffect(() => {
    if (step !== "code") return;
    // If we arrived here from a failed sign-in, the missing piece is the name; otherwise it is the code.
    (askNameOnCodeStep || codeLocked ? nameRef : codeRef).current?.focus();
  }, [step, tab, codeLocked]);

  // Each step mounts its own widget, which issues its own token; never carry one across (it may be spent or expired).
  useEffect(() => {
    setChallengeToken(null);
  }, [step]);

  function switchTab(next: Tab) {
    setTab(next);
    setError(null);
    setNameError(null);
    setInfo(null);
  }

  function checkName(): boolean {
    if (!needsName) return true;
    if (nameOk) {
      setNameError(null);
      return true;
    }
    setNameError("Enter your name (2 to 100 characters).");
    return false;
  }

  async function send(e?: FormEvent) {
    e?.preventDefault();
    const resolved = step === "form" ? resolveIdentifier(idInput) : identifier ? { id: identifier } : resolveIdentifier(idInput);
    if ("error" in resolved) {
      setError(resolved.error);
      return;
    }
    if (step === "form" && !checkName()) return;
    if (waitingForChallenge) return; // buttons are disabled; this guards Enter-key submits
    setError(null);
    setInfo(null);
    setBusy(true);
    const token = challengeToken;
    // The token is single-use whatever the outcome: forget it now and ask the widget for the next one.
    setChallengeToken(null);
    try {
      await requestOtp(resolved.id, token);
      setIdentifier(resolved.id);
      if (step === "form") setNameCollected(needsName);
      setCode("");
      setCodeLocked(false);
      setCooldown(RESEND_SECONDS);
      setStep("code");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setChallengeKey((k) => k + 1);
      setBusy(false);
    }
  }

  async function attempt(which: Tab) {
    if (!identifier) return;
    if (which === "signin") await signIn(portal, identifier, code);
    else await signUp(portal, identifier, code, name.trim());
  }

  async function verify(e?: FormEvent) {
    e?.preventDefault();
    if (code.length !== 6) return;
    if (tab === "signup" && !checkName()) {
      nameRef.current?.focus();
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await attempt(tab);
      // Success: the session flips to signed-in and the surrounding gate swaps this screen for the app.
    } catch (err) {
      const apiCode = err instanceof ApiError ? err.code : "";
      if (apiCode === "ACCOUNT_NOT_REGISTERED") {
        // Not an error: this number just has no account here yet. Keep the code, ask for the one missing field.
        setTab("signup");
        setCodeLocked(true);
        setInfo(copy.notRegistered);
        setNameError(null);
        setBusy(false);
        return;
      }
      if (apiCode === "ACCOUNT_ALREADY_REGISTERED") {
        setTab("signin");
        setInfo(copy.alreadyRegistered);
        try {
          await attempt("signin");
        } catch (err2) {
          setInfo(null);
          setError(errorMessage(err2));
          setBusy(false);
        }
        return;
      }
      setError(errorMessage(err));
      if (apiCode === "INVALID_OTP") {
        setCode("");
        setCodeLocked(false);
      } else if (apiCode.startsWith("OTP_")) {
        setCodeLocked(false);
      }
      setBusy(false);
    }
  }

  // "Continue with Google": the credential already proves the email, so there is no code step. Same door rules as the
  // code flow: sign-in with no account here moves to "Create account"; sign-up when one exists just signs in.
  async function onGoogleCredential(credential: string) {
    if (busy) return;
    setError(null);
    setInfo(null);
    setBusy(true);
    try {
      if (tab === "signin") await signInWithGoogle(portal, credential);
      else await signUpWithGoogle(portal, credential);
      // Success: the session flips to signed-in and the surrounding gate swaps this screen for the app.
    } catch (err) {
      const apiCode = err instanceof ApiError ? err.code : "";
      if (apiCode === "ACCOUNT_NOT_REGISTERED") {
        setTab("signup");
        setInfo(`There's no ${portal} account for this Google account yet. Choose "Continue with Google" again to create one.`);
      } else if (apiCode === "ACCOUNT_ALREADY_REGISTERED") {
        setTab("signin");
        setInfo(copy.alreadyRegistered);
        try {
          await signInWithGoogle(portal, credential);
          return;
        } catch (err2) {
          setInfo(null);
          setError(errorMessage(err2));
        }
      } else {
        setError(errorMessage(err));
      }
      setBusy(false);
    }
  }

  return (
    <div className="auth" data-portal={portal}>
      <aside className="auth-art" aria-hidden="true">
        <Brand href={copy.home} />
        <div className="auth-art-body">
          <span className="portal-chip portal-chip-on-art">
            <Icon size={15} /> {copy.chip}
          </span>
          <h2>{copy.art.title}</h2>
          <ul>
            {copy.art.points.map(({ icon: PointIcon, text }) => (
              <li key={text}>
                <PointIcon size={20} /> {text}
              </li>
            ))}
          </ul>
        </div>
        <span className="small auth-art-foot">{copy.art.foot}</span>
      </aside>

      <main className="auth-form">
        <div className="auth-card">
          <div className="only-lg-hide">
            <Brand href={copy.home} />
          </div>
          <span className="portal-chip">
            <Icon size={15} /> {copy.chip}
          </span>

          {notice ? <Banner tone="info">{notice}</Banner> : null}

          {step === "form" ? (
            <form className="stack" onSubmit={send} noValidate>
              <div className="seg seg-block" role="tablist" aria-label={`${copy.chip} access`}>
                <button type="button" role="tab" aria-selected={tab === "signin"} aria-pressed={tab === "signin"} onClick={() => switchTab("signin")}>
                  Sign in
                </button>
                <button type="button" role="tab" aria-selected={tab === "signup"} aria-pressed={tab === "signup"} onClick={() => switchTab("signup")}>
                  Create account
                </button>
              </div>

              <div className="stack stack-s">
                <h1>{copyTab.title}</h1>
                <p className="muted">{copyTab.sub}</p>
              </div>

              {info ? <Banner tone="info">{info}</Banner> : null}
              {error ? <Banner tone="danger">{error}</Banner> : null}

              <Field label="Email or mobile number" hint={phoneBlocked ? undefined : "We'll send a 6-digit code to verify it."}>
                {(id) => (
                  <input
                    id={id}
                    className="input"
                    type="text"
                    inputMode="email"
                    autoComplete="username"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    autoFocus={!existing}
                    maxLength={255}
                    placeholder="you@example.com"
                    value={idInput}
                    onChange={(e) => setIdInput(e.target.value)}
                  />
                )}
              </Field>
              {phoneBlocked ? <Banner tone="info">{PHONE_OFF_MESSAGE}</Banner> : null}

              {needsName ? (
                <Field label={copy.name.label} hint={copy.name.hint} error={nameError}>
                  {(id) => (
                    <input
                      id={id}
                      className="input"
                      autoComplete="name"
                      maxLength={100}
                      placeholder={copy.name.placeholder}
                      value={name}
                      aria-invalid={nameError ? true : undefined}
                      onChange={(e) => setName(e.target.value)}
                    />
                  )}
                </Field>
              ) : null}

              <BotChallenge onToken={setChallengeToken} resetKey={challengeKey} />
              <button
                className="btn btn-primary btn-block"
                disabled={busy || waitingForChallenge || phoneBlocked || idInput.trim().length < 5}
              >
                {busy ? <Spinner /> : kind === "phone" ? <Smartphone size={18} /> : <Mail size={18} />}
                Send code
              </button>
              {waitingForChallenge ? <p className="small faint">Checking that you are human…</p> : null}
              <p className="small faint">
                {tab === "signup" ? "By creating an account" : "By continuing"} you agree to receive a one-time verification {kind === "phone" ? "SMS" : "email"}.
              </p>
              {googleOn ? (
                <>
                  <div className="or-divider" aria-hidden="true">or</div>
                  <GoogleButton key={tab} text={tab === "signin" ? "signin_with" : "signup_with"} onCredential={(c) => void onGoogleCredential(c)} />
                </>
              ) : null}
            </form>
          ) : (
            <form className="stack" onSubmit={verify} noValidate>
              <button
                type="button"
                className="back back-btn"
                onClick={() => {
                  setStep("form");
                  setCodeLocked(false);
                  setError(null);
                  setInfo(null);
                }}
              >
                <ArrowLeft size={16} /> Change {sentTo}
              </button>
              <div className="stack stack-s">
                <h1>{tab === "signup" ? "Almost there" : "Enter the code"}</h1>
                <p className="muted">
                  Sent to <span className="strong num">{identifier?.value}</span>
                </p>
              </div>
              {info ? <Banner tone="info">{info}</Banner> : null}
              {error ? <Banner tone="danger">{error}</Banner> : null}

              {askNameOnCodeStep ? (
                <Field label={copy.name.label} hint={copy.name.hint} error={nameError}>
                  {(id) => (
                    <input
                      id={id}
                      ref={nameRef}
                      className="input"
                      autoComplete="name"
                      maxLength={100}
                      placeholder={copy.name.placeholder}
                      value={name}
                      aria-invalid={nameError ? true : undefined}
                      onChange={(e) => setName(e.target.value)}
                    />
                  )}
                </Field>
              ) : null}

              {codeLocked ? null : (
                <Field label="6-digit code">
                  {(id) => (
                    <input
                      id={id}
                      ref={codeRef}
                      className="input otp"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={6}
                      placeholder="······"
                      value={code}
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                    />
                  )}
                </Field>
              )}
              <button className="btn btn-primary btn-block" disabled={busy || code.length !== 6}>
                {busy ? <Spinner /> : <Leaf size={18} />}
                {copyTab.cta}
              </button>
              <BotChallenge onToken={setChallengeToken} resetKey={challengeKey} />
              <button
                type="button"
                className="btn btn-ghost btn-block"
                disabled={busy || cooldown > 0 || waitingForChallenge}
                onClick={() => void send()}
              >
                {cooldown > 0 ? `Resend code in ${cooldown}s` : "Resend code"}
              </button>
              {identifier?.type === "email" ? <p className="small faint">Can&apos;t find it? Check your spam folder.</p> : null}
              {process.env.NODE_ENV !== "production" ? <p className="small faint">Local dev: the code is printed in the backend terminal.</p> : null}
            </form>
          )}

          {existing ? (
            <button type="button" className="btn btn-ghost btn-block" onClick={() => void logout()}>
              Not you? Sign out
            </button>
          ) : null}

          <ApiStatus />
        </div>
      </main>
    </div>
  );
}
