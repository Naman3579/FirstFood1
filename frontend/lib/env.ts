/**
 * Centralized, validated access to environment configuration.
 * Never read process.env directly elsewhere - import from here so a
 * missing variable fails fast instead of surfacing as a confusing bug
 * deep in a component (phases.md Phase 1 "environment configuration").
 *
 * NEXT_PUBLIC_API_BASE_URL has two valid shapes (reverse-proxy migration):
 *   - ""                      -> same-origin PROXY mode. The browser calls
 *                                /api/v1/... on the frontend's own origin and
 *                                next.config.js rewrites it to BACKEND_ORIGIN.
 *   - "https://api.example"   -> DIRECT mode (the pre-proxy behaviour; also the
 *                                one-line rollback). Needs the backend's CORS
 *                                allowlist to include this site's origin.
 * "Not set at all" is still an error: an empty string must be a deliberate
 * choice, never the result of a forgotten variable. (The old check used a
 * falsy test, which would have crashed the whole app on a deliberate "".)
 */
function apiBaseUrl(value: string | undefined): string {
  if (value === undefined) {
    throw new Error(
      "Missing required environment variable: NEXT_PUBLIC_API_BASE_URL " +
        '(use "" for same-origin proxy mode, or the backend URL for direct mode)',
    );
  }
  const trimmed = value.trim().replace(/\/+$/, "");
  if (trimmed !== "" && !/^https?:\/\//.test(trimmed)) {
    throw new Error(
      `NEXT_PUBLIC_API_BASE_URL must be empty or start with http:// or https:// (got "${value}")`,
    );
  }
  return trimmed;
}

const resolvedApiBaseUrl = apiBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL);

export const env = {
  apiBaseUrl: resolvedApiBaseUrl,
  /** "proxy" = same-origin /api/v1 (rewritten by Next); "direct" = browser calls the backend host. */
  apiMode: resolvedApiBaseUrl === "" ? ("proxy" as const) : ("direct" as const),
  /**
   * In same-origin proxy mode the refresh token travels ONLY in an HttpOnly, SameSite=Strict cookie (page scripts
   * never see it, and a reload can restore the session). Direct mode is cross-origin, where that cookie cannot
   * work safely, so it keeps the refresh token in memory as before.
   */
  refreshCookieMode: resolvedApiBaseUrl === "",
  /**
   * F-05: PUBLIC Cloudflare Turnstile site key (safe for browsers; the secret key lives only in the backend). Empty =
   * no human check is shown, which only works while the backend challenge is off. Build-time, like the values above.
   */
  /**
   * Phone sign-in texts an SMS, which needs a paid provider and (in India) DLT registration. Until that exists the
   * sign-in screen steers people to email instead. Set NEXT_PUBLIC_PHONE_OTP_ENABLED=true (build-time) to switch it on.
   */
  phoneOtpEnabled: (process.env.NEXT_PUBLIC_PHONE_OTP_ENABLED ?? "").trim() === "true",
  turnstileSiteKey: (process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "").trim(),
};
