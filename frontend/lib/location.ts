"use client";

import { useCallback, useEffect, useState } from "react";
import type { Coordinates } from "./discovery-api";

const KEY = "ff.location";
/** Set once the browser has been asked in this tab, so an automatic request is never repeated after a "no". */
const ASKED_KEY = "ff.location.asked";

function read(): Coordinates | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) return null;
    const v = JSON.parse(raw) as Partial<Coordinates>;
    return typeof v.lat === "number" && typeof v.lng === "number" ? { lat: v.lat, lng: v.lng } : null;
  } catch {
    return null;
  }
}

function write(value: Coordinates | null) {
  try {
    if (value) window.sessionStorage.setItem(KEY, JSON.stringify(value));
    else window.sessionStorage.removeItem(KEY);
  } catch {
    /* storage can be blocked; the location then just lives in memory for this page */
  }
}

function wasAsked(): boolean {
  try {
    return window.sessionStorage.getItem(ASKED_KEY) === "1";
  } catch {
    return false;
  }
}

function markAsked() {
  try {
    window.sessionStorage.setItem(ASKED_KEY, "1");
  } catch {
    /* ignore */
  }
}

export type LocationState = "idle" | "locating" | "denied" | "unsupported";

/**
 * The customer's current position, asked for only when they allow it. Kept for the browser tab (sessionStorage) so
 * the provider page can show the distance without asking again. Never sent anywhere except as lat/lng query
 * parameters of the discovery endpoints.
 *
 * With {@code auto}, the browser is asked once per tab as soon as the screen opens (when no position is stored yet),
 * so the customer lands on "providers near you" without having to find a button. A refusal is remembered for the tab.
 */
export function useLocation({ auto = false }: { auto?: boolean } = {}) {
  const [location, setLocationState] = useState<Coordinates | null>(null);
  const [state, setState] = useState<LocationState>("idle");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setLocationState(read());
    setReady(true);
  }, []);

  const setLocation = useCallback((value: Coordinates | null) => {
    write(value);
    setLocationState(value);
  }, []);

  const locate = useCallback(() => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setState("unsupported");
      return;
    }
    markAsked();
    setState("locating");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        // ~100 m precision is plenty for "nearby" and keeps the exact position out of URLs and logs.
        const rounded = { lat: Math.round(pos.coords.latitude * 1000) / 1000, lng: Math.round(pos.coords.longitude * 1000) / 1000 };
        setLocation(rounded);
        setState("idle");
      },
      () => setState("denied"),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 5 * 60_000 },
    );
  }, [setLocation]);

  useEffect(() => {
    if (auto && ready && !location && state === "idle" && !wasAsked()) locate();
  }, [auto, ready, location, state, locate]);

  // False while a position is stored-but-unread, being fetched, or about to be asked for: a screen that searches by
  // location should wait for this instead of briefly showing everyone.
  const settled = ready && state !== "locating" && !(auto && !location && state === "idle" && !wasAsked());

  return { location, setLocation, locate, state, ready, settled };
}
