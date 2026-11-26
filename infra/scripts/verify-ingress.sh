#!/usr/bin/env bash
# Verifies the deployed ingress boundary from OUTSIDE your network (run it from a laptop on mobile data, or any
# machine that is not inside the private network / VPC).
#
#   infra/scripts/verify-ingress.sh https://app.example.com https://api-internal.example.com
#                                   ^ public frontend origin ^ backend origin (the BACKEND_ORIGIN value)
#
# PASS for the backend means: the public internet cannot call it (connection refused / timeout / 403 / 404 from a
# gateway). A 200 on /api/v1/version from the backend host means the backend is publicly reachable and the
# same-origin proxy is NOT protecting it.
set -u
FRONT="${1:?usage: $0 <frontend-origin> <backend-origin>}"
BACK="${2:?usage: $0 <frontend-origin> <backend-origin>}"
fail=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; fail=1; }
code() { curl -s -o /dev/null -m 8 -w '%{http_code}' "$@" 2>/dev/null || true; }

echo "== Backend must NOT be reachable directly =="
for path in /api/v1/version /actuator/health /api/v1/auth/otp/request; do
  c=$(code -X "$([ "$path" = /api/v1/auth/otp/request ] && echo POST || echo GET)" "$BACK$path")
  if [[ "$c" =~ ^(000|401|403|404|421|502|503|504)$ ]]; then ok "backend $path -> ${c:-no response}"; else bad "backend $path answered $c from the public internet"; fi
done

echo "== Frontend origin must expose only /api/v1 =="
for path in /actuator/health /actuator/info /api/other /internal; do
  c=$(code "$FRONT$path"); [ "$c" = 404 ] && ok "$path -> 404" || bad "$path -> $c (expected 404)"
done
c=$(code "$FRONT/api/v1/version"); [ "$c" = 200 ] && ok "/api/v1/version proxied (200)" || bad "/api/v1/version -> $c"

echo "== Headers =="
h=$(curl -sI -m 8 "$FRONT/")
for name in content-security-policy x-content-type-options x-frame-options referrer-policy strict-transport-security; do
  grep -qi "^$name:" <<<"$h" && ok "$name present" || bad "$name missing"
done
cc=$(curl -sI -m 8 "$FRONT/api/v1/version" | grep -i '^cache-control:')
grep -qi 'no-store' <<<"$cc" && ok "API responses are no-store" || bad "API responses not no-store ($cc)"

echo "== Backend origin must not leak into pages =="
host=$(sed -E 's#^[a-z]+://([^/:]+).*#\1#' <<<"$BACK")
if curl -s -m 8 "$FRONT/" | grep -qiF "$host"; then bad "backend host '$host' appears in the frontend HTML"; else ok "backend host not present in page HTML"; fi

echo "== HTTP must redirect to HTTPS =="
httpfront="http://${FRONT#https://}"
loc=$(curl -sI -m 8 "$httpfront/" | grep -i '^location:' || true)
grep -qi 'https://' <<<"$loc" && ok "http -> https redirect" || bad "no http->https redirect ($loc)"

exit $fail
