"use client";

import Link from "next/link";
import { useEffect, useId, useRef, type ReactNode, type InputHTMLAttributes } from "react";
import { AlertCircle, ArrowLeft, Check, ChevronLeft, ChevronRight, Info, Leaf, Search, X } from "lucide-react";
import { initials } from "@/lib/format";
import type { Query } from "@/lib/hooks";

/* ---------- small atoms ---------- */

export function Spinner() {
  return <span className="spinner" role="progressbar" aria-label="Loading" />;
}

export function Brand({ href = "/" }: { href?: string }) {
  return (
    <Link href={href} className="brand">
      <span className="brand-mark">
        <Leaf size={18} />
      </span>
      FirstFood
    </Link>
  );
}

export function Avatar({ name }: { name: string }) {
  return (
    <span className="avatar" aria-hidden="true">
      {initials(name)}
    </span>
  );
}

export type Tone = "ok" | "warn" | "danger" | "info" | "accent" | "neutral";

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge ${tone === "neutral" ? "" : `tone-${tone}`}`}>{children}</span>;
}

export function Banner({ tone = "info", children, action }: { tone?: "info" | "danger" | "warn" | "ok"; children: ReactNode; action?: ReactNode }) {
  const cls = tone === "info" ? "banner" : `banner banner-${tone}`;
  return (
    <div className={cls} role={tone === "danger" ? "alert" : undefined}>
      {tone === "ok" ? <Check size={18} /> : tone === "info" ? <Info size={18} /> : <AlertCircle size={18} />}
      <div className="grow">{children}</div>
      {action}
    </div>
  );
}

export function StatTile({ label, value, sub, icon }: { label: string; value: ReactNode; sub?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">
        {icon}
        {label}
      </div>
      <div className="stat-value">{value}</div>
      {sub ? <div className="stat-sub">{sub}</div> : null}
    </div>
  );
}

export function Empty({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      {icon ? <div className="empty-icon">{icon}</div> : null}
      <h3>{title}</h3>
      {children ? <p style={{ maxWidth: "36ch" }}>{children}</p> : null}
      {action}
    </div>
  );
}

export function Skeleton({ height = "1rem", width = "100%" }: { height?: string; width?: string }) {
  return <div className="skeleton" style={{ height, width }} />;
}

export function ListSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height="4rem" />
      ))}
    </div>
  );
}

/** Show a skeleton while loading, an error banner with retry on failure, otherwise the children. */
export function Boundary<T>({
  query,
  children,
  rows,
  skeleton,
}: {
  query: Query<T>;
  children: (data: T) => ReactNode;
  rows?: number;
  skeleton?: ReactNode;
}) {
  if (query.data === undefined) {
    if (query.error) {
      return (
        <Banner
          tone="danger"
          action={
            <button className="btn btn-sm" onClick={() => void query.reload()}>
              Retry
            </button>
          }
        >
          {query.error}
        </Banner>
      );
    }
    return <>{skeleton ?? <ListSkeleton rows={rows} />}</>;
  }
  return <>{children(query.data)}</>;
}

export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="back">
      <ArrowLeft size={16} />
      {children}
    </Link>
  );
}

export function PageHeader({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div style={{ minWidth: 0 }}>
        <h1>{title}</h1>
        {sub ? <p className="sub">{sub}</p> : null}
      </div>
      {actions ? <div className="row">{actions}</div> : null}
    </div>
  );
}

/* ---------- form bits ---------- */

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: ReactNode;
  error?: string | null;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
      {error ? (
        <span className="hint" style={{ color: "var(--danger)" }} role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="hint">{hint}</span>
      ) : null}
    </div>
  );
}

export function SearchBox(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className="search">
      <Search size={18} />
      <input className="input" type="search" {...props} />
    </div>
  );
}

export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string }[];
  label: string;
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={value === o.value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Pager({ label, onPrev, onNext }: { label: string; onPrev: () => void; onNext: () => void }) {
  return (
    <div className="row" style={{ gap: "0.25rem" }}>
      <button className="btn btn-icon btn-sm" onClick={onPrev} aria-label="Previous">
        <ChevronLeft size={18} />
      </button>
      <span className="strong num" style={{ minWidth: "9rem", textAlign: "center" }}>
        {label}
      </span>
      <button className="btn btn-icon btn-sm" onClick={onNext} aria-label="Next">
        <ChevronRight size={18} />
      </button>
    </div>
  );
}

/* ---------- modal ---------- */

export function Modal({
  title,
  onClose,
  children,
  wide,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const first = ref.current?.querySelector<HTMLElement>("input, select, textarea, button.btn-primary, button.btn-danger");
    first?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      if (e.key === "Tab" && ref.current) {
        const focusables = ref.current.querySelectorAll<HTMLElement>('a[href],button:not([disabled]),input,select,textarea,[tabindex]:not([tabindex="-1"])');
        if (focusables.length === 0) return;
        const a = focusables[0]!;
        const z = focusables[focusables.length - 1]!;
        if (e.shiftKey && document.activeElement === a) {
          e.preventDefault();
          z.focus();
        } else if (!e.shiftKey && document.activeElement === z) {
          e.preventDefault();
          a.focus();
        }
      }
    }
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      previous?.focus?.();
    };
  }, []);

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? "modal-wide" : ""}`} role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref}>
        <div className="modal-head">
          <h2 id={titleId}>{title}</h2>
          <button className="btn btn-ghost btn-icon btn-sm" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  danger,
  busy,
  onConfirm,
  onClose,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Modal title={title} onClose={onClose}>
      <div className="stack">{children}</div>
      <div className="modal-actions">
        <button className="btn" onClick={onClose} disabled={busy}>
          Not now
        </button>
        <button className={`btn ${danger ? "btn-danger" : "btn-primary"}`} onClick={onConfirm} disabled={busy}>
          {busy ? <Spinner /> : null}
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
