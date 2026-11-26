import { Badge, type Tone } from "@/components/ui";
import type { SubscriptionPhase } from "@/lib/subscription-api";
import type { ProviderStatus } from "@/lib/provider-api";
import type { MembershipStatus } from "@/lib/membership-api";
import type { PlanStatus } from "@/lib/plan-api";
import { titleCase } from "@/lib/format";

const PHASE_TONE: Record<SubscriptionPhase, Tone> = { RUNNING: "ok", UPCOMING: "info", ENDED: "neutral", CANCELLED: "danger" };
const PHASE_LABEL: Record<SubscriptionPhase, string> = { RUNNING: "Running", UPCOMING: "Upcoming", ENDED: "Ended", CANCELLED: "Cancelled" };

export function PhaseBadge({ phase }: { phase: SubscriptionPhase }) {
  return <Badge tone={PHASE_TONE[phase]}>{PHASE_LABEL[phase]}</Badge>;
}

const PROVIDER_TONE: Record<ProviderStatus, Tone> = { ACTIVE: "ok", FULL: "warn", TEMPORARILY_UNAVAILABLE: "warn", CLOSED: "danger" };
export function ProviderStatusBadge({ status }: { status: ProviderStatus }) {
  return <Badge tone={PROVIDER_TONE[status]}>{status === "TEMPORARILY_UNAVAILABLE" ? "Paused" : titleCase(status)}</Badge>;
}

export function MembershipBadge({ status }: { status: MembershipStatus }) {
  return <Badge tone={status === "ACTIVE" ? "ok" : "neutral"}>{titleCase(status)}</Badge>;
}

export function PlanStatusBadge({ status }: { status: PlanStatus }) {
  return <Badge tone={status === "ACTIVE" ? "ok" : "neutral"}>{titleCase(status)}</Badge>;
}

export const PROVIDER_STATUS_LABEL: Record<ProviderStatus, string> = {
  ACTIVE: "Open",
  FULL: "Full",
  TEMPORARILY_UNAVAILABLE: "Paused",
  CLOSED: "Closed",
};

export const FOOD_TYPE_LABEL: Record<string, string> = {
  VEG: "Veg",
  NON_VEG: "Non-veg",
  VEG_AND_NON_VEG: "Veg & non-veg",
  JAIN: "Jain",
  VEGAN: "Vegan",
};

/** "Mess · Tiffin service" - for a provider that is several kinds of business. */
export function providerTypesText(types: readonly string[] | undefined): string {
  return (types ?? []).map((t) => PROVIDER_TYPE_LABEL[t] ?? t).join(" · ");
}

export const PROVIDER_TYPE_LABEL: Record<string, string> = {
  MESS: "Mess",
  TIFFIN: "Tiffin service",
  PG: "PG",
  HOSTEL: "Hostel",
  CORPORATE: "Corporate canteen",
  OTHER: "Other",
};
