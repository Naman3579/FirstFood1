"use client";

import { createContext, useContext, type ReactNode } from "react";
import { useQuery, type Query } from "@/lib/hooks";
import { providerApi, type Provider } from "@/lib/provider-api";
import { membershipApi, type Person } from "@/lib/membership-api";
import type { Portal } from "@/lib/auth-api";

/**
 * Data the signed-in screens of ONE portal share. Each portal only asks for its own endpoints - a customer-only account
 * would get 403 PORTAL_ACCESS_DENIED from the staff API, and the other way round:
 *  - customer portal: the account's own person record (the name shown to providers);
 *  - staff portal:    the providers this account holds a role on.
 */
type AccountValue = {
  providers: Query<Provider[]>;
  person: Query<Person | null>;
};

const AccountContext = createContext<AccountValue | null>(null);

export function AccountProvider({ portal, children }: { portal: Portal; children: ReactNode }) {
  const providers = useQuery((t) => providerApi.list(t), [], { enabled: portal === "staff" });
  const person = useQuery<Person | null>(
    (t) =>
      membershipApi.getMyPerson(t).catch((err: { status?: number }) => {
        if (err?.status === 404) return null; // no person yet: the account page offers to set one
        throw err;
      }),
    [],
    { enabled: portal === "customer" },
  );
  return <AccountContext.Provider value={{ providers, person }}>{children}</AccountContext.Provider>;
}

export function useAccount(): AccountValue {
  const ctx = useContext(AccountContext);
  if (!ctx) throw new Error("useAccount must be used inside <PortalGate>");
  return ctx;
}
