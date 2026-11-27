"use client";

import { BackLink } from "@/components/ui";
import { AccountSettings } from "@/components/domain/AccountSettings";
import { StaffTopbar } from "@/components/shell/StaffTopbar";

export default function StaffAccountPage() {
  return (
    <>
      <StaffTopbar />
      <main className="page" style={{ maxWidth: "40rem" }}>
        <BackLink href="/staff">Your providers</BackLink>
        <h1>Account</h1>
        <AccountSettings portal="staff" />
      </main>
    </>
  );
}
