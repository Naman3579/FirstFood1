"use client";

import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import {
  CalendarCheck2,
  CalendarOff,
  ClipboardList,
  LayoutDashboard,
  LogOut,
  Menu,
  Settings,
  ShieldCheck,
  Users,
  UtensilsCrossed,
  X,
  ArrowLeftRight,
  UserCog,
} from "lucide-react";
import { Avatar, Banner, Brand, ListSkeleton } from "@/components/ui";
import { ProviderStatusBadge } from "@/components/domain/status";
import { useQuery, type Query } from "@/lib/hooks";
import { can, providerApi, type Provider, type ProviderPermission } from "@/lib/provider-api";
import { useSession } from "@/lib/session";
import { useAccount } from "./account";

type ProviderCtx = {
  provider: Provider;
  providerId: string;
  can: (p: ProviderPermission) => boolean;
  reload: () => Promise<void>;
  setProvider: (p: Provider) => void;
};

const Ctx = createContext<ProviderCtx | null>(null);

export function useProvider(): ProviderCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useProvider must be used inside <ProviderShell>");
  return ctx;
}

const NAV: { slug: string; label: string; icon: typeof Users; need: ProviderPermission }[] = [
  { slug: "", label: "Dashboard", icon: LayoutDashboard, need: "PROVIDER_VIEW" },
  { slug: "attendance", label: "Attendance", icon: CalendarCheck2, need: "ATTENDANCE_VIEW" },
  { slug: "subscriptions", label: "Subscriptions", icon: ClipboardList, need: "SUBSCRIPTION_VIEW" },
  { slug: "closures", label: "Closures", icon: CalendarOff, need: "SUBSCRIPTION_VIEW" },
  { slug: "customers", label: "Customers", icon: Users, need: "MEMBERSHIP_VIEW" },
  { slug: "plans", label: "Plans", icon: UtensilsCrossed, need: "PLAN_VIEW" },
  { slug: "team", label: "Team", icon: ShieldCheck, need: "ROLE_VIEW" },
  { slug: "settings", label: "Settings", icon: Settings, need: "PROVIDER_VIEW" },
];

/**
 * Staff workspace for ONE provider: left sidebar on desktop, slide-in drawer on phones. Nav entries and every action
 * inside the pages are filtered by the permission set the server returned for this account (provider.myPermissions) -
 * the backend still enforces them; this only hides what would be refused.
 */
export function ProviderShell({ children }: { children: ReactNode }) {
  const { providerId } = useParams<{ providerId: string }>();
  const pathname = usePathname();
  const { logout, profile } = useSession();
  const { providers } = useAccount();
  const [open, setOpen] = useState(false);
  const query: Query<Provider> = useQuery((t) => providerApi.get(t, providerId), [providerId]);

  useEffect(() => setOpen(false), [pathname]);

  if (query.data === undefined) {
    return (
      <div className="page">
        {query.error ? (
          <Banner tone="danger" action={<Link className="btn btn-sm" href="/staff">All providers</Link>}>
            {query.error}
          </Banner>
        ) : (
          <ListSkeleton rows={3} />
        )}
      </div>
    );
  }

  const provider = query.data;
  const base = `/staff/${providerId}`;
  const value: ProviderCtx = {
    provider,
    providerId,
    can: (p) => can(provider, p),
    reload: query.reload,
    setProvider: query.setData,
  };
  const multiple = (providers.data?.length ?? 0) > 1;

  return (
    <Ctx.Provider value={value}>
      <div className="staff">
        {open ? <div className="scrim" onClick={() => setOpen(false)} /> : null}
        <aside className="sidebar" data-open={open} aria-label="Staff navigation">
          <div className="sidebar-head">
            <Brand href="/staff" />
            <button className="btn btn-ghost btn-icon btn-sm menu-btn" onClick={() => setOpen(false)} aria-label="Close menu">
              <X size={18} />
            </button>
          </div>
          <Link href="/staff" className="provider-chip" title={multiple ? "Switch provider" : undefined}>
            <Avatar name={provider.name} />
            <span style={{ minWidth: 0, flex: 1 }}>
              <span className="strong truncate" style={{ display: "block" }}>
                {provider.name}
              </span>
              <span className="small muted">{provider.myRole.charAt(0) + provider.myRole.slice(1).toLowerCase()}</span>
            </span>
            {multiple ? <ArrowLeftRight size={16} className="muted" /> : null}
          </Link>
          <nav className="nav-list" aria-label="Provider">
            {NAV.filter((n) => value.can(n.need)).map(({ slug, label, icon: Icon }) => {
              const href = slug ? `${base}/${slug}` : base;
              const active = slug ? pathname.startsWith(href) : pathname === base;
              return (
                <Link key={label} href={href} aria-current={active ? "page" : undefined}>
                  <Icon size={19} />
                  {label}
                </Link>
              );
            })}
          </nav>
          <div className="sidebar-foot">
            <Link href="/staff/account" className="btn btn-ghost btn-block" style={{ justifyContent: "flex-start" }}>
              <UserCog size={18} />
              Account
            </Link>
            <button className="btn btn-ghost btn-block" style={{ justifyContent: "flex-start" }} onClick={() => void logout()}>
              <LogOut size={18} />
              Sign out
            </button>
            <span className="small faint" style={{ padding: "0.25rem 0.75rem" }}>
              {profile?.fullName ? `${profile.fullName} · ` : ""}{profile?.phone ?? profile?.email}
            </span>
          </div>
        </aside>

        <div className="staff-main with-tabbar">
          <header className="topbar staff-topbar">
            <button className="btn btn-ghost btn-icon menu-btn" onClick={() => setOpen(true)} aria-label="Open menu">
              <Menu size={22} />
            </button>
            <span className="strong truncate">{provider.name}</span>
            <ProviderStatusBadge status={provider.status} />
            <span className="grow" />
          </header>
          {provider.status === "CLOSED" ? (
            <div className="page" style={{ paddingBottom: 0 }}>
              <Banner tone="warn">This provider is closed. Records stay readable but nothing can be changed.</Banner>
            </div>
          ) : null}
          {children}
        </div>

        <nav className="tabbar tabbar-staff" aria-label="Quick navigation">
          {NAV.slice(0, 4)
            .filter((n) => value.can(n.need))
            .map(({ slug, label, icon: Icon }) => {
              const href = slug ? `${base}/${slug}` : base;
              const active = slug ? pathname.startsWith(href) : pathname === base;
              return (
                <Link key={label} href={href} aria-current={active ? "page" : undefined}>
                  <Icon size={22} />
                  {label}
                </Link>
              );
            })}
          <button type="button" onClick={() => setOpen(true)} aria-label="More: plans, team and settings">
            <Menu size={22} />
            More
          </button>
        </nav>
      </div>
    </Ctx.Provider>
  );
}
