"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ApiError } from "./api-client";
import { authApi, type AuthTokens, type Identifier, type Portal, type Profile } from "./auth-api";
import { membershipApi } from "./membership-api";
import { env } from "./env";

/**
 * One place that owns the signed-in session.
 *
 * Token storage: the short-lived ACCESS token lives in memory only - never localStorage/sessionStorage. The refresh
 * token is whatever the backend hands back: an HttpOnly cookie (cookie mode - a reload restores the session) or a value
 * in the response body, which is then held in memory only (a reload signs the person out).
 *
 * One session serves both portals: the same phone number can be registered as a customer, as staff, or both. Which
 * door a screen belongs to is decided by `profile.portals` (see PortalGate); the backend enforces it per request.
 *
 * `call()` is how every screen talks to the API: it attaches the access token and, on a 401, refreshes ONCE
 * (single-flight - refresh tokens rotate, so two parallel refreshes would make the second one look like a logout) and
 * retries. Screens never see token handling.
 */

type Status = "loading" | "anon" | "authed";

type SessionValue = {
  status: Status;
  profile: Profile | null;
  setProfile: (p: Profile) => void;
  /** `challengeToken`: one-time bot-challenge token for the public OTP request (F-05). */
  requestOtp: (id: Identifier, challengeToken?: string | null) => Promise<void>;
  /** Sign in to an EXISTING registration on this portal. Rejects 404 ACCOUNT_NOT_REGISTERED otherwise. */
  signIn: (portal: Portal, id: Identifier, code: string) => Promise<void>;
  /** Register on this portal (creating the account if the number is new). Rejects 409 ACCOUNT_ALREADY_REGISTERED. */
  signUp: (portal: Portal, id: Identifier, code: string, fullName: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Run an API call with the current access token, refreshing it once if it has expired. */
  call: <T>(fn: (accessToken: string) => Promise<T>) => Promise<T>;
};

const SessionContext = createContext<SessionValue | null>(null);

/** Refresh a little before the 15-minute access token expires. */
const ACCESS_REFRESH_MS = 12 * 60 * 1000;

type Restored = { accessToken: string; refreshToken: string | null; profile: Profile } | null;
let restoreInFlight: Promise<Restored> | null = null;

/** Runs once per page load even though React StrictMode runs effects twice in development. */
function restoreSession(): Promise<Restored> {
  restoreInFlight ??= authApi
    .refresh()
    .then(async (tokens) => ({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      profile: await authApi.getProfile(tokens.accessToken),
    }))
    .catch((err) => {
      if (err instanceof ApiError && err.status === 401) void authApi.logout().catch(() => undefined);
      return null;
    })
    .finally(() => {
      setTimeout(() => (restoreInFlight = null), 0);
    });
  return restoreInFlight;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>(env.refreshCookieMode ? "loading" : "anon");
  const [profile, setProfile] = useState<Profile | null>(null);
  const accessRef = useRef<string | null>(null);
  const refreshRef = useRef<string | null>(null);
  const issuedAt = useRef(0);
  const refreshing = useRef<Promise<string> | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = useCallback(() => {
    accessRef.current = null;
    refreshRef.current = null;
    if (timer.current) clearTimeout(timer.current);
    setProfile(null);
    setStatus("anon");
  }, []);

  const refreshNow = useCallback((): Promise<string> => {
    refreshing.current ??= authApi
      .refresh(refreshRef.current)
      .then((tokens) => {
        accessRef.current = tokens.accessToken;
        refreshRef.current = tokens.refreshToken;
        issuedAt.current = Date.now();
        schedule();
        return tokens.accessToken;
      })
      .catch((err) => {
        // Only a definite "your session is dead" signs the person out; a network blip should not.
        if (err instanceof ApiError && (err.status === 401 || err.status === 403)) clear();
        throw err;
      })
      .finally(() => {
        refreshing.current = null;
      });
    return refreshing.current;
  }, [clear]);

  function schedule() {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void refreshNow().catch(() => undefined), ACCESS_REFRESH_MS);
  }

  // Cookie mode: bring the session back after a reload.
  useEffect(() => {
    if (!env.refreshCookieMode) return;
    let cancelled = false;
    void restoreSession().then((restored) => {
      if (cancelled) return;
      if (restored) {
        accessRef.current = restored.accessToken;
        refreshRef.current = restored.refreshToken;
        issuedAt.current = Date.now();
        schedule();
        setProfile(restored.profile);
        setStatus("authed");
      } else {
        setStatus("anon");
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Browsers throttle timers in background tabs: renew on return if the token is (nearly) stale.
  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === "visible" && accessRef.current && Date.now() - issuedAt.current > ACCESS_REFRESH_MS) {
        void refreshNow().catch(() => undefined);
      }
    }
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [refreshNow]);

  const call = useCallback(
    async <T,>(fn: (accessToken: string) => Promise<T>): Promise<T> => {
      if (!accessRef.current) throw new ApiError(401, "UNAUTHENTICATED", "Please sign in again.");
      try {
        return await fn(accessRef.current);
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          const fresh = await refreshNow();
          return fn(fresh);
        }
        throw err;
      }
    },
    [refreshNow],
  );

  /** Install a fresh token pair as THE session (replacing any previous one, e.g. when adding the other portal). */
  const start = useCallback(
    async (tokens: AuthTokens, beforeReady?: (accessToken: string, me: Profile) => Promise<void>) => {
      const me = await authApi.getProfile(tokens.accessToken);
      await beforeReady?.(tokens.accessToken, me);
      accessRef.current = tokens.accessToken;
      refreshRef.current = tokens.refreshToken;
      issuedAt.current = Date.now();
      schedule();
      setProfile(me);
      setStatus("authed");
    },
    [],
  );

  const value = useMemo<SessionValue>(
    () => ({
      status,
      profile,
      setProfile,
      requestOtp: (id, challengeToken) => authApi.requestOtp(id, challengeToken),
      async signIn(portal, id, code) {
        await start(await authApi.signIn(portal, id, code));
      },
      async signUp(portal, id, code, fullName) {
        await start(await authApi.signUp(portal, id, code, fullName), async (t, me) => {
          // A customer is also a "person" providers can put on their list. Create it from the name just typed so the
          // customer never sees an "add your name" nag. Best effort: the account page can still do it later.
          if (portal !== "customer") return;
          try {
            await membershipApi.getMyPerson(t);
          } catch (err) {
            if (err instanceof ApiError && err.status === 404) {
              await membershipApi.saveMyPerson(t, me.fullName ?? fullName.trim()).catch(() => undefined);
            }
          }
        });
      },
      async logout() {
        try {
          await authApi.logout(refreshRef.current);
        } catch {
          /* logging out must always succeed locally */
        } finally {
          clear();
        }
      },
      call,
    }),
    [status, profile, call, clear, start],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession must be used inside <SessionProvider>");
  return ctx;
}
