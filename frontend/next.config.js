/**
 * Same-origin reverse proxy for the FirstFood API.
 *
 * PROXY mode is on when NEXT_PUBLIC_API_BASE_URL is the empty string (see
 * lib/env.ts). Then the browser calls /api/v1/... on THIS origin and Next
 * forwards it to BACKEND_ORIGIN. In direct mode (a non-empty
 * NEXT_PUBLIC_API_BASE_URL) nothing here is registered, so rolling back is a
 * single environment change.
 *
 * IMPORTANT - build time, not runtime: rewrites are compiled into the build
 * (.next/routes-manifest.json), so BACKEND_ORIGIN must be present when
 * `next build` runs (the Dockerfile passes it as a build ARG). Changing it on a
 * running container does nothing; rebuild instead.
 *
 * Safety rules baked in (see the migration risk register):
 *  - Only /api/v1/** is forwarded. /actuator and every other backend path stay
 *    unreachable from the public origin (R-12).
 *  - The destination is a FIXED origin read from the environment, never from
 *    the request, so this cannot be turned into an open proxy / SSRF (R-05).
 *  - BACKEND_ORIGIN is server-only (no NEXT_PUBLIC_ prefix): never shipped to
 *    browsers (R-13).
 *  - Authenticated responses are marked no-store here as well as by the backend,
 *    so no CDN/browser cache can serve one user's data to another (R-02).
 */
function backendOrigin() {
  const raw = process.env.BACKEND_ORIGIN;
  if (!raw || raw.trim() === "") {
    throw new Error(
      "BACKEND_ORIGIN is required when NEXT_PUBLIC_API_BASE_URL is empty (same-origin proxy mode). " +
        "Example: BACKEND_ORIGIN=http://localhost:8080 (native dev) or http://backend:8080 (docker compose).",
    );
  }
  let url;
  try {
    url = new URL(raw.trim());
  } catch {
    throw new Error(`BACKEND_ORIGIN is not a valid URL: "${raw}"`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`BACKEND_ORIGIN must be http(s), got "${url.protocol}"`);
  }
  if (url.username || url.password) {
    throw new Error("BACKEND_ORIGIN must not contain credentials.");
  }
  if ((url.pathname !== "/" && url.pathname !== "") || url.search || url.hash) {
    throw new Error(
      `BACKEND_ORIGIN must be an origin only (scheme://host[:port]) with no path, query or fragment, got "${raw}"`,
    );
  }
  return url.origin; // normalised: no trailing slash
}

const proxyMode = process.env.NEXT_PUBLIC_API_BASE_URL === "";
const isProd = process.env.NODE_ENV === "production";

/**
 * Browser security headers for every page.
 *
 * CSP: everything is locked to this origin; the API origin is added to connect-src only in direct mode.
 * script-src still needs 'unsafe-inline' because Next.js injects inline hydration scripts; the stricter,
 * nonce-based policy (no 'unsafe-inline') needs a per-request nonce and must be verified in a real browser before
 * it is enforced - tracked in docs/OPERATIONS.md. 'unsafe-eval' is development-only (React refresh).
 *
 * F-05: the Cloudflare Turnstile widget (human check on the public OTP request) loads its script from, and renders inside
 * an iframe served by, challenges.cloudflare.com. Those two sources are allowed ONLY when a site key is configured
 * (NEXT_PUBLIC_TURNSTILE_SITE_KEY), so a deployment without the challenge keeps the strict policy. Cloudflare documents
 * script-src and frame-src as the required directives (developers.cloudflare.com/turnstile/reference/content-security-policy).
 *
 * HSTS is production-only and deliberately WITHOUT includeSubDomains/preload: add those only after every subdomain
 * is confirmed HTTPS-ready, because browsers will refuse plain HTTP for the whole max-age and it cannot be undone
 * quickly. F-16: the value here MUST equal the one in deploy/nginx/firstfood.conf (checked by
 * infra/scripts/verify-edge-config.sh). Behind nginx that header wins (nginx hides this one); this copy is the
 * fallback when the app is reached without it.
 */
function securityHeaders() {
  const direct = process.env.NEXT_PUBLIC_API_BASE_URL;
  const connectSrc = ["'self'"];
  if (direct) {
    try {
      connectSrc.push(new URL(direct).origin);
    } catch {
      /* lib/env.ts reports a malformed value at runtime */
    }
  }
  const turnstile = (process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "").trim() !== "";
  // "Continue with Google" (Google Identity Services). Google documents exactly these four directives
  // (developers.google.com/identity/gsi/web/guides/get-google-api-clientid#content_security_policy). Added ONLY when a
  // client id is configured, so a deployment without Google sign-in keeps the strict policy.
  const google = (process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ?? "").trim() !== "";
  if (google) connectSrc.push("https://accounts.google.com/gsi/");
  const frameSrc = [...(turnstile ? ["https://challenges.cloudflare.com"] : []), ...(google ? ["https://accounts.google.com/gsi/"] : [])];
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'unsafe-inline'${isProd ? "" : " 'unsafe-eval'"}${turnstile ? " https://challenges.cloudflare.com" : ""}${google ? " https://accounts.google.com/gsi/client" : ""}`,
    ...(frameSrc.length ? [`frame-src ${frameSrc.join(" ")}`] : []),
    `style-src 'self' 'unsafe-inline'${google ? " https://accounts.google.com/gsi/style" : ""}`,
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    `connect-src ${connectSrc.join(" ")}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");

  return [
    { key: "Content-Security-Policy", value: csp },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" },
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
    { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
    ...(isProd ? [{ key: "Strict-Transport-Security", value: "max-age=31536000" }] : []),
  ];
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Do not advertise the framework version in a response header.
  poweredByHeader: false,

  // F-04: the app serves no optimized images, so remove the /_next/image optimizer route entirely. It was the
  // attack surface of several Next.js image-optimizer advisories, and nginx forwards everything under / to Next.
  images: { unoptimized: true },

  async rewrites() {
    if (!proxyMode) return [];
    return [
      {
        source: "/api/v1/:path*",
        destination: `${backendOrigin()}/api/v1/:path*`,
      },
    ];
  },

  async headers() {
    return [
      { source: "/:path*", headers: securityHeaders() },
      {
        source: "/api/:path*",
        headers: [{ key: "Cache-Control", value: "no-store" }],
      },
    ];
  },
};

// Fail the build (not the first user request) if proxy mode is on but misconfigured.
if (proxyMode) backendOrigin();

module.exports = nextConfig;
