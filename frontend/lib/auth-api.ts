import { ApiError, apiFetch } from "./api-client";
import { env } from "./env";

/** The two login portals. One account (identified by a phone number or a verified email) can be registered on either or both. */
export type Portal = "customer" | "staff";
export type PortalName = "CUSTOMER" | "STAFF";

export const PORTAL_NAME: Record<Portal, PortalName> = { customer: "CUSTOMER", staff: "STAFF" };

export type AuthTokens = {
  accessToken: string;
  /**
   * null when the backend keeps the refresh token in an HttpOnly cookie (cookie mode); otherwise the token itself,
   * which the session holds in memory only.
   */
  refreshToken: string | null;
};

/** What a one-time code is sent to, and what it proves. The server decides the door from the endpoint. */
export type Identifier = { type: "phone"; value: string } | { type: "email"; value: string };

export type Profile = {
  accountId: string;
  /** null for an account that signed up with an email only. */
  phone: string | null;
  /** false when there is no number, and when it was added without a code (it is then only a contact detail). */
  phoneVerified: boolean;
  email: string | null;
  /** false for no email, and for an email saved before verification existed. */
  emailVerified: boolean;
  /** Collected on the sign-up form; null for accounts that predate it. */
  fullName: string | null;
  status: "ACTIVE" | "SUSPENDED";
  /** Which portals this account is registered on, e.g. ["CUSTOMER", "STAFF"]. */
  portals: PortalName[];
  createdAt: string;
};

export function hasPortal(profile: Profile | null | undefined, portal: Portal): boolean {
  return !!profile?.portals?.includes(PORTAL_NAME[portal]);
}

/**
 * One function per identity endpoint (com.firstfood.identity.web). Deliberately thin - no token storage, no state, no
 * retry logic. That belongs in lib/session.tsx.
 *
 * An OTP only proves you own the number. Which door it opens is decided by the endpoint: sign-in needs an existing
 * registration on that portal (404 ACCOUNT_NOT_REGISTERED otherwise), sign-up creates one (409
 * ACCOUNT_ALREADY_REGISTERED if it exists). Both reject BEFORE consuming the code, so the screen can switch to the
 * other form and reuse the code the person already typed.
 */
export const authApi = {
  /**
   * Same for both portals. `challengeToken` is the one-time Turnstile token (F-05): the backend refuses to send an SMS
   * or an email without a valid one while its challenge is on. A token works once, so every request needs a fresh one.
   */
  requestOtp(id: Identifier, challengeToken?: string | null): Promise<void> {
    return apiFetch<void>(id.type === "email" ? "/auth/email/otp/request" : "/auth/otp/request", {
      method: "POST",
      body: id.type === "email" ? { email: id.value } : { phone: id.value },
      headers: challengeToken ? { "X-Challenge-Token": challengeToken } : undefined,
    });
  },

  signIn(portal: Portal, id: Identifier, code: string): Promise<AuthTokens> {
    return apiFetch<AuthTokens>(id.type === "email" ? `/auth/${portal}/email/signin` : `/auth/${portal}/signin`, {
      method: "POST",
      body: id.type === "email" ? { email: id.value, code } : { phone: id.value, code },
      refreshCookie: env.refreshCookieMode,
    });
  },

  signUp(portal: Portal, id: Identifier, code: string, fullName: string): Promise<AuthTokens> {
    return apiFetch<AuthTokens>(id.type === "email" ? `/auth/${portal}/email/signup` : `/auth/${portal}/signup`, {
      method: "POST",
      body: id.type === "email" ? { email: id.value, code, fullName } : { phone: id.value, code, fullName },
      refreshCookie: env.refreshCookieMode,
    });
  },

  /**
   * Works with either backend behaviour: when we hold a refresh token (the backend returned one in the body) it is
   * sent in the body; otherwise, in same-origin mode, the browser's HttpOnly cookie is used.
   */
  refresh(refreshToken?: string | null): Promise<AuthTokens> {
    if (refreshToken) return apiFetch<AuthTokens>("/auth/refresh", { method: "POST", body: { refreshToken } });
    if (env.refreshCookieMode) return apiFetch<AuthTokens>("/auth/refresh", { method: "POST", refreshCookie: true });
    return Promise.reject(new ApiError(401, "UNAUTHENTICATED", "Please sign in again."));
  },

  logout(refreshToken?: string | null): Promise<void> {
    if (refreshToken) return apiFetch<void>("/auth/logout", { method: "POST", body: { refreshToken } });
    if (env.refreshCookieMode) return apiFetch<void>("/auth/logout", { method: "POST", refreshCookie: true });
    return Promise.resolve();
  },

  getProfile(accessToken: string): Promise<Profile> {
    return apiFetch<Profile>("/me", { accessToken });
  },

  /** Step 1 of adding/changing an email: a code is sent to THIS address; nothing is stored yet. */
  requestEmailChange(accessToken: string, email: string): Promise<void> {
    return apiFetch<void>("/me/email/otp/request", { method: "POST", accessToken, body: { email } });
  },

  /** Step 2: the same address plus the code that was emailed to it. */
  verifyEmailChange(accessToken: string, email: string, code: string): Promise<Profile> {
    return apiFetch<Profile>("/me/email/otp/verify", { method: "POST", accessToken, body: { email, code } });
  },

  /**
   * Adds a number with NO code - only while the backend does not require verification (409 PHONE_VERIFICATION_REQUIRED
   * otherwise). The number stays unverified: it does not sign in, and gets no staff access.
   */
  addPhone(accessToken: string, phone: string): Promise<Profile> {
    return apiFetch<Profile>("/me/phone", { method: "PUT", accessToken, body: { phone } });
  },

  requestPhoneChange(accessToken: string, newPhone: string): Promise<void> {
    return apiFetch<void>("/me/phone/otp/request", { method: "POST", accessToken, body: { newPhone } });
  },

  verifyPhoneChange(accessToken: string, newPhone: string, code: string): Promise<Profile> {
    return apiFetch<Profile>("/me/phone/otp/verify", {
      method: "POST",
      accessToken,
      body: { newPhone, code },
    });
  },
};
