# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

# Bağımlılıklar (cache katmanı)
COPY package*.json ./
RUN npm ci --silent

# Kaynak kod
COPY . .
RUN npm run build          # → /app/dist

# ── Stage 2: Serve (nginx) ────────────────────────────────────────────────────
FROM nginx:1.27-alpine

# Vite build çıktısı
COPY --from=builder /app/dist /usr/share/nginx/html

# API proxy + SPA routing konfigürasyonu
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
