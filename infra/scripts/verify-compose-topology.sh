#!/usr/bin/env bash
# F-10 start-up/topology test for the production Compose file. Renders it with dummy secrets (nothing is started) and
# asserts the tier separation the security review requires. Run from the repo root; used by CI.
#
#   infra/scripts/verify-compose-topology.sh [path/to/docker-compose.prod.yml]
set -eu
FILE="${1:-frontend/deploy/docker-compose.prod.yml}"

POSTGRES_PASSWORD=dummy REDIS_PASSWORD=dummy JWT_SECRET=dummy BREVO_API_KEY=dummy BREVO_SENDER_EMAIL=a@example.com \
  docker compose -f "$FILE" config --format json > /tmp/firstfood-compose.json

python3 - /tmp/firstfood-compose.json <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
svc, nets = cfg["services"], cfg["networks"]
fail = []
def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg)
    if not ok:
        fail.append(msg)

def on(name):
    return set((svc[name].get("networks") or {}).keys())

check(on("postgres") == {"data"}, f"postgres is on the data network only ({sorted(on('postgres'))})")
check(on("redis") == {"data"}, f"redis is on the data network only ({sorted(on('redis'))})")
check("data" not in on("frontend"), f"frontend cannot reach the data tier ({sorted(on('frontend'))})")
check(on("backend") == {"app", "data"}, f"backend bridges app and data ({sorted(on('backend'))})")
check("public" in on("nginx") and "app" in on("nginx") and "data" not in on("nginx"),
      f"nginx is on public + app, not data ({sorted(on('nginx'))})")
check(nets["data"].get("internal") is True, "data network is internal (no internet route)")
published = [n for n, s in svc.items() if s.get("ports")]
check(published == ["nginx"], f"only nginx publishes ports ({published})")
cmd = " ".join(svc["redis"].get("command", []))
check("--requirepass" in cmd, "redis requires a password")
check("@redis" in svc["backend"]["environment"].get("REDIS_URL", ""), "backend REDIS_URL carries credentials")
# The stack must actually build: each service's context has to be the right app (a wrong context starts nothing).
import os
def ctx(name):
    b = svc[name].get("build")
    return b.get("context") if isinstance(b, dict) else b
check(os.path.isfile(os.path.join(ctx("backend") or "", "pom.xml")), f"backend builds from the Spring Boot app ({ctx('backend')})")
check(os.path.isfile(os.path.join(ctx("frontend") or "", "package.json")), f"frontend builds from the Next.js app ({ctx('frontend')})")
sys.exit(1 if fail else 0)
PY
