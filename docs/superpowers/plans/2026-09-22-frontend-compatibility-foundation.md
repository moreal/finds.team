# Frontend Compatibility Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reproducible SolidJS 2 RC, TanStack Start 2 RC, Relay, Kobalte, and virtualization frontend foundation and prove SSR/hydration compatibility before feature work.

**Architecture:** A root pnpm workspace owns `frontend/`. TanStack Start performs request-scoped SSR, `solid-relay` owns normalized GraphQL state, and compatibility-only integrations are isolated behind local adapters so prerelease dependency changes do not leak into product components.

**Tech Stack:** Node.js 24, pnpm 12.5.1, TypeScript, SolidJS 2 RC, TanStack Start 2 RC, Relay compiler/runtime, solid-relay, Kobalte 2 alpha, TanStack Virtual/core, Vitest, Playwright

**Spec:** `docs/superpowers/specs/2026-09-22-web-product-program-design.md`

## Global Constraints

- Keep SolidJS on the 2.x release-candidate line; Solid 1 is not a fallback.
- Use same-origin `/graphql`; do not add a TanStack Start BFF.
- Every SSR request owns a fresh Relay Environment.
- Protect executable SSR serialization with a fresh cryptographic nonce per
  request and a strict CSP that permits only nonce-bearing scripts.
- Product components never import Kobalte or TanStack Virtual directly; only local adapters may do so.
- Generated Relay artifacts are produced by codegen and checked for drift.

## Review Focus

- Two simultaneous SSR requests must not share Relay records; the concurrency test must return each request's own marker.
- Hydration must restore SSR records without issuing the initial GraphQL request again.
- A Kobalte dialog and select must retain keyboard/focus behavior after SSR hydration.
- Variable-height virtualization must not run on the server or cause a hydration mismatch.
- Every Start-emitted script must carry the request nonce, and production CSP
  must exclude `unsafe-eval` and unrestricted `unsafe-inline`.

---

### Task 1: Reproducible Node and pnpm workspace

**Files:**
- Create: `package.json`
- Create: `pnpm-workspace.yaml`
- Create: `.npmrc`
- Modify: `flake.nix`
- Modify: `README.md`

**Interfaces:**
- Produces: root commands `pnpm install`, `pnpm frontend:typecheck`, `pnpm frontend:test`, and `pnpm frontend:build`.

- [ ] **Step 1: Add failing workspace verification**

Run: `pnpm --version`

Expected: FAIL because pnpm is not supplied by the development shell.

- [ ] **Step 2: Define the workspace**

Create a private root `package.json` with `packageManager: "pnpm@12.5.1"` and scripts forwarding to the `frontend` package. Set `shared-workspace-lockfile=true`, `strict-peer-dependencies=false`, and `save-exact=true` in `.npmrc`; peer mismatches remain documented and are covered by behavior tests rather than silently ignored.

- [ ] **Step 3: Add runtime tools to Nix**

Add `pkgs.nodejs_24` and `pkgs.pnpm` beside `pkgs.jdk25` and document `pnpm install --frozen-lockfile` in `README.md`.

- [ ] **Step 4: Verify the workspace shell**

Run: `nix develop --command pnpm --version`

Expected: PASS and print pnpm 12.5.1.

- [ ] **Step 5: Commit**

```bash
git add package.json pnpm-workspace.yaml .npmrc flake.nix README.md
git commit -m "build(frontend): add pnpm workspace" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 2: Minimal TanStack Start Solid 2 application

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/src/router.tsx`
- Create: `frontend/src/routes/__root.tsx`
- Create: `frontend/src/routes/index.tsx`
- Create: `frontend/src/security/csp.ts`
- Create: `frontend/src/styles/global.css`
- Create: `frontend/src/app.test.tsx`
- Create: `frontend/tests/built-handler.test.mjs`

**Interfaces:**
- Produces: `getRouter(): Router`, SSR route `/`, and production client/server bundles.

- [ ] **Step 1: Write the failing root-route test**

Test that rendering `/` produces a `main` landmark and a link to `/jobs` with the text `공고 보기`.

- [ ] **Step 2: Run the test before scaffolding**

Run: `pnpm --dir frontend test --run`

Expected: FAIL because the frontend package and route do not exist.

- [ ] **Step 3: Pin the validated prerelease baseline**

Add exact dependencies for `solid-js@2.0.0-rc.9`, `@solidjs/web@2.0.0-rc.9`, `@tanstack/solid-start@2.0.0-rc.8`, and the matching `@tanstack/solid-router@2.0.0-rc.8`. Enable SSR with a fresh cryptographic nonce per request, attach that nonce to every Start-emitted script, and return a strict nonce-only script CSP.

- [ ] **Step 4: Implement the minimal route tree**

`getRouter()` must create a new router instance; never export a process-global router containing request data. `/` issues a permanent redirect to `/jobs` only after the `/jobs` route is introduced; during this task it renders the test landmark and link.

- [ ] **Step 5: Verify unit and production builds**

Run: `pnpm --dir frontend test --run && pnpm --dir frontend typecheck && pnpm --dir frontend build && pnpm --dir frontend test:built`

Expected: all commands PASS, `dist/client` plus `dist/server` are created, and
the built handler proves nonce/CSP coverage with one hydration bootstrap.

- [ ] **Step 6: Commit**

```bash
git add frontend pnpm-lock.yaml
git commit -m "feat(frontend): scaffold Solid Start SSR" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 3: Relay compiler and request-scoped SSR environment

**Files:**
- Create: `frontend/relay.config.json`
- Create: `frontend/src/relay/environment.ts`
- Create: `frontend/src/relay/network.ts`
- Create: `frontend/src/relay/RelayRoot.tsx`
- Create: `frontend/src/relay/__tests__/ssr-isolation.test.tsx`
- Create: `frontend/src/relay/__tests__/hydration.test.tsx`
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/src/routes/__root.tsx`

**Interfaces:**
- Produces: `createServerRelayEnvironment(request: Request): Environment` and `getBrowserRelayEnvironment(records: Record<string, unknown>): Environment`.
- Consumes: committed `schema/finds.graphqls`.

- [ ] **Step 1: Write failing isolation and hydration tests**

The isolation test creates two server environments with scripted fetch responses `{ marker: "a" }` and `{ marker: "b" }`, renders concurrently, and asserts no marker crosses responses. The hydration test serializes records, restores a browser environment, renders the same query, and asserts the fetch spy remains at zero calls.

- [ ] **Step 2: Verify failures**

Run: `pnpm --dir frontend test --run src/relay/__tests__`

Expected: FAIL because the Relay environment factories do not exist.

- [ ] **Step 3: Configure Relay codegen**

Use `schema: "../schema/finds.graphqls"`, `src: "./src"`, language `typescript`, and artifact directory `./src/__generated__`. Add `relay`, `relay:watch`, and `relay:validate` scripts. Configure `vite-plugin-relay-lite` and CJS interop required by `relay-runtime`.

- [ ] **Step 4: Implement environment factories**

Server network calls use `FINDS_INTERNAL_GRAPHQL_URL`, forward only the `cookie`, `accept-language`, request id, and CSRF headers required by the operation, and never store the environment globally. Browser calls use relative `/graphql` with `credentials: "same-origin"`.

- [ ] **Step 5: Serialize and restore records**

Expose only `RecordSource.toJSON()` data through the Start document serialization boundary. Restore it before mounting `RelayEnvironmentProvider`.

- [ ] **Step 6: Verify codegen, tests, and CSP build**

Run: `pnpm --dir frontend relay && pnpm --dir frontend relay:validate && pnpm --dir frontend test --run && pnpm --dir frontend build`

Expected: generated artifacts are current, tests pass, and built HTML/runtime contains no `eval(` requirement.

- [ ] **Step 7: Commit**

```bash
git add frontend pnpm-lock.yaml
git commit -m "feat(frontend): integrate Relay SSR" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 4: Kobalte compatibility adapter

**Files:**
- Create: `frontend/src/ui/kobalte/Dialog.tsx`
- Create: `frontend/src/ui/kobalte/Select.tsx`
- Create: `frontend/src/ui/kobalte/kobalte.css`
- Create: `frontend/src/ui/kobalte/__tests__/Dialog.test.tsx`
- Create: `frontend/src/ui/kobalte/__tests__/Select.test.tsx`
- Create: `frontend/e2e/kobalte-hydration.spec.ts`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: local `Dialog` and `Select<T>` APIs; product code imports only these paths.

- [ ] **Step 1: Write failing keyboard and hydration tests**

Test dialog focus trap, Escape close, trigger focus restoration, select ArrowDown/Enter selection, and stable hydrated DOM without console warnings.

- [ ] **Step 2: Install the alpha behind an explicit override**

Pin `@kobalte/core@2.0.0-alpha.2` and document its rc.3 peer declaration in `frontend/COMPATIBILITY.md`; pnpm's allowed peer mismatch is justified only by these behavior tests.

- [ ] **Step 3: Implement local wrappers**

Expose product-neutral props and preserve Kobalte-generated ARIA/focus props. Do not expose Kobalte component types from the wrapper's public declarations.

- [ ] **Step 4: Run browser compatibility checks**

Run: `pnpm --dir frontend test --run src/ui/kobalte && pnpm --dir frontend exec playwright test e2e/kobalte-hydration.spec.ts`

Expected: keyboard/focus tests PASS and browser console has no hydration error.

- [ ] **Step 5: Apply the explicit failure branch if checks fail**

If the alpha fails, remove it from runtime dependencies and implement the same local APIs with semantic native elements: `<dialog>` plus managed focus restoration, and a native `<select>` for the initial product. Preserve the failing Kobalte tests as skipped tests referencing the compatibility issue, while all local API behavior tests remain passing.

- [ ] **Step 6: Commit**

```bash
git add frontend pnpm-lock.yaml
git commit -m "feat(frontend): isolate accessible UI primitives" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 5: Virtual list compatibility adapter

**Files:**
- Create: `frontend/src/ui/virtual/VirtualList.tsx`
- Create: `frontend/src/ui/virtual/__tests__/VirtualList.test.tsx`
- Create: `frontend/e2e/virtual-list.spec.ts`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: `VirtualList<T>({ items, estimateSize, getKey, children, enabled })`.

- [ ] **Step 1: Write failing SSR and browser tests**

Assert SSR renders the first bounded page without absolute-position measurements, hydration preserves content, variable-height rows remain reachable, and keyboard focus is not unmounted while moving within overscan.

- [ ] **Step 2: Implement the adapter**

Try the Solid adapter under the locked peer override. If its hydration test fails, use `@tanstack/virtual-core` behind the same `VirtualList` API. `enabled` defaults false until after hydration and until the caller exceeds its measured threshold.

- [ ] **Step 3: Verify**

Run: `pnpm --dir frontend test --run src/ui/virtual && pnpm --dir frontend exec playwright test e2e/virtual-list.spec.ts`

Expected: PASS with no hydration warnings and no inaccessible focused row.

- [ ] **Step 4: Commit**

```bash
git add frontend pnpm-lock.yaml
git commit -m "feat(frontend): add isolated virtual list" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 6: Foundation verification command

**Files:**
- Modify: `package.json`
- Modify: `README.md`
- Create: `frontend/COMPATIBILITY.md`

**Interfaces:**
- Produces: root `pnpm frontend:check` command.

- [ ] **Step 1: Add the aggregate check**

`frontend:check` runs Relay validation, TypeScript, Vitest, Playwright compatibility specs, and production build in that order.

- [ ] **Step 2: Document the validated matrix**

Record exact resolved versions, which peer overrides were needed, whether Kobalte or native fallback is active, and whether Solid Virtual or virtual-core is active.

- [ ] **Step 3: Run the full gate**

Run: `pnpm frontend:check`

Expected: PASS from a clean install.

- [ ] **Step 4: Commit**

```bash
git add package.json README.md frontend/COMPATIBILITY.md
git commit -m "test(frontend): codify compatibility gate" -m "Assisted-by: Codex:gpt-5.6-sol"
```

### Task 7: Same-origin reverse-proxy topology

**Files:**
- Create: `frontend/Dockerfile`
- Create: `backend/Dockerfile`
- Create: `deploy/Caddyfile`
- Modify: `compose.yaml`
- Create: `frontend/e2e/same-origin-routing.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Produces one public origin where page routes reach TanStack Start; `/graphql`, `/auth/*`, and `/webauthn/*` reach Spring directly; Start SSR reaches Spring through `FINDS_INTERNAL_GRAPHQL_URL`.

- [ ] **Step 1: Write the failing routing smoke test**

Assert `/jobs` returns Start-rendered HTML, `/graphql` returns the Spring GraphQL content type, `/auth/session` reaches Spring, and a browser GraphQL request does not pass through a Start server route.

- [ ] **Step 2: Add production images and Caddy routing**

Build the frontend server/client bundle in a pinned Node/pnpm stage, the backend boot jar with the Gradle wrapper/JDK 25 stage, and run each as an unprivileged user. Caddy routes the three backend prefixes before the frontend catch-all and forwards request ids.

- [ ] **Step 3: Extend Compose without changing database persistence**

Add opt-in `backend`, `frontend`, and `proxy` services under the `app` profile. Backend depends on healthy PostgreSQL; frontend receives only the internal GraphQL URL; proxy is the only published application port.

- [ ] **Step 4: Verify topology**

Run: `docker compose --profile app up -d --build && pnpm --dir frontend exec playwright test e2e/same-origin-routing.spec.ts`

Expected: PASS with no CORS response dependency and no duplicate browser-to-Start-to-Spring GraphQL hop.

- [ ] **Step 5: Commit**

```bash
git add frontend/Dockerfile frontend/e2e/same-origin-routing.spec.ts backend/Dockerfile deploy/Caddyfile compose.yaml README.md
git commit -m "build: add same-origin web topology" -m "Assisted-by: Codex:gpt-5.6-sol"
```
