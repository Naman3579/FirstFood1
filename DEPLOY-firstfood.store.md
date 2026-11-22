# Deploying FirstFood to firstfood.store (single VPS + Docker Compose)

## 0. What you need
- A Linux VPS (Ubuntu 22.04/24.04, 2 GB RAM minimum, 4 GB better: Spring Boot + Next build) with a public IPv4.
  NOTE: shared/cPanel "web hosting" cannot run this stack (Java 25 + Postgres + Redis + Docker). You need a VPS.
- Domain firstfood.store (bought).
- Brevo account (a verified sender email is enough for now; SMS needs DLT registration in India, so phone sign-in is off) and a Cloudflare Turnstile widget for firstfood.store.

## 1. DNS (at your domain registrar)
| Type | Name | Value |
|---|---|---|
| A | @   | <VPS public IP> |
| A | www | <VPS public IP> |
Wait until `dig +short firstfood.store` returns the IP.

## 2. Server setup (SSH in as root / sudo user)
    apt update && apt install -y docker.io docker-compose-v2 git certbot ufw
    ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
    mkdir -p /var/www/certbot

## 3. Get the code on the server
    git clone <your-repo-url> /opt/firstfood      # or scp/unzip the project
    cd /opt/firstfood/frontend/deploy

## 4. Secrets
    cp .env.example .env && chmod 600 .env && nano .env
Fill every value (generation commands are in the file).

## 5. TLS certificate (first time only)
Port 80 must be free, so do this BEFORE starting compose:
    certbot certonly --standalone -d firstfood.store -d www.firstfood.store -m you@email.com --agree-tos
Auto-renew uses the webroot, so after step 6 switch it:
    certbot renew --dry-run   # if it fails with standalone, run:
    certbot certonly --webroot -w /var/www/certbot -d firstfood.store -d www.firstfood.store --force-renewal
Add cron: `0 3 * * * certbot renew -q --deploy-hook "docker exec $(docker ps -qf name=nginx) nginx -s reload"`

## 6. Launch
    docker compose -f docker-compose.prod.yml --env-file .env up -d --build
    docker compose -f docker-compose.prod.yml ps
    docker compose -f docker-compose.prod.yml logs -f backend    # wait for "Started ..."
Flyway creates the database schema automatically on first start.

## 7. Verify
    curl -sI https://firstfood.store/ | head
Open https://firstfood.store and https://firstfood.store/staff, enter your email, and sign up with the emailed code.
Phone sign-in stays off until SMS/DLT is ready. Until then users can add a phone number in Account without a code; it is saved as "not verified" and cannot sign in or receive staff roles.
When DLT is done: set NEXT_PUBLIC_PHONE_OTP_ENABLED=true and PHONE_VERIFICATION_REQUIRED=true in .env and rebuild. Unverified numbers then stop working for invitations, and users are asked to verify.

## Updating later
    git pull && docker compose -f docker-compose.prod.yml --env-file .env up -d --build

## Gotchas
- Backend refuses to start with a weak JWT_SECRET (needs 32+ random bytes) or without TURNSTILE_SECRET_KEY.
- Turnstile: add firstfood.store to the widget's allowed hostnames.
- Brevo: the sender email must be verified (BREVO_SENDER_EMAIL) or no sign-in code is delivered. India SMS (+91, SMS_ALLOWED_COUNTRY_CODES) is only needed once phone sign-in is enabled.
- Existing accounts: migration V28 stops with a readable error if two accounts share one verified email; fix those rows first (the message has the query).
- Back up the `pgdata` volume (e.g. nightly `docker exec ... pg_dump`).
