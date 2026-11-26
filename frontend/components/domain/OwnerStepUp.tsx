"use client";

import { useEffect, useState } from "react";
<<<<<<< HEAD
import { Mail, MessageSquare } from "lucide-react";
import { Field, Spinner } from "@/components/ui";
import { useAction } from "@/lib/hooks";
import { roleApi } from "@/lib/role-api";
import { useSession } from "@/lib/session";
import { env } from "@/lib/env";
=======
import { MessageSquare } from "lucide-react";
import { Field, Spinner } from "@/components/ui";
import { useAction } from "@/lib/hooks";
import { roleApi } from "@/lib/role-api";
>>>>>>> origin/main

const RESEND_SECONDS = 30;

/**
 * "Prove it's you" for owner-level actions (F-08): texts a fresh code to the owner's own phone and collects it.
 * Sending the code and acting on it are separate calls; the parent keeps the code and sends it with the action.
 */
export function OwnerStepUp({
  providerId,
  code,
  onCode,
  error,
}: {
  providerId: string;
  code: string;
  onCode: (code: string) => void;
  error?: string | null;
}) {
  const { run, busy } = useAction();
<<<<<<< HEAD
  const { profile } = useSession();
  // Mirrors the backend rule (StepUpServiceImpl): while SMS is off the code goes to the verified email.
  const viaEmail = !!profile?.emailVerified && (!env.phoneOtpEnabled || !profile?.phoneVerified);
  const where = viaEmail ? "email" : "phone";
=======
>>>>>>> origin/main
  const [sent, setSent] = useState(false);
  // Every code costs the owner's hourly OTP budget, so a quick double click must not send two.
  const [wait, setWait] = useState(0);

  useEffect(() => {
    if (wait <= 0) return;
    const timer = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(timer);
  }, [wait]);

  async function send() {
    // The endpoint answers 204 (no body), so return a marker: run() gives undefined only when the call failed.
    const ok = await run(
      async (t) => {
        await roleApi.requestStepUp(t, providerId);
        return true;
      },
<<<<<<< HEAD
      { success: `Code sent to your ${where}.` },
=======
      { success: "Code sent to your phone." },
>>>>>>> origin/main
    );
    if (ok) {
      setSent(true);
      setWait(RESEND_SECONDS);
    }
  }

  return (
    <div className="stack" style={{ gap: "0.5rem" }}>
      <Field
        label="Verification code"
        error={error}
<<<<<<< HEAD
        hint={`For your safety, we ${viaEmail ? "email" : "text"} a code to your own ${where} before this can happen.`}
=======
        hint="For your safety, we text a code to your own phone before this can happen."
>>>>>>> origin/main
      >
        {(id) => (
          <div className="row" style={{ gap: "0.5rem" }}>
            <input
              id={id}
              className="input otp"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="······"
              value={code}
              aria-invalid={error ? true : undefined}
              onChange={(e) => onCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
            />
            <button type="button" className="btn" disabled={busy || wait > 0} onClick={send}>
<<<<<<< HEAD
              {busy ? <Spinner /> : viaEmail ? <Mail size={16} /> : <MessageSquare size={16} />}{" "}
=======
              {busy ? <Spinner /> : <MessageSquare size={16} />}{" "}
>>>>>>> origin/main
              {wait > 0 ? `Resend in ${wait}s` : sent ? "Resend code" : "Send code"}
            </button>
          </div>
        )}
      </Field>
    </div>
  );
}
