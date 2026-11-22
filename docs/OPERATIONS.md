# FirstFood - operations, abuse protection and scale readiness

Implements the "Scale readiness and next steps" review (10 Oct 2026). Principle: set safe boundaries now, measure,
add capacity when evidence says so. Keep the modular monolith.

## 1. What is enforced in code, and where

| Concern | Where | Behaviour |
|---|---|---|
| OTP SMS per phone | `OtpServiceImpl` -> `RateLimitService` | `app.otp.request-rate-limit-per-hour` (5/h). `+91...` and `91...` share one budget. **Fails closed** (503) if Redis is down. |
| OTP SMS per IP | `IpRateLimitFilter` | burst 5/min and 20/h per client address. Stops phone-rotation. Fails closed. |
| OTP SMS destination (F-05) | `SmsDestinationPolicy`, called from `OtpServiceImpl.requestOtp` (the one door for login, phone-change and owner step-up OTPs) | Only numbers written WITH a country code on `app.sms.allowed-country-codes` (default `91`; India: exactly 10 digits starting 6-9). A bare 10-digit number, or any other country, is `400 PHONE_REGION_NOT_SUPPORTED` and costs nothing. A pure function of the number, so it never reveals whether an account exists. |
| OTP SMS site-wide budget (F-05) | `SmsBudgetGuard` | Circuit breaker shared by every replica through Redis: `app.sms.hourly-budget` (rolling hour, default 60) and `app.sms.daily-budget` (Asia/Kolkata day, default 300). Either spent = `503 SMS_TEMPORARILY_UNAVAILABLE` + `Retry-After` (capped at 1 h) until it reopens. Runs AFTER the per-phone limit. Fails closed if Redis is down. |
| Bot challenge on public OTP request (F-05) | `BotChallengeVerifier` (`TurnstileBotChallengeVerifier`), `AuthController` | Cloudflare Turnstile token in the `X-Challenge-Token` header, verified server-side; single use. Missing/invalid/reused = `400 BOT_CHALLENGE_FAILED`; Cloudflare unreachable = `503 BOT_CHALLENGE_UNAVAILABLE` (fails closed). ON by default in the `production` profile, which will not start without `TURNSTILE_SECRET_KEY`. |
| OTP guessing (F-02, F-09) | `AuthAbuseGuard.guardVerify`, `OtpServiceImpl.check` | Both limits are **atomic increment-then-check**, so parallel guesses cannot overshoot. Per challenge: one conditional SQL `UPDATE` reserves a guess before the code is compared (at most `app.otp.max-attempts` = 5 comparisons, ever); the challenge dies after the last wrong one. Per phone+IP: 5 reservations / 10 min (sliding window, one atomic Redis script); a wrong code keeps its reservation, a success clears it, anything else (no pending code, expired, wrong portal) gives it back. Per phone from anywhere: 10 wrong codes / hour is an alert only, never a block. |
| Token endpoints | filter + guard | 60/min per IP; refresh 10/min per token. |
| General API | `IpRateLimitFilter`, `AccountRateLimitFilter` | 1200/min per IP (generous: NAT/hostel/college); per account 300 reads/min and 60 writes/min. Redis down -> **local in-memory fallback** (still limited, per instance). |
| Counter atomicity | `RedisRateLimitStore` | one Lua script: INCR + PEXPIRE together; a key with no TTL self-repairs on the next hit. Works on Redis, Valkey, Upstash. |
| Sliding window (OTP) | `RateLimitService.SLIDING_WINDOW_POLICIES`, `RedisRateLimitStore.hitSliding` | Every OTP policy (request per phone / IP burst / IP hourly, verify per IP, verify failures) allows at most `limit` hits in ANY `window`-long span, with no fixed-window boundary burst. Redis sorted set under `<key>:sw` (one Lua script: trim, count, record only if room). Denied hits are not recorded, so storage is bounded by `limit`. Other policies stay fixed-window. |
| OTP lock-out rules (F-02) | `AuthAbuseGuard` | Only a WRONG code against a pending challenge counts. Per phone+IP failures block that address only; a fresh OTP request clears it. Per-phone failures across addresses are an ALERT (log + metric), never a block. The per-challenge cap (`app.otp.max-attempts`, 5) protects the code itself. |
| 429 / 503 shape | handler + filters | `{code,message,timestamp}` + `Retry-After`. 503 = "we are degraded", never confused with 429 "you are too fast". |
| Client IP | `ClientIpResolver` | TCP peer only, unless the peer is in `TRUSTED_PROXY_CIDRS`; then `X-Forwarded-For` is read right-to-left skipping trusted hops. IPv6 limited per /64. Literals only - never DNS. |
| Request size | `RequestSizeLimitFilter` | 64 KiB (`app.http.max-request-body-bytes`), 413 before any work; chunked bodies counted as read. |
| Timeouts | `application*.yml`, `BrevoSmsOtpSender` | Tomcat 10 s, Redis 1 s, Hikari 5 s checkout, Postgres `statement_timeout` 15 s, SMS provider 3 s connect / 5 s read -> clean 503 `OTP_DELIVERY_FAILED`. Browser fetch 20 s. |
| API headers | `SecurityConfig` | CSP `default-src 'none'; frame-ancestors 'none'`, nosniff, DENY, no-referrer, no-store. |
| Page headers | `next.config.js` | CSP, nosniff, DENY, Referrer-Policy, Permissions-Policy, HSTS (prod). |
| Refresh token | `RefreshCookies`, `lib/env.ts` | Proxy mode: HttpOnly + Secure + SameSite=Strict cookie scoped to `/api/v1/auth`, opted into with `X-Refresh-Mode: cookie`; exact-Origin allowlist check; session restored on reload; access token auto-renewed. Direct mode / scripts: unchanged body tokens. |
| Sessions (F-11) | `RefreshSessionService`, `TokenService`, `JwtAuthenticationFilter` | One login = one session *family*; the access token carries its id (`sid`) and every request checks the family still has a live refresh row, so logout / revocation end access tokens at once. Replaying an already-rotated refresh token revokes the whole family. Details: "Session hygiene (F-11)" below. |
| Portals (F-12) | `Portal`, `StaffPortalInterceptor`, `CustomerPortalInterceptor`, `LegacyLoginProperties` | A portal is a door, not a boundary: anyone with an OTP can register on either, including staff. Real authorization is the per-provider role check. The old portal-less `POST /auth/otp/verify` no longer creates accounts (production). Details: "Portals are doors, not boundaries (F-12)" below. |
| Observability | `RequestObservabilityFilter`, Micrometer | `X-Request-Id` on every response + log line; one access line per request (route *template*, status, latency, hashed client tag). No bodies, queries, tokens, OTPs, phones or raw IPs. JSON logs in production. |

### Why CSRF stays globally disabled (revisited, as the review required)
The cookie is read by exactly two endpoints (refresh, logout). Everything else authenticates with `Authorization`,
which a cross-site request cannot attach. A forged refresh/logout must beat: `SameSite=Strict`, `Path=/api/v1/auth`,
the custom `X-Refresh-Mode` header (needs a CORS preflight that the exact-origin allowlist refuses), and an explicit
`Origin` check. This relies on `CORS_ALLOWED_ORIGINS` containing the real frontend origin **exactly**.

## 2. Configuration to set in production

| Variable | Notes |
|---|---|
| `JWT_SECRET`, `DATABASE_*`, `REDIS_URL`, `BREVO_API_KEY` | managed secret store; never in the image/repo. `JWT_SECRET` must be **at least 32 random bytes** (`openssl rand -base64 48`); the app refuses to start otherwise (F-17) |
| `CORS_ALLOWED_ORIGINS` | exact frontend origin (also used by the cookie Origin check) |
| `DB_POOL_SIZE` | budget: `instances x pool + workers + admin tools <= DB connection limit` (4 x 20 = 80) |
| `REDIS_PASSWORD` | **required in production Compose** (F-10). Valkey runs with `--requirepass`; the backend gets it through `REDIS_URL=redis://:<password>@redis:6379`, so keep it URL-safe (`openssl rand -hex 32`). Use `rediss://` for a managed Redis reached over an untrusted network |
| `TRUSTED_PROXY_CIDRS` | **required in production** (the app refuses to start without it). The shipped prod Compose sets it to nginx's pinned address `172.28.10.2/32`; empty means "no proxy" and collapses every user into one rate-limit bucket behind nginx (F-01) |
| `TURNSTILE_SECRET_KEY` (backend), `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (frontend **build** arg) | **required in production** (F-05): the two halves of one Cloudflare Turnstile widget. Without the secret the `production` profile refuses to start; to run without the challenge on purpose set `BOT_CHALLENGE_ENABLED=false`. Without the site key the page shows no widget and the CSP does not allow Cloudflare, so the two must be set together. |
| `SMS_ALLOWED_COUNTRY_CODES`, `SMS_HOURLY_BUDGET`, `SMS_DAILY_BUDGET`, `SMS_ALERT_PERCENT` | F-05 spend controls (defaults `91`, 60, 300, 70). Size the budgets from real traffic; see "SMS pumping" below. |
| Frontend build args | `NEXT_PUBLIC_API_BASE_URL=""`, `BACKEND_ORIGIN=<backend origin>` (build time) |

Tuning: every limit is under `app.rate-limit.*` in `application.yml`; override per environment without code changes.
Renaming a policy (`RateLimitPolicies`) resets its counters.

## 3. Ingress isolation - a HOSTING task this repo cannot do for you

Same-origin proxying hides the backend URL; it does **not** make the backend private.

1. Make the backend reachable only from the frontend/proxy (private networking / security group / VPC), plus health checks.
2. From an outside network run `infra/scripts/verify-ingress.sh <frontend-origin> <backend-origin>`. All checks must PASS.
3. Only then set `TRUSTED_PROXY_CIDRS` to the proxy hops **and every hop in front of them** (CDN/LB). Prefer exact addresses over broad ranges: the shipped prod Compose pins the subnet and nginx's address and trusts only `172.28.10.2/32`, so no other container (e.g. a compromised frontend) can pick its own bucket. If a CDN/LB sits in front of nginx, enable `realip` in `nginx/firstfood.conf` so nginx's `$remote_addr` is the real client.
   Next.js appends the socket peer to `X-Forwarded-For`; the right-to-left walk needs every real hop listed as trusted.
4. Verify per-client buckets with `infra/scripts/verify-client-ip.sh` (two source addresses through nginx must not share a budget; run on staging with `APP_RATE_LIMIT_OTP_REQUEST_IP_BURST_LIMIT=1`). Also: from a trusted hop send `X-Forwarded-For: 1.2.3.4` -> ignored if the peer is not trusted; from outside it is ignored too.
   (`ClientIpResolverTest` + `RateLimitSpoofingIntegrationTest` cover the logic; this step covers your network.)
5. If private networking is impossible: put a gateway/WAF in front of the backend and keep these controls anyway.
Also at the edge/CDN: DDoS/WAF baseline, connection and request-size limits, HTTP->HTTPS redirect, HSTS (add
`includeSubDomains` only after all subdomains are HTTPS), and confirm CDN rules do not cache `/api/*`.

## 4. Metrics and alerts

Export Micrometer to your platform (add the registry for it, e.g. Prometheus/Datadog/Grafana Cloud).
| Signal | Source | Alert |
|---|---|---|
| 5xx rate, 4xx rate | `http.server.requests` | 5xx > 1% for 5 min |
| p95 / p99 latency | `http.server.requests` histogram | p95 > 500 ms, p99 > 1.5 s for 10 min |
| Limiter store trouble | `firstfood.ratelimit.decisions{outcome=STORE_ERROR_REJECTED\|FALLBACK_*}` | any sustained > 0 (Redis degraded) |
| Abuse | `...{outcome=LIMITED}` by `policy` | spike vs. baseline (esp. `otp-*`) |
| OTP delivery | `firstfood.otp.delivery.failures` | > 5 in 10 min |
| SMS budget (F-05) | `firstfood.sms.budget.alerts{scope=daily}`, `firstfood.sms.budget.rejected{scope=hourly\|daily}`, WARN "SMS budget alert", ERROR "SMS circuit breaker OPEN" | any `rejected` > 0, or an `alerts` event (70% of the day's budget) = look for SMS pumping |
| Bot challenge (F-05) | `firstfood.botchallenge.results{outcome=missing\|rejected\|unavailable\|passed}` | `unavailable` > 0 (Cloudflare unreachable: nobody can request a code); a surge of `missing`/`rejected` = scripted traffic |
| Refresh-token reuse (F-11) | `firstfood.auth.refresh.reuse`, WARN "Refresh token reuse detected" (session family + account ids) | any > 0 deserves a look; a burst across many accounts = leaked tokens or a client bug |
| DB pool | `hikaricp.connections.pending/active` | pending > 0 for 2 min |
| Jobs | `job_run` table / logs | failed or no run within 2 h |
Alert routing, on-call contact and escalation: **fill in section 7.**

Starter SLOs (revisit after 4 weeks of data): availability 99.5% monthly for login + read flows; p95 < 500 ms.

## 5. Runbook (short)
- **Redis down:** OTP/token routes answer 503 (by design); the rest of the API keeps working under the local fallback. Restore Redis; counters restart empty. Do **not** disable limits to "fix" an outage.
- **SMS cost spike / OTP abuse:** check `LIMITED` by `otp-*` policy; tighten `otp-request-ip-*`; block at the edge.
- **Legit users blocked (shared NAT):** raise `api-ip` / `otp-request-ip-*` for the environment; prefer per-account limits.
- **DB pool pending:** look for slow queries (`statement_timeout` = 15 s) before adding replicas or pool size.
- **Rollback:** redeploy the previous image tag. Frontend direct-mode rollback: rebuild with `NEXT_PUBLIC_API_BASE_URL=<backend url>` (cookie mode switches off automatically; add the origin to `CORS_ALLOWED_ORIGINS`).

## 6. Release gates -> how each is checked
| Gate | Check |
|---|---|
| Same-origin | browser network panel; `proxy-smoke.mjs` (CI) |
| Ingress | `verify-ingress.sh` from outside **(manual, per environment)** |
| Rate limiting | `RateLimit*IntegrationTest`, `RedisRateLimitStoreIntegrationTest` (CI): 429+Retry-After, atomic expiry, parallel hits, rotating IP/phone, spoofed XFF, outage |
| Identity | `RefreshCookieIntegrationTest`, `AuthenticationFlowIntegrationTest`, `SessionFamilyIntegrationTest`, `SessionFamilyGraceIntegrationTest` (F-11); replay/rotation, logout, phone change. Logs: no tokens/OTPs (see section 1) |
| Resilience | outage test; timeouts configured (section 1); **kill-test backend/DB/SMS in staging (manual)** |
| Operations | backups + **tested restore (manual)**, dashboards/alerts (section 4), rollback (section 5) |
| Capacity | `loadtest/k6/firstfood.js` on staging; record p50/p95/p99, errors, CPU/mem, Redis latency, pool use |
| Supply chain | `npm audit` + Dependabot (CI); add a container image scan in your registry/CI |

## 7. Ownership - fill in before launch
| Item | Owner / value |
|---|---|
| Production domains | |
| TLS/DNS owner | |
| Secret storage + rotation owner | |
| Backup owner + last restore test date | |
| On-call / incident contact | |
| Rollback procedure confirmed by | |

## 8. Known limits / next steps (not done here)
- **Fixed-window counters** allow up to 2x the limit across a window boundary. Fine for these budgets; move to a sliding window if abuse justifies it.
- OTP verify uses a fixed lock, not an *escalating* cool-down.
- Page CSP keeps `script-src 'unsafe-inline'`; a nonce-based CSP needs browser verification before enforcing.
- `PHONE_CHANGE` request still reveals "phone already in use" (authenticated flow); the unauthenticated login flow does not.
- Phone numbers are stored in one canonical form, "+" and the digits (F-13). Only the country-agnostic shape is checked; a bare 10-digit number is not guessed into a country (the SMS rule refuses it).
- Session limits (access token checked against its session, revocation on reuse and phone change) are in "Session hygiene (F-11)"; the open points are listed there.
- Metrics export registry, alert rules, WAF, private networking and backups are platform work.


## Network tiers (F-10)

`frontend/deploy/docker-compose.prod.yml` uses three networks: `public` (nginx only, the only published ports), `app`
(nginx, frontend, backend; pinned subnet `172.28.10.0/24`) and `data` (backend, postgres, redis; `internal: true`, so no
route to the internet). The Next.js container is deliberately not on `data`: if the web tier is compromised it cannot reach
Postgres or Redis. Redis requires a password because rate-limit counters, and so login throttling, live there.
`infra/scripts/verify-compose-topology.sh` asserts all of this and runs in CI. The dev Compose (`infra/docker-compose.yml`)
binds every published port to loopback and is for local use only; never reuse its default database password elsewhere.

## Customer enrolment and personal data (F-03)

A provider can no longer add a customer by phone number. `POST .../customers {phone}` creates a PENDING invitation (202)
and nothing else: no membership, no profile, no contact details. The answer is identical whether or not the number has an
account, so it cannot be used to find out who is registered. The customer sees the invitation under `/api/v1/me/invitations`
and only on `accept` does a membership exist; from then on the provider sees name, phone and saved address, and only while the
membership is ACTIVE. `POST /api/v1/me/memberships/{id}/leave` ends it (allowed even if the provider closed) and the provider
stops seeing phone and address. The profile name always comes from the customer, never from staff.
Abuse caps (`application.yml` > `app.membership.*`, `app.provider.*`): invitations per provider per day, providers created per
account per day and owned at once, phone lookups per provider per day for role assignment and ownership transfer.
Not implemented: manual provider vetting/approval before a provider is visible (a product decision), and an acceptance flow
for role assignment and ownership transfer (that is F-08).

## SMS pumping and cost abuse (F-05)

Every OTP request costs an SMS, and the public endpoint needs no account, so it is a money tap. Four layers, in the order a
request meets them:

1. **Bot challenge** (`POST /api/v1/auth/otp/request` only). The browser solves a Cloudflare Turnstile widget and sends the one-time
   token as `X-Challenge-Token`; the backend verifies it with Cloudflare before anything else is spent. A script cannot replay
   a token. Cloudflare down = requests refused (503), not waved through. The authenticated OTP routes (`/me/phone/otp/request`,
   owner step-up) need no challenge: the caller already holds a session.
2. **Destination allow-list** (`SmsDestinationPolicy`). India only for the pilot. Checked for every phone OTP, in the one service
   all of them use, so there is no route that skips it. The frontend mirrors the rule (`isSmsSupportedPhone`) only to give an
   instant message; the server is the authority.
3. **Per-phone and per-IP limits** (already existed; became effective once F-01 fixed the client address): 5 per phone per hour,
   5/min and 20/h per IP.
4. **Site-wide budget** (`SmsBudgetGuard`): the circuit breaker that still holds when an attacker rotates numbers AND addresses.

The trade-off is deliberate and visible: someone willing to burn the budget can switch SMS login off for the rest of the hour or
day. That is a bounded, alarmed outage instead of an unbounded bill. Real traffic never gets near it: the pilot (about 50
customers) sends a few dozen codes a day, against 300.

**Operator steps (not code, so not done by this change):**
- Create a Turnstile widget (Cloudflare dashboard > Turnstile) for your domain. Put the **secret** in `TURNSTILE_SECRET_KEY` and the
  **site key** in `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (a build arg: rebuild the frontend image after changing it).
- In the Brevo account, restrict SMS sending to the countries you serve (check Brevo's SMS settings for country restrictions) so
  that a bug or a misconfigured allow-list here can never reach other countries, and set a spend/credit alert there too.
- Wire alerts for the metrics in section 4 and tell whoever is on call what a `SMS circuit breaker OPEN` log means.
- Before first use, run the staging checks below; they need real Cloudflare and were **not** run from this repo's test environment.

**Staging checks.** Cloudflare publishes test keys: site key `1x00000000000000000000AA` with secret `1x0000000000000000000000000000000AA`
always passes; secret `2x0000000000000000000000000000000AA` always fails. With those:
1. Open the sign-in page: a widget appears, "Send code" stays disabled until it finishes, then a code is sent.
2. `curl -X POST .../api/v1/auth/otp/request -d '{"phone":"+919876543210"}' -H 'Content-Type: application/json'` with no token: `400 BOT_CHALLENGE_FAILED`.
3. Enter a non-Indian number: refused on the page, and by the API with `400 PHONE_REGION_NOT_SUPPORTED`, with no SMS sent.
4. Set `SMS_HOURLY_BUDGET=3`, request codes for four different Indian numbers: the fourth is `503 SMS_TEMPORARILY_UNAVAILABLE` with `Retry-After`.
5. Check the browser console shows no Content-Security-Policy violation while the widget loads (the CSP allows only
   `challenges.cloudflare.com` for scripts and frames, and only when a site key is configured).

**Adding a country later:** add its calling code to `SMS_ALLOWED_COUNTRY_CODES`, allow it in Brevo, and widen `isSmsSupportedPhone`
in `frontend/lib/format.ts` (and its message). Other countries get only a loose length check; only India has a number-shape rule.

Not covered: per-recipient cost differences (one budget counts messages, not rupees), SMS toll fraud that arrives through a
legitimate Indian number, and a Turnstile outage (which blocks all new code requests until Cloudflare returns or the operator sets
`BOT_CHALLENGE_ENABLED=false`).

## Capacity squatting through unpaid self-service subscriptions (F-06)

A customer's own purchase is stored ACTIVE with a PENDING verification and immediately holds a seat. Nothing required payment and nothing expired it, so a few throwaway accounts could fill a small provider.

**Three layers**
1. **Capacity counts only real or fresh requests** - `maxActiveSubscriptions` counts VERIFIED subscriptions plus PENDING ones requested within `app.subscription.pending-ttl`. A stale request stops holding a seat the moment it goes stale, even before the job runs.
2. **Per-account cap** - a customer may have at most `app.subscription.max-pending-per-account` fresh unconfirmed requests (default 3). The 4th gets `409 TOO_MANY_PENDING_SUBSCRIPTIONS`. Staff-sold subscriptions are never limited.
3. **Hourly expiry job** - `pending-subscription-expiry` (`app.jobs.pending-expiry-cron`, default `0 10 * * * *`) closes stale PENDING requests: verification becomes EXPIRED (a system decision, no staff member, no reason) and the subscription is CANCELLED in the same transaction, which frees its dates.

**Configuration**

| Env var | Default | Meaning |
|---|---|---|
| `SUBSCRIPTION_PENDING_TTL` | `48h` | How long staff have to confirm (minimum 1h) |
| `SUBSCRIPTION_MAX_PENDING_PER_ACCOUNT` | `3` | Open requests per customer (minimum 1) |

Migration `V24` adds the EXPIRED outcome and an index for the per-account count. Pending requests carry `expiresAt` in the API so the UI can say when they lapse.

**Watch**: job metrics for `pending-subscription-expiry` (changed / failed). A steady high "changed" count at one provider means someone is squatting: look at the requesting accounts. Staff can still reject by hand at any time.

**Known limits**
- The per-account cap is a soft limit across providers (a race can overshoot by a request), and many accounts can still re-request every 48h. This raises the cost of squatting; it does not prove payment. Real protection is requiring payment before a seat is held.
- A customer's own stale request can block re-buying the same dates for up to about an hour, until the job closes it.
- Verified meal-based plans never lapse (meal consumption is not built yet).
- If staff cancel a pending subscription, its verification stays PENDING until the job closes it as EXPIRED.
- The auto-cancel is recorded in the customer's name, because the schema requires `cancelled_by`; the reason text says it was automatic.

## OTP guess limits under parallel requests (F-09)

**Was:** the per-OTP attempt counter was read, incremented in memory and saved, and the phone+IP limiter peeked first and counted after the code was checked. N parallel guesses could all pass both checks and overwrite each other's increments, so the cap of 5 was soft.

**Now:**
- `OtpVerificationRepository.reserveAttempt` takes a guess in one conditional `UPDATE ... SET attempt_count = attempt_count + 1 WHERE status = 'PENDING' AND expires_at > :now AND attempt_count < :max` BEFORE the hash is compared. The database serialises competing updates on the row, so at most `max-attempts` requests ever get to compare. `expireIfExhausted` then kills the challenge after the last wrong guess (the status is not flipped at reservation, so the last allowed guess can still be the right one). A correct code that was only *checked* (`checkOtp`, used before the portal check in sign-in/sign-up) gets its guess back (`refundAttempt`).
- `AuthAbuseGuard.guardVerify` reserves a phone+IP hit with one atomic sliding-window script, runs the verification, then keeps (wrong code), clears (success) or releases (any other outcome) the reservation. `RateLimitStore.releaseSliding` removes the newest hit atomically (`ZPOPMAX`). There is no peek-then-hit left.
- The old `OtpVerification.recordFailedAttempt`, `assertVerifyAllowed`, `recordVerifyFailure` and `recordVerifySuccess` are gone; new code must go through `guardVerify` / `guardVerifyRun`.

**Check on staging:** fire 20 parallel `POST /api/v1/auth/customer/signin` with the same wrong code for a fresh OTP. Expect exactly 5 `INVALID_OTP`, the rest `OTP_EXPIRED` (or 429 once the address budget is spent), and `select attempt_count, status from otp_verification` = `5, EXPIRED`.

**Known limits**
- `StepUpServiceImpl` (owner step-up) has no phone+IP limiter, only the per-challenge cap, which is now hard.
- A release is best effort: if Redis fails between reservation and release, the address keeps one extra hit (the safe direction).
- The row lock is held while the hash is compared, so parallel guesses at ONE challenge are serialised (about one bcrypt each). That is intended; other challenges are unaffected.

## Session hygiene (F-11)

**Was:** (1) replaying a rotated refresh token only failed, so a thief who rotated first kept a working session while the real user was signed out; (2) changing the phone number left every existing session alive; (3) logout revoked the refresh token but the access token stayed valid for up to 15 minutes.

**Now:**
- `V26` adds `refresh_session.family_id` (one login = one family; rotation keeps it) and `revoked_reason` (`ROTATED`, `LOGOUT`, `REUSE`, `PHONE_CHANGE`, `LEGACY`).
- **Reuse = theft.** `RefreshSessionService.rotate` revokes the whole family when a `ROTATED` token is presented again (reason `REUSE`), commits that despite the 401, logs a WARN with the family and account ids and counts `firstfood.auth.refresh.reuse`. Replaying a logged-out token changes nothing.
- **Grace window.** A replay within `app.jwt.refresh-reuse-grace-seconds` (default 10; 0 = strict) of the rotation is only refused. This is for two browser tabs sharing the cookie that refresh in the same moment: the slower one gets a 401, the session survives.
- **Access tokens follow the session.** Access tokens carry `sid` (the family id). `JwtAuthenticationFilter` accepts a token only if that family still has an unexpired, unrevoked row (one indexed read, partial index `idx_refresh_session_family_live`). Logout, phone change and theft handling therefore take effect on the next request. Tokens without `sid` (issued before this change) are unauthenticated; the client's normal 401 -> refresh gets a new one, so nobody has to sign in again at deploy time.
- **Phone change** (`verifyPhoneChangeOtp`) revokes every OTHER session of the account (reason `PHONE_CHANGE`); the session that made the change stays so the user is not bounced on the device they used.
- **Logout** revokes the whole family (not just the presented row).
- **Concurrency.** Rotate, logout and revoke-others first take one transaction-scoped Postgres advisory lock per account, so "revoke the family" cannot run in the gap while a rotation inserts its replacement row (which the revoking statement would not see).

**Check on staging:** sign in; copy the refresh cookie; refresh once from a script (keep the new token); wait 15 s; refresh again with the COPIED token (expect 401); then call any API with the script's new access token (expect 401) and refresh with its new refresh token (expect 401). Also: sign in on two browsers, change the number on one, expect the other to get 401 on its next call. Logout, then reuse the old access token: 401 immediately.

**Known limits / decisions to confirm**
- Phone change keeps the caller's own session (see above). If you prefer "everyone signs in again", pass a random UUID as the kept family in `MeController`.
- Report item "revoke all sessions ... when an owner-level action is taken from a new session" is NOT implemented. Owner-level actions already need a fresh step-up OTP and notify the old owner (F-08); a "new session" has no definition in the data model yet. Say what you want it to mean (for example: block owner actions for N minutes after sign-in, or revoke other sessions on ownership transfer) and it is a small change on top of `revokeOtherSessions`.
- Email change does not revoke sessions (the report only names phone change).
- Theft is only detectable while the rotated row exists: `session-retention` (7 days) deletes dead rows, after which a replay of a very old token just fails.
- The grace window means a thief who replays within 10 s of the user's rotation is not detected (the replay still fails and gets no session).
- The per-request session check adds one indexed query on top of the existing account read; at pilot scale this is noise. If it ever shows up, cache `isSessionLive` in Redis for a few seconds (and accept that logout then lags by that much).


## Portals are doors, not boundaries (F-12)

**Was:** the report's point is that "staff portal" sounds like a trust level but is not one. Anyone can sign up on `/staff` with just an OTP, and the legacy `POST /api/v1/auth/otp/verify` registered a brand-new number on BOTH portals in one call. `StaffPortalInterceptor` therefore only keeps the two UIs' APIs apart; it says nothing about who the caller is.

**What was already true (checked in this pass):** nothing sensitive depends on portal membership. `hasPortal` / `Portal.STAFF` / `Portal.CUSTOMER` are read only by the two interceptors and the account/registration code. Every provider-scoped service calls `ProviderAccessService.requirePermission`, which answers 404 `PROVIDER_NOT_FOUND` for a non-member exactly as for a missing provider; customer-side lookups are scoped to the caller's own person ids.

**Now:**
- **Legacy login no longer mints accounts.** `app.auth.legacy-login.register-new-accounts` (`LegacyLoginProperties`): `false` in `application.yml` and PINNED `false` in `application-production.yml` (no environment override); `true` in `application-local.yml` and `application-test.yml` as a one-call login for curl experiments and the test suite. With it off, a code that proves an unregistered number gets 404 `ACCOUNT_NOT_REGISTERED` and is NOT spent, so the person can sign up on the portal they actually want and gets only that portal. The code is checked first (`checkOtp`), so someone without the code sees the same 401 for known and unknown numbers: no registration oracle. Existing accounts sign in exactly as before.
- **The rule is written down where people will see it:** `Portal`, both interceptors and `AccountLookupService.hasPortal` now say "UI door, never an authorization signal".
- **The rule is enforced:** `PortalUsageGuardTest` fails if any source file other than the interceptors and the account/registration code mentions `hasPortal(`, `Portal.STAFF` or `Portal.CUSTOMER`. A new caller has to justify itself (is it a role, ownership or membership decision instead?).
- **Tests (new, unrun):** `LegacyLoginClosedIntegrationTest` (unknown number refused and code not spent, then staff sign-up with the same code yields STAFF only; wrong code looks the same for known and unknown numbers; existing account still signs in), `PortalBoundaryIntegrationTest` (a throwaway staff-portal account gets the staff API but, for another owner's provider, the same 404 (code and message) as for a nonexistent provider on the provider, customers and invitations routes), `PortalUsageGuardTest`.

**Check on staging:** `POST /api/v1/auth/otp/request` for a number with no account, then `POST /api/v1/auth/otp/verify` with the code: expect 404 `ACCOUNT_NOT_REGISTERED`; then `POST /api/v1/auth/staff/signup` with the SAME code: expect 200 and `GET /api/v1/me` shows `portals: ["STAFF"]`.

**Known limits / decisions to confirm**
- Staff sign-up is still open to anyone with a phone. That is a product decision (provider vetting is listed as not implemented under F-03), and F-03's caps (providers per account, invitations per day) are what limit abuse; the portal does not.
- The customer portal's own API still rejects a staff-only account (`PORTAL_ACCESS_DENIED`). That is door-keeping for the two UIs, not protection; keep it or drop it as you like.
- If you want the legacy endpoint gone entirely, delete `AuthController.verifyOtp` and move the test helpers to `/auth/{portal}/signup`; ~20 test classes use it as a login shortcut today, which is why it is a flag and not a deletion.
- F-14 (the portal gate misses `PUT /me/person/address`) is a separate item and is not part of this change.

## One canonical phone number (F-13)

**Was:** `+919876543210` and `919876543210` were different strings, so one SIM could own two accounts. Staff invitations and role assignment tried both spellings and took the first match; the rate limiter already treated them as one number, the data did not.

**Now:** the stored form is `+` followed by the digits, everywhere.
- `PhonePattern.addPlus` (a bare run of digits gets its `+`; anything else is returned untouched so validation behaves as before) is applied in the five login-side request DTOs, in `AuthenticationServiceImpl` (request, verify, sign-in, sign-up, phone change), in `OtpServiceImpl` (so the stored challenge and the SMS use the canonical number) and in `UserAccount` (constructor and `setPhone`, the last line of defence). `PhonePattern.canonical` (also strips spaces and hyphens; null if not a phone) serves staff-typed numbers and replaces the two private copies in `membership` and `provideraccess`.
- `AccountLookupServiceImpl` does one lookup instead of "this spelling, then the other".
- **`V27`** rewrites existing `user_account.phone` rows, adds `CHECK (phone ~ '^\+[1-9][0-9]{9,14}$')` and prefixes pending OTP rows (email codes are left alone). **It aborts with a readable message** if two accounts exist for one number (one with `+`, one without), or if any phone is not 10-15 digits. Those need a human decision (which account keeps the history), so nothing is merged automatically.
- Spaces and hyphens are still rejected on the login side (400), as before; only staff-typed lookups strip them.

**Before deploying V27**, run on the production database: `select ltrim(phone,'+') k, array_agg(id) from user_account group by 1 having count(*) > 1;` - any row is a pair to resolve first - and `select id, phone from user_account where phone !~ '^\+?[1-9][0-9]{9,14}$';`.

**Check on staging:** sign up on the customer portal typing the number without `+`; sign in typing it with `+`; both give the same `GET /api/v1/me` `accountId`, and `phone` shows the `+` form. Requesting a phone change to the other spelling of an existing number gives 409 `PHONE_ALREADY_IN_USE`.

**Known limits**
- Not full E.164 validation (no per-country length or numbering plan except India's SMS rule); a library such as libphonenumber is the next step if non-Indian numbers are ever allowed.
- `provider.contact_phone` is a display field typed by the provider and is NOT rewritten.
- A number typed with a trunk prefix (`0`...) is rejected by the pattern, not converted.
- A user who changes phone keeps their sessions' identity (F-11); nothing here changes that.

## Every route behind exactly one door (F-14)

**Was:** the report found `CustomerPortalInterceptor` registered for `/api/v1/me/person` and `/api/v1/me/memberships` only, so `PUT /api/v1/me/person/address` was not gated.

**State on arrival:** the registration already used `/api/v1/me/person/**`, `/me/memberships/**` and `/me/invitations/**` (fixed in an earlier pass), but nothing proved it and nothing would catch the next route that missed a pattern. This pass:
- moves the three lists into `PortalPaths` (`STAFF`, `CUSTOMER`, and `SHARED` for the public `/version`, sign-in and the account routes both portals use: `/auth/**`, `/me`, `/me/email/**`, `/me/phone/**`); `WebConfig` registers the interceptors from them;
- `PortalRouteCoverageTest` reads every mapped `/api/**` route from Spring and fails if one is in zero or more than one list - so a new controller with an unclassified URL breaks the build instead of shipping ungated;
- `CustomerPortalGateIntegrationTest`: a staff-only account gets 403 `PORTAL_ACCESS_DENIED` on all eight customer-side `/me` routes (including `PUT /me/person/address`), still reaches `GET /me`, and a customer account gets past the gate.

Behaviour is unchanged for users. As with F-12, the gate is door-keeping, not authorization.

**Check on staging:** sign up on `/staff` only, then `PUT /api/v1/me/person/address` with that token: expect 403 `PORTAL_ACCESS_DENIED`.

**Known limits**
- The coverage test reads routes from `RequestMappingHandlerMapping`; it does not see actuator endpoints or non-`/api/` paths (`/error`), which have their own rules in the security config.
- It checks that a route is classified, not that the classification is right: a customer-only route added under `SHARED` would pass.

## Email update validation (F-15)

**Was:** `PATCH /api/v1/me/email` bound `UpdateEmailRequest(@Email String email)`: no length limit and no required check. An address over 255 characters passed validation, hit `user_account.email varchar(255)` and came back as a 500; a null body field passed `@Email` and would have cleared the stored address.

**Now:** `@NotBlank @Email @Size(max = 255)`, identical to `EmailChangeRequest` (the OTP-gated flow). A bad value is a 400 `VALIDATION_ERROR` and nothing is stored. Behaviour for valid input is unchanged: the address is stored as typed and marked unverified (the proven path is `/me/email/otp/*`).

**Check on staging:** `PATCH /api/v1/me/email` with a 256-character address: expect 400, not 500. With `{"email":""}` or `{}`: expect 400 and the old address still on `GET /api/v1/me`.

**Known limits**
- The address is not lower-cased or de-duplicated on this route (the verified flow lower-cases); unverified addresses are display data only and nothing keys on them.
- The web UI does not call this route (it uses the verified flow), so no frontend change was needed.

## Edge and header consistency (F-16)

**Was:** (1) nginx sent `Strict-Transport-Security: max-age=31536000; includeSubDomains` while `next.config.js` deliberately sent `max-age=15552000` without it, so a browser saw two conflicting policies and only the first counts. (2) The tight OTP rate limit covered only `POST /api/v1/auth/otp/request`; the phone-change, email-change and owner step-up code requests (all of which send an SMS or email) fell into the general API zone. (3) nginx advertised its version. (4) The frontend image copied the whole build `node_modules`, dev dependencies included.

**Now**
- **HSTS:** one policy, `max-age=31536000`, **no `includeSubDomains`/`preload`** (the choice `next.config.js` already documented: add them only after every subdomain is confirmed HTTPS-ready, because it cannot be undone quickly). nginx owns it and `proxy_hide_header`s any upstream copy; the Next.js value is the identical fallback for when the app is reached without nginx. If you later want `includeSubDomains`, change both places (the CI check fails if they differ).
- **OTP zone (`fp_otp`, 6/min, burst 3):** `/api/v1/auth/otp/request`, `/api/v1/me/phone/otp/request`, `/api/v1/me/email/otp/request` and `/api/v1/(staff/)providers/{id}/owner-step-up`. The zone is per client IP and shared, so one person doing a login code then an email-change code within a minute can see a 429 from nginx; the backend per-phone/per-account limits are unchanged.
- **`server_tokens off`.**
- **Frontend image:** the runtime stage runs `npm ci --omit=dev` from the lockfile instead of copying the build stage's `node_modules`.
- **`infra/scripts/verify-edge-config.sh`** (CI job `edge-config`) checks all of the above statically.

**Check on staging:** `curl -sI https://<host>/` shows exactly one `Strict-Transport-Security` header with no `includeSubDomains` and `Server: nginx` with no version; request `POST /api/v1/me/email/otp/request` four times within a minute from one address: the fifth gets 429 from nginx. In the frontend container, `ls node_modules | grep -c eslint` is 0.

**Left as is, on purpose**
- **CSP `script-src 'unsafe-inline'`:** still there. Next.js injects inline hydration scripts, so a nonce-based policy needs per-request nonces (dynamic rendering) and testing in a real browser before it is enforced. It remains the follow-up listed under "Page headers".
- Other authenticated routes that send nothing (verify endpoints) stay in the general zones.

## JWT secret, claims and local defaults (F-17)

**Was:** `application-local.yml` hardcoded a JWT secret, so `SPRING_PROFILES_ACTIVE=local` on a real server made every token forgeable by anyone who had read the repository. Local and test also ship `firstfood`/`firstfood` database credentials. Access tokens had no `iss`/`aud`, so a token from any other system sharing the secret would have been accepted, and nothing checked the strength of the production secret.

**Now**
- **No committed secret.** The `local` profile generates a random 256-bit secret on every start (`${LOCAL_JWT_SECRET:${random.value}${random.value}}`); `JWT_SECRET` is ignored there. Consequence: restarting a local backend signs everyone out; set `LOCAL_JWT_SECRET` (32+ random bytes) in your own environment to keep sessions. The test profile's fixed throwaway secret moved from `src/main` to `src/test/resources/application-test.yml`, so it is no longer in the deployable jar.
- **Start-up policy (`JwtSecretPolicy`, enforced in `TokenService`'s constructor):** at least 32 bytes; at least 12 distinct characters (rejects `aaaa...` and `changeme` padding; a sanity check, not an entropy proof); never the retired local value (it is public in version control); the test value only under the `test` profile; `production` together with `local` or `test` is refused. All problems are listed in one message and the secret is never printed.
- **`LocalDefaultsGuard`:** `production` with the development database password (`firstfood`) refuses to start.
- **`iss` and `aud`:** every access token carries `iss` = `app.jwt.issuer` (default `firstfood`) and `aud` = `app.jwt.audience` (default `firstfood-api`), and `TokenService.parse` requires both. Give staging and production different audiences so a token from one is useless on the other even if the secret were shared.
- **Rollout:** access tokens issued before this change lack `iss`/`aud`, so they are unauthenticated after deploy; clients' normal 401 -> refresh obtains a new one, so nobody signs in again (same mechanism as F-11). **Deploy check:** if the current production `JWT_SECRET` is shorter than 32 bytes or repetitive, the new version will not start. Rotate it first (that signs everyone out once) or set a new one at the same time as deploying.

**Check on staging:** start with `JWT_SECRET=short` -> the container exits with "Refusing to start: unsafe JWT configuration". Start with `SPRING_PROFILES_ACTIVE=production,local` -> refused. Take an access token, change `APP_JWT_AUDIENCE` on the server, restart: the old token is a 401 on `GET /api/v1/me`.

**Known limits**
- Symmetric HS256: anyone who holds the secret can mint tokens. Rotation is a restart with a new secret (all sessions end); there is no key-id / dual-key rotation yet.
- The local profile still uses `firstfood`/`firstfood` for its own throwaway database, on purpose; the guard only protects `production`. Do not expose the local compose stack.
- Entropy is not measured beyond the length and distinct-character checks; generate the value, do not type it.

