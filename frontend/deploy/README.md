# Deploy notes

- `docker-compose.prod.yml` and `nginx/firstfood.conf` come from the portals backend, with one fix: the frontend's
  `NEXT_PUBLIC_API_BASE_URL` / `BACKEND_ORIGIN` are now **build args** (they are compiled into the Next build; as runtime
  env the Dockerfile fails on purpose).
- Routing: `/` and `/staff` -> Next.js (customer portal and staff portal), `/api/v1/` -> Spring Boot. Postgres and Redis sit on an internal `data` network that the frontend is not attached to; Redis requires `REDIS_PASSWORD` (F-10).
- Sessions: the portals backend returns the refresh token in the response body, which the app keeps **in memory only**.
  A full page reload therefore signs the user out (links inside the app do not). The hardened backend's HttpOnly refresh
  cookie fixes that; the app already supports it (in same-origin mode it asks for the cookie automatically).
- Bot challenge (F-05): the sign-in page shows a Cloudflare Turnstile check before it will send an SMS. Set `NEXT_PUBLIC_TURNSTILE_SITE_KEY`
  (a **build arg**, like the two above) for the frontend and `TURNSTILE_SECRET_KEY` for the backend. The same-origin proxy forwards the
  `X-Challenge-Token` header untouched (checked by `npm run smoke:proxy`). Details and staging checks: `docs/OPERATIONS.md` > "SMS pumping".
