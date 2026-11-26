"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Compass, Home, Store, UserRound } from "lucide-react";
import { Avatar, Brand } from "@/components/ui";
import { useAccount } from "./account";
import { useSession } from "@/lib/session";

const ITEMS = [
  { href: "/home", label: "Home", icon: Home, match: (p: string) => p === "/home" || p.startsWith("/subscriptions") },
  { href: "/discover", label: "Discover", icon: Compass, match: (p: string) => p.startsWith("/discover") },
  { href: "/memberships", label: "My messes", icon: Store, match: (p: string) => p.startsWith("/memberships") },
  { href: "/account", label: "Account", icon: UserRound, match: (p: string) => p.startsWith("/account") },
];

/** Customer side. Phones get a bottom tab bar (thumb reach); wider screens get the same links in the top bar. */
export function CustomerShell({ children, withTabs = true }: { children: ReactNode; withTabs?: boolean }) {
  const pathname = usePathname();
  const { person } = useAccount();
  const { profile } = useSession();

  return (
    <div className={withTabs ? "with-tabbar" : undefined}>
      <header className="topbar">
        <Brand href="/home" />
        <nav className="topnav" aria-label="Main">
          {ITEMS.map(({ href, label, icon: Icon, match }) => (
            <Link key={href} href={href} aria-current={match(pathname) ? "page" : undefined}>
              <Icon size={17} />
              {label}
            </Link>
          ))}
        </nav>
        <span className="grow" />
        <Link href="/account" aria-label="Your account" style={{ display: "inline-flex" }}>
          <Avatar name={person.data?.fullName ?? profile?.fullName ?? profile?.phone ?? profile?.email ?? "?"} />
        </Link>
      </header>
      {children}
      {withTabs ? (
        <nav className="tabbar" aria-label="Main">
          {ITEMS.map(({ href, label, icon: Icon, match }) => (
            <Link key={href} href={href} aria-current={match(pathname) ? "page" : undefined}>
              <Icon size={22} />
              {label}
            </Link>
          ))}
        </nav>
      ) : null}
    </div>
  );
}

