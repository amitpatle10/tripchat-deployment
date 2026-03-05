# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Node 20 Alpine is small (~180MB). We use it only for the build step.
FROM node:20-alpine AS build

WORKDIR /app

# Install dependencies first (cached layer).
# npm ci is strict: uses package-lock.json exactly, fails on mismatch.
# This is preferred over npm install in CI/Docker for reproducible builds.
COPY package*.json ./
RUN npm ci

# Copy source and build. Vite produces hashed static files in /app/dist.
COPY . .
RUN npm run build


# ── Stage 2: Serve ────────────────────────────────────────────────────────────
# nginx:alpine is ~25MB. It serves the static files and proxies /api + /ws
# to the backend container (handled in nginx.conf).
FROM nginx:alpine

# Replace the default nginx config with our SPA + proxy config.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built static assets from the build stage.
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx starts in the foreground; Docker manages the process lifecycle.
CMD ["nginx", "-g", "daemon off;"]
