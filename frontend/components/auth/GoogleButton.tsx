"use client";

import { useEffect, useRef, useState } from "react";
import { env } from "@/lib/env";

/**
 * Google's own "Sign in with Google" button (Google Identity Services). Google renders the button and runs the popup;
 * when the person picks an account it hands us an ID token ("credential"), which the backend verifies. We never see a
 * password and never hold a Google access token.
 *
 * - Renders nothing when no client id is configured (NEXT_PUBLIC_GOOGLE_CLIENT_ID), matching the backend being off.
 * - The script loads on demand from accounts.google.com, which the CSP allows only when the client id is set
 *   (next.config.js).
 * - `text` picks the label Google draws; change it (or remount with a new key) when the screen switches tab.
 */

type GoogleId = {
  initialize: (config: {
    client_id: string;
    callback: (response: { credential?: string }) => void;
    ux_mode?: "popup" | "redirect";
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
  }) => void;
  renderButton: (
    element: HTMLElement,
    options: {
      type?: "standard" | "icon";
      theme?: "outline" | "filled_blue" | "filled_black";
      size?: "large" | "medium" | "small";
      text?: "signin_with" | "signup_with" | "continue_with" | "signin";
      shape?: "rectangular" | "pill";
      logo_alignment?: "left" | "center";
      width?: number;
    },
  ) => void;
  disableAutoSelect: () => void;
};

declare global {
  interface Window {
    google?: { accounts: { id: GoogleId } };
  }
}

const SCRIPT_SRC = "https://accounts.google.com/gsi/client";
let scriptPromise: Promise<GoogleId> | null = null;

function loadGoogle(): Promise<GoogleId> {
  if (window.google?.accounts?.id) return Promise.resolve(window.google.accounts.id);
  if (!scriptPromise) {
    scriptPromise = new Promise<GoogleId>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = SCRIPT_SRC;
      script.async = true;
      script.defer = true;
      script.onload = () =>
        window.google?.accounts?.id ? resolve(window.google.accounts.id) : reject(new Error("Google sign-in unavailable"));
      script.onerror = () => reject(new Error("Google sign-in failed to load"));
      document.head.appendChild(script);
    }).catch((err) => {
      scriptPromise = null; // let a later mount try again
      throw err;
    });
  }
  return scriptPromise;
}

/** Forget the "last used Google account" so the next visitor on a shared device is not auto-signed in. */
export function googleDisableAutoSelect(): void {
  try {
    window.google?.accounts?.id?.disableAutoSelect();
  } catch {
    /* nothing to do */
  }
}

export function GoogleButton({
  onCredential,
  text = "continue_with",
}: {
  onCredential: (credential: string) => void;
  text?: "signin_with" | "signup_with" | "continue_with";
}) {
  const clientId = env.googleClientId;
  const container = useRef<HTMLDivElement>(null);
  const onCredentialRef = useRef(onCredential);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    onCredentialRef.current = onCredential;
  }, [onCredential]);

  useEffect(() => {
    if (!clientId) return;
    let cancelled = false;
    loadGoogle()
      .then((gsi) => {
        const el = container.current;
        if (cancelled || !el) return;
        gsi.initialize({
          client_id: clientId,
          callback: (response) => {
            if (response.credential) onCredentialRef.current(response.credential);
          },
          ux_mode: "popup",
          auto_select: false,
          cancel_on_tap_outside: true,
        });
        el.replaceChildren();
        // Google only accepts a pixel width (200-400); fit the card, up to the maximum.
        const width = Math.max(200, Math.min(400, Math.floor(el.clientWidth || 320)));
        gsi.renderButton(el, { type: "standard", theme: "outline", size: "large", text, shape: "rectangular", logo_alignment: "left", width });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [clientId, text]);

  if (!clientId) return null;
  return (
    <div className="google-btn">
      <div ref={container} className="google-btn-slot" />
      {failed ? (
        <p className="small faint">Could not load Google sign-in. Disable content blockers for this site and reload, or use your email.</p>
      ) : null}
    </div>
  );
}
