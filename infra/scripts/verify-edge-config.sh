#!/usr/bin/env bash
# F-16 static checks for the edge and the frontend image. Reads files only (nothing is started); used by CI.
# Run from the repo root:  infra/scripts/verify-edge-config.sh
set -eu
python3 - <<'PY'
import re, sys
conf = open("frontend/deploy/nginx/firstfood.conf").read()
nxt = open("frontend/next.config.js").read()
dock = open("frontend/Dockerfile").read()
fail = []
def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg)
    if not ok:
        fail.append(msg)

code = re.sub(r"(?m)^\s*#.*$", "", conf)          # ignore comments

# 1. server_tokens off
check(re.search(r"(?m)^\s*server_tokens\s+off;", code) is not None, "nginx: server_tokens off")

# 2. one HSTS policy, same in nginx and next.config.js, no includeSubDomains
m = re.search(r'add_header\s+Strict-Transport-Security\s+"([^"]+)"', code)
n = re.search(r'key:\s*"Strict-Transport-Security",\s*value:\s*"([^"]+)"', nxt)
check(m is not None and n is not None, "HSTS is set in nginx and in next.config.js")
if m and n:
    check(m.group(1) == n.group(1), f"HSTS values match (nginx '{m.group(1)}', next '{n.group(1)}')")
    check("includesubdomains" not in m.group(1).lower(), "HSTS has no includeSubDomains")
check("proxy_hide_header Strict-Transport-Security" in code, "nginx hides upstream HSTS (no duplicate policies)")

# 3. every code-sending route is in the tight OTP zone
loc = re.search(r"location\s+~\s+(\S+)\s*\{[^}]*zone=fp_otp", code)
check(loc is not None, "nginx: a regex location uses the tight fp_otp zone")
if loc:
    rx = re.compile(loc.group(1))
    for path in ["/api/v1/auth/otp/request", "/api/v1/me/phone/otp/request", "/api/v1/me/email/otp/request",
                 "/api/v1/providers/3f2c/owner-step-up", "/api/v1/staff/providers/3f2c/owner-step-up"]:
        check(rx.match(path) is not None, f"fp_otp covers {path}")
    for path in ["/api/v1/auth/signin", "/api/v1/me/phone/otp/verify", "/api/v1/providers/3f2c/roles"]:
        check(rx.match(path) is None, f"fp_otp does not cover {path}")

# 4. runtime image carries production dependencies only
runtime = dock.split("# --- Runtime stage ---")[1]
check("npm ci --omit=dev" in runtime, "Dockerfile runtime stage installs with npm ci --omit=dev")
check(not re.search(r"COPY\s+--from=build\s+\S*node_modules", runtime), "Dockerfile runtime stage does not copy the build node_modules")

sys.exit(1 if fail else 0)
PY
