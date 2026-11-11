# Imagen de produccion del frontend: compila la web y la sirve con Caddy, que
# ademas pone el HTTPS y reenvia /api y /ws al backend (ver deploy/Caddyfile).

# --- 1. Compilar -------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- 2. Servir ---------------------------------------------------------------
FROM caddy:2.8.4-alpine
COPY --from=build /app/dist /srv
