import { env } from "./env";

/**
 * Thin wrapper around fetch for the FirstFood v1 API.
 *
 * ALL browser HTTP to the backend goes through this one function. In proxy
 * mode env.apiBaseUrl is "" so the URL is the relative /api/v1/... (same
 * origin, rewritten to the backend by next.config.js); in direct mode it is
 * the backend host. Do not add other fetch() calls to the backend elsewhere.
 *
 * The backend is the authoritative source for business rules (rules.md
 * Rule 28.1 "Frontend Is Not the Source of Truth") - this client should
 * stay dumb: build requests, attach auth, parse responses, surface
 * errors. No subscription/expiry/extension calculation belongs here.
 */
/**
 * Every provider-scoped endpoint lives under /api/v1/staff and needs a staff registration (403 PORTAL_ACCESS_DENIED
 * otherwise). Customer-side endpoints (/me/person, /me/memberships, /subscriptions/...) stay at the top level.
 */
export const STAFF = "/staff";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    /** From the Retry-After header on 429/503, when the server sent one. */
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  accessToken?: string;
  /** Ask the backend to carry the refresh token in an HttpOnly cookie instead of the JSON body. */
  refreshCookie?: boolean;
  /** Extra request headers (e.g. the F-05 bot-challenge token). Never used to override Content-Type or Authorization. */
  headers?: Record<string, string>;
};

/** A request that has not answered by now is treated as failed, so the UI never waits forever. */
const REQUEST_TIMEOUT_MS = 20_000;

function waitText(seconds: number): string {
  if (seconds < 90) return `${seconds} second${seconds === 1 ? "" : "s"}`;
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

function retryAfterSeconds(response: Response): number | undefined {
  const raw = response.headers.get("Retry-After");
  if (!raw) return undefined;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, accessToken, refreshCookie = false, headers: extraHeaders } = options;

  let response: Response;
  try {
    response = await fetch(`${env.apiBaseUrl}/api/v1${path}`, {
      method,
      headers: {
        ...extraHeaders,
        "Content-Type": "application/json",
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...(refreshCookie ? { "X-Refresh-Mode": "cookie" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (cause) {
    const timedOut = cause instanceof DOMException && cause.name === "TimeoutError";
    throw new ApiError(
      0,
      timedOut ? "REQUEST_TIMEOUT" : "NETWORK_ERROR",
      timedOut
        ? "The server took too long to respond. Please try again."
        : "Could not reach the server. Check your connection and try again.",
    );
  }

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    // A 5xx with no FirstFood error body did not come from our API: it is the
    // proxy (or a load balancer) reporting that the backend is unreachable,
    // slow, or crashed - typically a plain-text/HTML page, not JSON. Give it
    // a stable code instead of a misleading "Request failed.".
    const gatewayFailure = response.status >= 500 && !errorBody.code;
    const wait = retryAfterSeconds(response);
    const baseMessage =
      errorBody.message ??
      (gatewayFailure ? "The server is temporarily unreachable. Please try again." : "Request failed.");
    // 429 / 503: tell the person WHEN to try again instead of leaving them to hammer the button.
    const message =
      wait && (response.status === 429 || response.status === 503)
        ? `${baseMessage} Try again in ${waitText(wait)}.`
        : baseMessage;
    throw new ApiError(
      response.status,
      errorBody.code ?? (gatewayFailure ? "SERVICE_UNAVAILABLE" : "UNKNOWN_ERROR"),
      message,
      wait,
    );
  }

  // Not just 204: this API also returns 202 Accepted with an empty body
  // (e.g. the OTP request endpoints) - .json() throws on an empty body,
  // so check for actual content first rather than special-casing 204.
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}
