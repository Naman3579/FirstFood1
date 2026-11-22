import { apiFetch } from "./api-client";
import type { ConsumptionType, PlanPolicy } from "./plan-api";
import type { FoodType, ProviderStatus, ProviderType } from "./provider-api";
import type { MySubscription } from "./subscription-api";

export type DiscoverySort = "distance" | "price" | "name" | "newest";

/** One provider as a customer sees it: public facts only (no role, capacity or closure data). */
export type ProviderCard = {
  id: string;
  name: string;
  providerTypes: ProviderType[];
  foodType: FoodType;
  description: string | null;
  addressLine: string;
  locality: string;
  city: string;
  pincode: string | null;
  contactPhone: string | null;
  status: ProviderStatus;
  acceptingNewCustomers: boolean;
  /** Taking new customers right now (open AND accepting). Decided by the server. */
  available: boolean;
  latitude: number | null;
  longitude: number | null;
  /** Km from the searched location; null when no location was sent or the provider has no coordinates. */
  distanceKm: number | null;
  activePlanCount: number;
  minPrice: number | null;
  maxPrice: number | null;
  currency: string;
};

/** A plan a customer can subscribe to right now. */
export type PlanListing = {
  id: string;
  providerId: string;
  name: string;
  description: string | null;
  consumptionType: ConsumptionType;
  durationDays: number | null;
  mealQuantity: number | null;
  price: number;
  currency: string;
  policy: PlanPolicy;
};

export type ProviderDetail = { provider: ProviderCard; plans: PlanListing[] };

export type Coordinates = { lat: number; lng: number };

export type DiscoveryFilters = {
  location?: Coordinates | null;
  radiusKm?: number;
  city?: string;
  q?: string;
  types?: ProviderType[];
  foodTypes?: FoodType[];
  /** Server default is true: only providers taking new customers. */
  availableOnly?: boolean;
  minPrice?: number;
  maxPrice?: number;
  sort?: DiscoverySort;
  limit?: number;
};

function locationQuery(location?: Coordinates | null): string[] {
  return location ? [`lat=${location.lat}`, `lng=${location.lng}`] : [];
}

/**
 * Customer-portal discovery endpoints (com.firstfood.discovery.web). Everything that is a rule - what counts as
 * "nearby", what is available, whether a subscription may be bought - is decided by the backend.
 */
export const discoveryApi = {
  search(accessToken: string, f: DiscoveryFilters = {}): Promise<ProviderCard[]> {
    const q = [...locationQuery(f.location)];
    if (f.location && f.radiusKm) q.push(`radiusKm=${f.radiusKm}`);
    if (f.city?.trim()) q.push(`city=${encodeURIComponent(f.city.trim())}`);
    if (f.q?.trim()) q.push(`q=${encodeURIComponent(f.q.trim())}`);
    if (f.types?.length) q.push(`type=${f.types.join(",")}`);
    if (f.foodTypes?.length) q.push(`foodType=${f.foodTypes.join(",")}`);
    if (f.availableOnly !== undefined) q.push(`availableOnly=${f.availableOnly}`);
    if (f.minPrice !== undefined) q.push(`minPrice=${f.minPrice}`);
    if (f.maxPrice !== undefined) q.push(`maxPrice=${f.maxPrice}`);
    if (f.sort) q.push(`sort=${f.sort}`);
    if (f.limit) q.push(`limit=${f.limit}`);
    return apiFetch<ProviderCard[]>(`/discover/providers${q.length ? `?${q.join("&")}` : ""}`, { accessToken });
  },

  get(accessToken: string, providerId: string, location?: Coordinates | null): Promise<ProviderDetail> {
    const q = locationQuery(location);
    return apiFetch<ProviderDetail>(`/discover/providers/${providerId}${q.length ? `?${q.join("&")}` : ""}`, {
      accessToken,
    });
  },

  /**
   * Joins the provider (if not yet a member) and buys the plan, atomically. Price and terms are never sent: the
   * server snapshots the plan. `startDate` ("YYYY-MM-DD") defaults to today.
   */
  subscribe(
    accessToken: string,
    providerId: string,
    input: { planId: string; startDate?: string },
  ): Promise<MySubscription> {
    return apiFetch<MySubscription>(`/discover/providers/${providerId}/subscribe`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },
};

/** "2.4 km" / "850 m" - display only. */
export function formatDistance(km: number | null | undefined): string {
  if (km === null || km === undefined) return "";
  return km < 1 ? `${Math.round(km * 1000)} m` : `${km.toFixed(1)} km`;
}
