"use client";

import { useEffect, useMemo, useState } from "react";
import { Compass, LocateFixed, MapPinOff, SlidersHorizontal } from "lucide-react";
import { Banner, Boundary, Empty, Field, ListSkeleton, SearchBox, Segmented, Spinner } from "@/components/ui";
import { ProviderCardView } from "@/components/domain/ProviderCardView";
import { FOOD_TYPE_LABEL, PROVIDER_TYPE_LABEL } from "@/components/domain/status";
import { discoveryApi, type DiscoverySort } from "@/lib/discovery-api";
import { useQuery } from "@/lib/hooks";
import { useLocation } from "@/lib/location";
import { FOOD_TYPES, PROVIDER_TYPES, type FoodType, type ProviderType } from "@/lib/provider-api";

const RADIUS_OPTIONS = [
  { value: "3", label: "3 km" },
  { value: "5", label: "5 km" },
  { value: "10", label: "10 km" },
  { value: "25", label: "25 km" },
  { value: "50", label: "50 km" },
] as const;

function useDebounced<T>(value: T, ms = 350): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

function parsePrice(raw: string): number | undefined {
  const n = Number(raw);
  return raw.trim() !== "" && Number.isFinite(n) && n >= 0 ? n : undefined;
}

/**
 * Customer landing: every provider near the customer that can take a new customer right now. The server decides what
 * "nearby" and "available" mean (distance, status, intake); this screen only sends the person's filters.
 */
export default function DiscoverPage() {
  const { location, setLocation, locate, state: locState, settled } = useLocation({ auto: true });
  const [radius, setRadius] = useState("25");
  const [text, setText] = useState("");
  const [city, setCity] = useState("");
  const [type, setType] = useState<ProviderType | "">("");
  const [foodType, setFoodType] = useState<FoodType | "">("");
  const [availableOnly, setAvailableOnly] = useState(true);
  const [minPrice, setMinPrice] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [sort, setSort] = useState<DiscoverySort | "">("");
  const [showFilters, setShowFilters] = useState(false);

  const q = useDebounced(text);
  const debouncedCity = useDebounced(city);
  const min = useDebounced(minPrice);
  const max = useDebounced(maxPrice);

  const minNum = parsePrice(min);
  const maxNum = parsePrice(max);
  const priceError = minNum !== undefined && maxNum !== undefined && minNum > maxNum ? "Minimum is above maximum" : null;

  const providers = useQuery(
    (t) =>
      discoveryApi.search(t, {
        location,
        radiusKm: Number(radius),
        q,
        city: debouncedCity,
        types: type ? [type] : undefined,
        foodTypes: foodType ? [foodType] : undefined,
        availableOnly,
        minPrice: minNum,
        maxPrice: maxNum,
        sort: sort || undefined,
        limit: 200,
      }),
    [location?.lat, location?.lng, radius, q, debouncedCity, type, foodType, availableOnly, minNum, maxNum, sort],
    { enabled: settled && !priceError },
  );

  const sortOptions = useMemo(
    () => [
      ...(location ? [{ value: "distance" as const, label: "Nearest" }] : []),
      { value: "price" as const, label: "Cheapest" },
      { value: "name" as const, label: "A–Z" },
      { value: "newest" as const, label: "Newest" },
    ],
    [location],
  );
  const effectiveSort: DiscoverySort = sort || (location ? "distance" : "newest");
  const activeFilters = [type, foodType, city.trim(), minPrice, maxPrice].filter(Boolean).length + (availableOnly ? 0 : 1);

  return (
    <main className="page" style={{ maxWidth: "60rem" }}>
      <div>
        <h1>Find food near you</h1>
        <p className="muted" style={{ marginTop: "0.25rem" }}>
          Every mess, tiffin service and food provider near you that is taking customers right now.
        </p>
      </div>

      {location ? (
        <div className="card row between" style={{ gap: "0.75rem" }}>
          <div className="row row-nowrap">
            <span className="avatar">
              <LocateFixed size={18} />
            </span>
            <div>
              <div className="strong">Providers near you</div>
              <div className="small muted">All available options within {radius} km, nearest first.</div>
            </div>
          </div>
          <div className="row">
            <Segmented label="Search radius" value={radius} onChange={setRadius} options={[...RADIUS_OPTIONS]} />
            <button
              className="btn btn-sm btn-ghost"
              onClick={() => {
                setLocation(null);
                if (sort === "distance") setSort("");
              }}
            >
              Clear
            </button>
          </div>
        </div>
      ) : (
        <Banner
          tone={locState === "denied" || locState === "unsupported" ? "warn" : "info"}
          action={
            locState === "unsupported" ? undefined : (
              <button className="btn btn-sm btn-primary" onClick={locate} disabled={locState === "locating"}>
                {locState === "locating" ? <Spinner /> : <LocateFixed size={16} />}
                Use my location
              </button>
            )
          }
        >
          {locState === "denied"
            ? "Location is blocked, so we can't sort by distance. Allow it in your browser, or search by city below."
            : locState === "unsupported"
              ? "This browser can't share a location. Search by city below."
              : locState === "locating"
                ? "Finding your location to show the providers closest to you…"
                : "Share your location to see the providers closest to you. Until then you're seeing everyone who is taking customers."}
        </Banner>
      )}

      <div className="stack stack-s">
        <div className="row" style={{ gap: "0.6rem" }}>
          <div className="grow" style={{ minWidth: "12rem" }}>
            <SearchBox value={text} onChange={(e) => setText(e.target.value)} placeholder="Search by name or area" aria-label="Search providers" />
          </div>
          <button className="btn" aria-expanded={showFilters} onClick={() => setShowFilters((v) => !v)}>
            <SlidersHorizontal size={16} /> Filters{activeFilters ? ` (${activeFilters})` : ""}
          </button>
        </div>

        {showFilters ? (
          <div className="card stack">
            <div className="form-grid">
              <Field label="Type">
                {(id) => (
                  <select id={id} className="select" value={type} onChange={(e) => setType(e.target.value as ProviderType | "")}>
                    <option value="">Any</option>
                    {PROVIDER_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {PROVIDER_TYPE_LABEL[t]}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Food">
                {(id) => (
                  <select id={id} className="select" value={foodType} onChange={(e) => setFoodType(e.target.value as FoodType | "")}>
                    <option value="">Any</option>
                    {FOOD_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {FOOD_TYPE_LABEL[t]}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="City" hint="Also finds providers without a pinned location.">
                {(id) => <input id={id} className="input" value={city} onChange={(e) => setCity(e.target.value)} placeholder="Bhopal" autoComplete="address-level2" />}
              </Field>
            </div>
            <div className="form-grid">
              <Field label="Plan price from (₹)" error={priceError}>
                {(id) => <input id={id} className="input" inputMode="numeric" value={minPrice} onChange={(e) => setMinPrice(e.target.value.replace(/[^\d.]/g, ""))} placeholder="0" />}
              </Field>
              <Field label="Plan price up to (₹)">
                {(id) => <input id={id} className="input" inputMode="numeric" value={maxPrice} onChange={(e) => setMaxPrice(e.target.value.replace(/[^\d.]/g, ""))} placeholder="5000" />}
              </Field>
            </div>
            <label className="row" style={{ gap: "0.5rem", cursor: "pointer" }}>
              <input type="checkbox" checked={availableOnly} onChange={(e) => setAvailableOnly(e.target.checked)} />
              <span>Only providers taking new customers</span>
            </label>
            {activeFilters ? (
              <div>
                <button
                  className="btn btn-sm btn-ghost"
                  onClick={() => {
                    setType("");
                    setFoodType("");
                    setCity("");
                    setMinPrice("");
                    setMaxPrice("");
                    setAvailableOnly(true);
                  }}
                >
                  Reset filters
                </button>
              </div>
            ) : null}
          </div>
        ) : null}

        <div className="row between" style={{ gap: "0.6rem" }}>
          <span className="small muted" aria-live="polite">
            {providers.data ? `${providers.data.length} provider${providers.data.length === 1 ? "" : "s"}` : " "}
            {providers.loading && providers.data ? " · updating…" : ""}
          </span>
          <Segmented label="Sort by" value={effectiveSort} onChange={setSort} options={sortOptions} />
        </div>
      </div>

      <Boundary query={providers} skeleton={<ListSkeleton rows={4} />}>
        {(list) =>
          list.length === 0 ? (
            <div className="card">
              <Empty
                icon={location ? <MapPinOff size={26} /> : <Compass size={26} />}
                title="No providers found"
                action={
                  location && Number(radius) < 50 ? (
                    <button className="btn" onClick={() => setRadius("50")}>
                      Search within 50 km
                    </button>
                  ) : availableOnly ? (
                    <button className="btn" onClick={() => setAvailableOnly(false)}>
                      Include providers that are full or paused
                    </button>
                  ) : undefined
                }
              >
                {location ? `Nothing within ${radius} km matches these filters.` : "Nothing matches these filters."}
              </Empty>
            </div>
          ) : (
            <div className="grid grid-2" style={{ opacity: providers.loading ? 0.6 : 1, transition: "opacity .15s" }}>
              {list.map((p) => (
                <ProviderCardView key={p.id} provider={p} />
              ))}
            </div>
          )
        }
      </Boundary>
    </main>
  );
}
