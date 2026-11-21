# Build from the repository root:  docker build -f crosspay-server/Dockerfile .
FROM node:22-alpine
WORKDIR /app
# the ledger is shared with the app
COPY crosspay/src/main/assets/core.js crosspay/src/main/assets/core.js
COPY crosspay-server/package.json crosspay-server/package.json
COPY crosspay-server/src crosspay-server/src
WORKDIR /app/crosspay-server
ENV NODE_ENV=production DATA_DIR=/data PORT=8080
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:${PORT}/ >/dev/null || exit 1
CMD ["node", "src/server.js"]
