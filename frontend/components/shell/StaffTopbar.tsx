"use client";

import Link from "next/link";
import { LogOut } from "lucide-react";
import { Avatar, Brand } from "@/components/ui";
import { useSession } from "@/lib/session";

/** Header for the staff pages that sit outside a single provider's workspace (provider list, account). */
export function StaffTopbar() {
  const { logout, profile } = useSession();
  return (
    <header className="topbar">
      <Brand href="/staff" />
      <span className="portal-chip portal-chip-sm hide-sm">Staff portal</span>
      <span className="grow" />
      <Link href="/staff/account" aria-label="Your account" style={{ display: "inline-flex" }}>
        <Avatar name={profile?.fullName ?? profile?.phone ?? profile?.email ?? "?"} />
      </Link>
      <button className="btn btn-ghost btn-sm btn-icon" onClick={() => void logout()} aria-label="Sign out">
        <LogOut size={18} />
      </button>
    </header>
  );
}
