"use client";

import { AccountSettings } from "@/components/domain/AccountSettings";

export default function AccountPage() {
  return (
    <main className="page" style={{ maxWidth: "40rem" }}>
      <h1>Account</h1>
      <AccountSettings portal="customer" />
    </main>
  );
}
