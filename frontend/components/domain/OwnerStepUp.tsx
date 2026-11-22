"use client";

import { useEffect, useState } from "react";
import { MessageSquare } from "lucide-react";
import { Field, Spinner } from "@/components/ui";
import { useAction } from "@/lib/hooks";
import { roleApi } from "@/lib/role-api";

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
      { success: "Code sent to your phone." },
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
        hint="For your safety, we text a code to your own phone before this can happen."
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
              {busy ? <Spinner /> : <MessageSquare size={16} />}{" "}
              {wait > 0 ? `Resend in ${wait}s` : sent ? "Resend code" : "Send code"}
            </button>
          </div>
        )}
      </Field>
    </div>
  );
}
