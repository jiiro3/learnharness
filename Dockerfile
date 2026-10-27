# Self-hosted server image (Cloud Run). Builds the standalone Next server
# from source; install scripts stay off (security review 2026-10-27).
FROM node:22-bookworm-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY . .
RUN npx next build && node scripts/build-server.mjs

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production HOSTNAME=0.0.0.0 PORT=8080
COPY --from=build --chown=node:node /src/.next/standalone/ ./
USER node
EXPOSE 8080
CMD ["node", "server.js"]
