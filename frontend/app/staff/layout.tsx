"use client";

import type { ReactNode } from "react";
import { PortalGate } from "@/components/auth/PortalGate";

/**
 * Everything under /staff sits behind the staff sign-in. Signed out, /staff itself shows the staff sign-in / create
 * account screen; a deep link such as /staff/<id>/plans shows it too and continues to that page after signing in.
 */
export default function StaffLayout({ children }: { children: ReactNode }) {
  return <PortalGate portal="staff">{children}</PortalGate>;
}
