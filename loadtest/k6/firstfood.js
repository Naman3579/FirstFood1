// FirstFood load test (k6). Run against STAGING, never production.
//
//   k6 run -e BASE_URL=https://staging.example.com \
//          -e TOKENS=tok1,tok2,... \            # valid access tokens (>= 20 accounts recommended)
//          -e OTP_PHONES=+919876500101,...  \   # throwaway numbers whose SMS you do NOT pay for (stub sender!)
//          loadtest/k6/firstfood.js
//
// IMPORTANT: point staging at a stub/capturing OTP sender. A real SMS provider would bill every OTP-burst request.
// Each virtual user should use its OWN account token, otherwise the per-account budgets (60 writes/min) are what
// you end up measuring. Run this from a machine whose IP is allow-listed/uniform, or the per-IP budgets will be.
//
// What it records (compare with docs/OPERATIONS.md SLOs): p50/p95/p99 latency, error rate (excluding the 429s the
// OTP-burst scenario is SUPPOSED to provoke), and that rate limits actually trigger.
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL;
const TOKENS = (__ENV.TOKENS || "").split(",").filter(Boolean);
const PHONES = (__ENV.OTP_PHONES || "").split(",").filter(Boolean);
if (!BASE) throw new Error("BASE_URL is required");

const limited = new Counter("otp_requests_limited_429");
const accepted = new Counter("otp_requests_accepted_202");

export const options = {
  scenarios: {
    // Mostly reads, with a steady trickle of writes: the realistic mix for a mess/tiffin dashboard.
    browse: {
      executor: "ramping-vus", exec: "browse", startVUs: 0,
      stages: [{ duration: "2m", target: 50 }, { duration: "5m", target: 50 }, { duration: "1m", target: 0 }],
    },
    // A login rush (e.g. lunch time): bursts of OTP requests that must be throttled, not allowed to run away.
    otpBurst: {
      executor: "constant-arrival-rate", exec: "otpBurst", rate: 10, timeUnit: "1s",
      duration: "2m", preAllocatedVUs: 20, startTime: "1m",
    },
  },
  thresholds: {
    // Only genuine failures count; 429 from the burst scenario is expected and tracked separately.
    "http_req_failed{scenario:browse}": ["rate<0.01"],
    "http_req_duration{scenario:browse}": ["p(95)<500", "p(99)<1500"],
    "http_req_duration{scenario:otpBurst}": ["p(95)<1000"],
    // The limiter must actually bite under a burst.
    otp_requests_limited_429: ["count>0"],
  },
};

const json = (token) => ({
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
  tags: { name: "api" },
});

export function browse() {
  if (TOKENS.length === 0) throw new Error("TOKENS is required for the browse scenario");
  const token = TOKENS[(__VU - 1) % TOKENS.length];
  const r = http.get(`${BASE}/api/v1/me`, json(token));
  check(r, { "me 200": (x) => x.status === 200 });
  // ~1 write per 20 reads
  if (Math.random() < 0.05) {
    const w = http.patch(`${BASE}/api/v1/me/email`, JSON.stringify({ email: `load${__VU}@example.com` }), json(token));
    check(w, { "write ok or limited": (x) => x.status === 200 || x.status === 429 });
  }
  sleep(1 + Math.random() * 2);
}

export function otpBurst() {
  if (PHONES.length === 0) throw new Error("OTP_PHONES is required for the otpBurst scenario");
  const phone = PHONES[Math.floor(Math.random() * PHONES.length)];
  const r = http.post(`${BASE}/api/v1/auth/otp/request`, JSON.stringify({ phone }), {
    headers: { "Content-Type": "application/json" },
    tags: { name: "otp_request" },
    responseCallback: http.expectedStatuses(202, 429),
  });
  if (r.status === 202) accepted.add(1);
  if (r.status === 429) {
    limited.add(1);
    check(r, { "429 has Retry-After": (x) => !!x.headers["Retry-After"] });
  }
}
