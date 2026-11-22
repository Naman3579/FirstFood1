"use client";

import { useEffect, useRef, useState } from "react";
import { env } from "@/lib/env";

/**
 * Cloudflare Turnstile widget for the PUBLIC OTP request (F-05). It hands the parent a one-time token, which is sent
 * as the X-Challenge-Token header and checked server-side; without a valid token the backend sends no SMS.
 *
 * - Renders nothing when no site key is configured (NEXT_PUBLIC_TURNSTILE_SITE_KEY), matching the backend's challenge
 *   being off.
 * - A token works ONCE. Bump `resetKey` after every request (success or failure) to get a fresh one; until then the
 *   parent receives null and should keep its send button disabled.
 * - The script is loaded on demand from challenges.cloudflare.com, which the CSP allows only when the site key is set
 *   (next.config.js).
 */

type TurnstileApi = {
  render: (
    element: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      "expired-callback": () => void;
      "error-callback": () => void;
    },
  ) => string;
  reset: (widgetId: string) => void;
  remove: (widgetId: string) => void;
};

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

const SCRIPT_SRC = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
let scriptPromise: Promise<TurnstileApi> | null = null;

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  if (!scriptPromise) {
    scriptPromise = new Promise<TurnstileApi>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = SCRIPT_SRC;
      script.async = true;
      script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error("Turnstile unavailable")));
      script.onerror = () => reject(new Error("Turnstile failed to load"));
      document.head.appendChild(script);
    }).catch((err) => {
      scriptPromise = null; // let a later mount try again
      throw err;
    });
  }
  return scriptPromise;
}

export function BotChallenge({ onToken, resetKey }: { onToken: (token: string | null) => void; resetKey: number }) {
  const siteKey = env.turnstileSiteKey;
  const container = useRef<HTMLDivElement>(null);
  const widgetId = useRef<string | null>(null);
  const onTokenRef = useRef(onToken);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    onTokenRef.current = onToken;
  }, [onToken]);

  useEffect(() => {
    if (!siteKey) return;
    let cancelled = false;
    let api: TurnstileApi | null = null;
    loadTurnstile()
      .then((turnstile) => {
        if (cancelled || !container.current) return;
        api = turnstile;
        widgetId.current = turnstile.render(container.current, {
          sitekey: siteKey,
          callback: (token) => onTokenRef.current(token),
          "expired-callback": () => onTokenRef.current(null),
          "error-callback": () => onTokenRef.current(null),
        });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (api && widgetId.current) api.remove(widgetId.current);
      widgetId.current = null;
    };
  }, [siteKey]);

  useEffect(() => {
    // resetKey changes after each request: the used token is dead, ask Cloudflare for a new one.
    if (widgetId.current && window.turnstile) window.turnstile.reset(widgetId.current);
  }, [resetKey]);

  if (!siteKey) return null;
  return (
    <div>
      <div ref={container} />
      {failed ? (
        <p className="small faint">Could not load the human check. Disable content blockers for this site and reload.</p>
      ) : null}
    </div>
  );
}
