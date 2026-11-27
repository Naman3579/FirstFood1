"use client";

import type { ReactNode } from "react";
import { Spinner } from "@/components/ui";
import { AccountProvider } from "@/components/shell/account";
import { hasPortal, type Identifier, type Portal, type Profile } from "@/lib/auth-api";
import { env } from "@/lib/env";
import { useSession } from "@/lib/session";
import { PORTAL_COPY } from "./portal-copy";
import { PortalAuth } from "./PortalAuth";

/** Where the fresh code for the missing portal goes: the number if texts are on and there is one, else the verified email. */
function loginIdentifier(profile: Profile): Identifier {
  const email = profile.emailVerified && profile.email ? profile.email : null;
  if (profile.phone && (env.phoneOtpEnabled || !email)) return { type: "phone", value: profile.phone };
  return { type: "email", value: email ?? "" };
}

/**
 * The door for one portal. Signed out -> that portal's sign-in / create-account screen, rendered in place (so a deep
 * link like /staff/<id>/plans signs in and then simply continues, no redirect dance). Signed in but never registered
 * on this portal -> the same screen with the number and name filled in. Otherwise the app.
 *
 * This is UX only. The backend answers 403 PORTAL_ACCESS_DENIED to every request from an account that is not
 * registered for the portal an endpoint belongs to, whatever the screen shows.
 */
export function PortalGate({ portal, children }: { portal: Portal; children: ReactNode }) {
  const { status, profile } = useSession();

  if (status === "loading") {
    return (
      <div style={{ minHeight: "100dvh", display: "grid", placeItems: "center", color: "var(--muted)" }}>
        <Spinner />
      </div>
    );
  }
  if (status !== "authed" || !profile) return <PortalAuth portal={portal} />;
  if (!hasPortal(profile, portal)) {
    return <PortalAuth portal={portal} existing={{ identifier: loginIdentifier(profile), fullName: profile.fullName }} notice={PORTAL_COPY[portal].addPortal} />;
  }
  return <AccountProvider portal={portal}>{children}</AccountProvider>;
}
