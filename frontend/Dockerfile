
# --- Build stage ---
FROM node:26.13.1-alpine3.24 AS build
WORKDIR /app

# Update bundled npm to a version with patched dependencies
RUN npm install --global npm@12.2.2

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG NEXT_PUBLIC_API_BASE_URL
ARG BACKEND_ORIGIN
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV BACKEND_ORIGIN=${BACKEND_ORIGIN}

ARG NEXT_PUBLIC_TURNSTILE_SITE_KEY=""
ENV NEXT_PUBLIC_TURNSTILE_SITE_KEY=${NEXT_PUBLIC_TURNSTILE_SITE_KEY}

RUN npm run build

# --- Runtime stage ---
FROM node:26.13.1-alpine3.24
WORKDIR /app
ENV NODE_ENV=production

# Update npm in the final production image too
RUN npm install --global npm@12.2.2

RUN addgroup -S firstfood && adduser -S firstfood -G firstfood

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public

USER firstfood
EXPOSE 3000

CMD ["npm", "run", "start"]
