import { Badge } from "@/components/ui";
import { describePolicyFromTerms } from "@/lib/policy";
import type { SubscriptionTerms, Verification } from "@/lib/subscription-api";

/** Shown only while something is left to say: awaiting staff, refused, or lapsed. A confirmed subscription shows nothing. */
export function VerificationBadge({ verification }: { verification: Verification | null }) {
  if (!verification || verification.status === "VERIFIED") return null;
  if (verification.status === "PENDING") return <Badge tone="warn">Awaiting confirmation</Badge>;
  if (verification.status === "EXPIRED") return <Badge tone="neutral">Not confirmed in time</Badge>;
  return <Badge tone="danger">Rejected</Badge>;
}

export function TermsList({ terms }: { terms: SubscriptionTerms }) {
  return (
    <ul className="stack stack-s" style={{ margin: 0, paddingLeft: "1.1rem" }}>
      {describePolicyFromTerms(terms).map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  );
}

export function Progress({ value, max, label }: { value: number; max: number; label: string }) {
  const pct = max > 0 ? Math.min(100, Math.max(0, Math.round((value / max) * 100))) : 0;
  return (
    <div className="progress" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
      <span style={{ width: `${pct}%` }} />
    </div>
  );
}
