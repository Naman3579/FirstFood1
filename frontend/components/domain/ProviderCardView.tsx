import Link from "next/link";
import { ChevronRight, MapPin, Store } from "lucide-react";
import { Badge } from "@/components/ui";
import { FOOD_TYPE_LABEL, PROVIDER_STATUS_LABEL, PROVIDER_TYPE_LABEL, providerTypesText } from "./status";
import { formatDistance, type ProviderCard } from "@/lib/discovery-api";
import { formatMoney } from "@/lib/format";

/** "₹2,500" or "₹2,500 – ₹3,500"; empty when the provider has no active plan. */
export function priceRange(p: Pick<ProviderCard, "minPrice" | "maxPrice" | "currency">): string {
  if (p.minPrice === null) return "";
  if (p.maxPrice === null || p.maxPrice === p.minPrice) return formatMoney(p.minPrice, p.currency);
  return `${formatMoney(p.minPrice, p.currency)} – ${formatMoney(p.maxPrice, p.currency)}`;
}

/** Why a provider cannot take a new customer right now, in customer words. */
export function unavailableText(p: Pick<ProviderCard, "status" | "acceptingNewCustomers">): string {
  if (p.status === "FULL") return "Full right now";
  if (p.status === "TEMPORARILY_UNAVAILABLE") return "Paused for now";
  return "Not taking new customers";
}

/** One provider in the Discover list. */
export function ProviderCardView({ provider: p }: { provider: ProviderCard }) {
  const distance = formatDistance(p.distanceKm);
  return (
    <Link href={`/discover/${p.id}`} className="card card-link provider-card" aria-label={`${p.name}, ${providerTypesText(p.providerTypes)}`}>
      <div className="row row-nowrap between" style={{ alignItems: "flex-start", gap: "0.75rem" }}>
        <div className="row row-nowrap" style={{ minWidth: 0, alignItems: "flex-start" }}>
          <span className="avatar">
            <Store size={18} />
          </span>
          <div style={{ minWidth: 0 }}>
            <div className="strong truncate">{p.name}</div>
            <div className="small muted truncate">
              {p.locality}, {p.city}
            </div>
          </div>
        </div>
        {distance ? (
          <span className="row row-nowrap strong small" style={{ gap: "0.25rem", whiteSpace: "nowrap" }}>
            <MapPin size={14} aria-hidden /> {distance}
          </span>
        ) : null}
      </div>

      <div className="row" style={{ gap: "0.4rem", marginTop: "0.75rem" }}>
        {p.providerTypes.map((t) => (
          <Badge key={t} tone="accent">{PROVIDER_TYPE_LABEL[t] ?? t}</Badge>
        ))}
        <Badge>{FOOD_TYPE_LABEL[p.foodType] ?? p.foodType}</Badge>
        <Badge tone={p.available ? "ok" : "warn"}>{p.available ? PROVIDER_STATUS_LABEL.ACTIVE : unavailableText(p)}</Badge>
      </div>

      {p.description ? (
        <p className="small muted" style={{ marginTop: "0.6rem", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
          {p.description}
        </p>
      ) : null}

      <div className="row between" style={{ marginTop: "0.75rem" }}>
        <span className="small">
          {p.activePlanCount > 0 ? (
            <>
              <span className="strong">{priceRange(p)}</span>
              <span className="muted">
                {" "}
                · {p.activePlanCount} plan{p.activePlanCount === 1 ? "" : "s"}
              </span>
            </>
          ) : (
            <span className="muted">No plans published yet</span>
          )}
        </span>
        <ChevronRight size={18} className="faint" aria-hidden />
      </div>
    </Link>
  );
}
