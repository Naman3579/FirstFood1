#!/usr/bin/env bash
# F-01 smoke test: two clients with DIFFERENT source addresses, going through nginx, must get SEPARATE per-IP
# rate-limit buckets. If TRUSTED_PROXY_CIDRS is wrong, the backend sees nginx's address for everyone and the second
# client is throttled because of the first.
#
# Run against STAGING only (it requests OTPs). Start the backend with the OTP burst cap lowered to 1 so the check is
# fast and unambiguous, and use a console/test SMS sender so no real SMS is sent:
#
#   APP_RATE_LIMIT_OTP_REQUEST_IP_BURST_LIMIT=1
#
#   infra/scripts/verify-client-ip.sh <compose-network> <nginx-ip> <host>
#   infra/scripts/verify-client-ip.sh deploy_app 172.28.10.2 yourdomain.com
#
# Needs docker. Source addresses must be free addresses inside the pinned subnet (172.28.10.0/24).
set -u
NET="${1:?usage: $0 <compose-network> <nginx-ip> <host>}"
NGINX_IP="${2:?}"
HOST="${3:?}"
IP_A="${CLIENT_A_IP:-172.28.10.201}"
IP_B="${CLIENT_B_IP:-172.28.10.202}"
fail=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; fail=1; }

otp() { # <source-ip> <phone> -> http status
  docker run --rm --network "$NET" --ip "$1" curlimages/curl:8.10.1 -sk -o /dev/null -m 10 -w '%{http_code}' \
    --resolve "$HOST:443:$NGINX_IP" -X POST "https://$HOST/api/v1/auth/otp/request" \
    -H 'Content-Type: application/json' -d "{\"phone\":\"$2\"}" 2>/dev/null || true
}

# Distinct phones so the per-PHONE limit never interferes; only the per-IP limit can cause a 429 here.
a1=$(otp "$IP_A" +919876500101)
a2=$(otp "$IP_A" +919876500102)
b1=$(otp "$IP_B" +919876500103)

[ "$a1" = 202 ] && ok "client A first request accepted ($a1)"           || bad "client A first request -> $a1 (expected 202)"
[ "$a2" = 429 ] && ok "client A second request limited ($a2)"            || bad "client A second request -> $a2 (expected 429; is the burst limit set to 1?)"
[ "$b1" = 202 ] && ok "client B unaffected by A's limit ($b1): separate buckets" \
                || bad "client B -> $b1 (expected 202). Buckets are shared: check TRUSTED_PROXY_CIDRS and the nginx address"
exit $fail
