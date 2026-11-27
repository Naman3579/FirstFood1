"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { PortalGate } from "@/components/auth/PortalGate";
import { Spinner } from "@/components/ui";

/** Customer portal entry. Signed out, this IS the customer sign-in / create-account screen; signed in, it hands over to the app. */
function GoHome() {
  const router = useRouter();
  useEffect(() => router.replace("/home"), [router]);
  return (
    <div style={{ minHeight: "100dvh", display: "grid", placeItems: "center", color: "var(--muted)" }}>
      <Spinner />
    </div>
  );
}

export default function CustomerPortal() {
  return (
    <PortalGate portal="customer">
      <GoHome />
    </PortalGate>
  );
}
