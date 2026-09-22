FROM node:24.21.0-alpine3.24 AS build

ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME:$PATH
WORKDIR /workspace

RUN corepack enable && corepack prepare pnpm@12.5.1 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY patches ./patches
COPY frontend/package.json ./frontend/package.json
RUN pnpm install --frozen-lockfile

COPY schema ./schema
COPY frontend ./frontend
RUN pnpm --dir frontend build && pnpm install --prod --frozen-lockfile

FROM node:24.21.0-alpine3.24 AS runtime

ENV HOST=0.0.0.0
ENV NODE_ENV=production
ENV PORT=3000
WORKDIR /workspace/frontend

COPY --from=build --chown=node:node /workspace/node_modules /workspace/node_modules
COPY --from=build --chown=node:node /workspace/frontend/node_modules ./node_modules
COPY --from=build --chown=node:node /workspace/frontend/dist ./dist

USER node
EXPOSE 3000
CMD ["./node_modules/.bin/srvx", "--prod", "--static", "../client", "--entry", "dist/server/server.js"]
