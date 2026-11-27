"use client";

import type { ReactNode } from "react";
import { PortalGate } from "@/components/auth/PortalGate";
import { CustomerShell } from "@/components/shell/CustomerShell";

/** Everything for customers: /home, /memberships, /account, /subscriptions/... behind the customer sign-in. */
export default function CustomerLayout({ children }: { children: ReactNode }) {
  return (
    <PortalGate portal="customer">
      <CustomerShell>{children}</CustomerShell>
    </PortalGate>
  );
}
