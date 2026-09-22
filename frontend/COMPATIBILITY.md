# Frontend compatibility foundation

The validated baseline is Solid/Web `2.0.0-rc.9`, TanStack Start/Router
`2.0.0-rc.8`, Relay compiler/runtime `20.1.1`, and Vite `8.3.0`.

## Resolved package matrix

The exact direct versions below are present in `pnpm-lock.yaml` and were
confirmed from the installed graph with `pnpm list --depth 0 --json`. The Nix
shell resolves Node `24.19.0`; the root `packageManager` and Corepack-managed
lock resolve pnpm `12.5.1`.

| Package | Resolved version | Scope |
| --- | --- | --- |
| `solid-js` / `@solidjs/web` | `2.0.0-rc.9` / `2.0.0-rc.9` | Active application runtime |
| `@tanstack/solid-start` / `@tanstack/solid-router` | `2.0.0-rc.8` / `2.0.0-rc.8` | Active SSR/router runtime |
| `relay-runtime` / `relay-compiler` | `20.1.1` / `20.1.1` | Active runtime and build-time codegen |
| `graphql` | `16.11.0` | Relay compiler input tooling |
| `@tanstack/virtual-core` | `3.17.11` | Active virtual-list engine |
| `tabbable` | `6.5.0` | Active native Dialog focus traversal |
| `@kobalte/core` | `2.0.0-alpha.2` | Test-only failed candidate; native controls are active |
| `@tanstack/solid-virtual` | `3.13.40` | Test-only failed candidate; virtual-core is active |
| `vite` / `vite-plugin-solid` | `8.3.0` / `3.0.0-next.27` | Build and Solid transform |
| `vite-plugin-relay-lite` / `vite-plugin-cjs-interop` | `0.12.0` / `4.0.3` | Relay transform and runtime interop |
| `typescript` / `vitest` | `5.9.3` / `5.0.1` | Type and unit-test gates |
| `@playwright/test` | `1.63.0` | Browser compatibility gate |
| `@solidjs/testing-library` / `@testing-library/jest-dom` | `1.0.0-beta.3` / `7.0.1` | DOM test support |
| `jsdom` / `@types/jsdom` | `28.1.0` / `28.0.3` | DOM test environment and types |
| `@types/node` / `@types/relay-runtime` | `24.10.0` / `20.1.1` | Type-only tooling |

## Peer policy, overrides, and remaining diagnostics

`.npmrc` keeps `strict-peer-dependencies=false` only for this prerelease
compatibility foundation. `pnpm-workspace.yaml` contains exactly three scoped
`allowedVersions` entries:

| Override | Declared peer | Resolved peer | Scope and evidence |
| --- | --- | --- | --- |
| `@kobalte/core>solid-js` | exact `2.0.0-rc.3` | `2.0.0-rc.9` | Test-only Kobalte candidate; its real hydration gate fails and the native fallback remains active. |
| `@kobalte/core>@solidjs/web` | exact `2.0.0-rc.3` | `2.0.0-rc.9` | Same test-only Kobalte probe; not a runtime compatibility claim. |
| `@tanstack/solid-virtual>solid-js` | `^1.3.0` | `2.0.0-rc.9` | Test-only Solid adapter probe; its production bundle fails and virtual-core remains active. |

`pnpm peers check` intentionally remains nonzero. Every reported mismatch is
named here; none has an additional override:

| Packages reporting the peer | Wanted | Installed | Scope |
| --- | --- | --- | --- |
| `@kobalte/utils@2.0.0-alpha.0` → `@solidjs/web`, `solid-js` | exact `2.0.0-rc.0` | `2.0.0-rc.9` | Transitive test-only Kobalte candidate. |
| `@solid-primitives/utils@6.4.1`, `refs@1.1.4`, `static-store@0.1.4`, `styles@0.1.4`, `rootless@1.5.4`, `scheduled@1.5.3`, `event-listener@2.4.6`, `media@2.3.6`, `keyboard@1.3.7`, `bounds@0.1.7`, `resize-observer@2.2.0` → `solid-js` | `^1.6.12` | `2.0.0-rc.9` | Transitive through the TanStack router's Solid devtools graph; covered by SSR, hydration, DOM, type, browser, and build gates. |
| `@solid-devtools/logger@0.9.11`, `shared@0.20.0`, `debugger@0.28.1` → `solid-js` | `^1.9.0` | `2.0.0-rc.9` | Transitive through `@tanstack/solid-router@2.0.0-rc.8`; same active-runtime gate coverage. |
| `vite-plugin-relay-lite@0.12.0` → `vite` | `^2.0.0 || ^3.0.0 || ^4.0.0 || ^5.0.0 || ^6.0.0 || ^7.0.0` | `8.3.0` | Build-time Relay transform; covered by codegen, query, hydration, typecheck, and production-build gates. |

The only package patch is scoped to `@tanstack/solid-start@2.0.0-rc.8`
(`d42e4d6f8a1e052341af34680b1cae9e42c41672d69e1aba20a885569e830035`
in the lock). It maps that RC's removed Solid Web rc.8 server-function URL
helpers to rc.9's action-URL helpers. Native rc.9 imports for every other
consumer remain unchanged; remove the patch when TanStack consumes the rc.9 API.

## Relay runtime boundary

- `solid-relay@1.0.0-beta.29` imports removed Solid 1 APIs and
  `solid-js/store`, so it cannot load on this baseline. `src/relay/RelayRoot.tsx`
  supplies only the local environment provider/context. Replace that boundary
  with upstream bindings once compatible; add query/fragment/pagination/mutation
  bindings only when product code needs them. We own binding correctness meanwhile.
- Relay 20 rejects `.graphqls`. `scripts/relay.ts` refreshes `.relay/schema.graphql`
  from the canonical `../schema/finds.graphqls` bytes and derives an ignored
  compiler config before every compile, validation, watch, and Vite codegen run.
  Watch mode also refreshes on canonical schema changes. Neither derived file is
  committed. Remove the adapter once Relay accepts the canonical extension.
- `vite-plugin-relay-lite@0.12.0` declares Vite peers through 7. The pinned Vite 8
  combination passes compiled-query tests, actual production-mode DOM hydration,
  typecheck, and client/server production builds. Its automatic codegen launcher
  emits Node's `DEP0190` warning because the upstream plugin uses `spawn` with
  `shell: true`; our launcher passes argument arrays without a shell.
- Existing Solid 1 peer declarations under Solid devtools/primitives remain
  upstream metadata mismatches from the scaffold. No Solid 1 runtime is added.
- The existing consumer-scoped TanStack Start patch is unchanged.

Route loaders preload and await their Relay queries before Start dehydrates the
router. Only `RecordSource.toJSON()` enters Start's nonce-protected serialization;
the browser restores it before rendering the provider. SSR calls require
`FINDS_INTERNAL_GRAPHQL_URL`; browser requests use `/graphql` with same-origin
credentials. Server forwarding allows `cookie`, `accept-language`, `x-request-id`,
and, for mutations only, `x-csrf-token`/`x-xsrf-token`. Browser mutation CSRF-token
acquisition belongs to the later identity/mutation integration.

Run the full fail-fast gate from the repository root with the pinned
Corepack/pnpm toolchain:

```sh
pnpm install --frozen-lockfile
pnpm --dir frontend exec playwright install chromium
pnpm frontend:check
```

The gate order is Relay validation, TypeScript, Vitest, the two compatibility
Playwright specs (`e2e/kobalte-hydration.spec.ts` and
`e2e/virtual-list.spec.ts`), the production build, then `test:built` against the
emitted artifacts. That final suite verifies nonce-only CSP, a unique nonce on
every script, one hydration bootstrap, no dynamic evaluation, and no internal
GraphQL endpoint in the client bundle. The opt-in Kobalte and Solid Virtual
failure reproductions below remain outside the passing gate.

## KOBALTE-ALPHA2-SOLID-RC9

The active `src/ui/kobalte/Dialog.tsx` and `Select.tsx` adapters use native
`<dialog>` and `<select>`. Their public declarations expose local props and
Solid JSX types only; product components import these local paths. `Dialog`
manages modal opening, keyboard focus traversal, Escape, native form dismissal,
and trigger focus restoration. `Select<T>` is controlled, returns the original
option object, and submits `getOptionValue(option)` through its native form
control. Option values must be unique. Dialogs become modal after hydration.

Focus traversal uses `tabbable@6.5.0` for visibility-aware native tab order,
including contenteditable fields. It respects handled keyboard events and only
cancels default Tab traversal after focus succeeds. Both controlled adapters
reconcile native DOM changes after Solid's updates settle, so a parent can
decline a dialog dismissal or select change without changing its prop. Dialog
dimensions include padding and borders in their viewport limits.

`@kobalte/core@2.0.0-alpha.2` declares exact Solid/Web peers at `2.0.0-rc.3`.
The workspace explicitly allows this package's peers to resolve to our pinned
`2.0.0-rc.9` for the compatibility probe. The alpha passes TypeScript but fails
the real production SSR/client hydration gate: the browser reports
`[REACTIVITY_HALTED]` and `Hydration Mismatch. Unable to find DOM nodes for
hydration key: A19000203l40`. Its hidden select also emits inline style attributes
blocked by the existing CSP. The policy is unchanged.

The alpha is absent from runtime dependencies. Its exact pin remains a
development dependency solely to reproduce the preserved candidate wrappers in
`src/ui/kobalte/__tests__/alpha/`. The `kobalte-alpha` Playwright project retains
the failed SSR/hydration and keyboard tests, skipped by default with this issue
reference.

The alpha's transitive `@kobalte/utils@2.0.0-alpha.0` also declares exact
Solid/Web `2.0.0-rc.0` peers. `pnpm peers check` therefore remains nonzero for
that test-only mismatch and the pre-existing Solid devtools/primitives and
Relay-plugin metadata mismatches above. No compatibility override is asserted
for the utils package.

Reproduce the failure with:

```sh
KOBALTE_COMPATIBILITY=1 pnpm --dir frontend exec playwright test e2e/kobalte-hydration.spec.ts --project=kobalte-alpha
```

Replace the native implementations only when a compatible Kobalte build passes
the same behavior gate without weakening the CSP. Remove the alpha fixtures,
development dependency, and package-scoped peer override when they are no longer
needed. The alpha override does not claim production compatibility.

Run the local API gate (Playwright `1.63.0`):

```sh
pnpm --dir frontend exec playwright install chromium
pnpm --dir frontend test --run src/ui/kobalte
pnpm --dir frontend exec playwright test e2e/kobalte-hydration.spec.ts
```

The fixture builds real production-mode Solid SSR and client bundles and serves
them under the application's nonce-only CSP. It does not add a product route.
Hydration tests retain references to SSR nodes while the client bundle is paused,
then check that hydration reuses those nodes. Every browser warning/error fails
the gate, including diagnostics during interaction.

On the validated macOS host, headless Chromium and WebKit cannot drive a native
select popup with synthetic ArrowDown/Enter, even for a plain HTML select outside
Solid. Firefox also fails to launch its temporary profile. Use Linux Chromium
for this complete keyboard gate; a local Linux browser server is supported:

```sh
docker run --rm --init --publish 127.0.0.1:3300:3000 mcr.microsoft.com/playwright:v1.63.0-noble npx -y playwright@1.63.0 run-server --host 0.0.0.0 --port 3000
# In another terminal; choose a free local port if 3300 is occupied.
PW_TEST_CONNECT_WS_ENDPOINT=ws://127.0.0.1:3300/ PW_TEST_CONNECT_EXPOSE_NETWORK='<loopback>' pnpm --dir frontend exec playwright test e2e/kobalte-hydration.spec.ts
```

## SOLID-VIRTUAL3-SOLID-RC9

`src/ui/virtual/VirtualList.tsx` uses `@tanstack/virtual-core@3.17.11` with
Solid 2's `onSettled` lifecycle and two-phase effects. Product code imports only
this local component. Its public `VirtualListProps<T>` exposes local props and
Solid accessor/JSX types, with no TanStack types. Pass readonly `items`, a positive
`estimateSize(index)` in pixels, stable unique `getKey(item)` values, and a
`children(item, index)` renderer whose two arguments are reactive accessors.
The renderer runs once for each mounted key; read `item()` and `index()` in JSX
or component props so same-key data updates and reorder positions stay live
without recreating the row's subtree or local state:

```tsx
<VirtualList items={rows()} estimateSize={() => 80} getKey={(row) => row.id} enabled={enabled()}>
  {(item, index) => <Row item={item()} position={index() + 1} />}
</VirtualList>
```

The exact `@tanstack/solid-virtual@3.13.40` candidate declares `solid-js@^1.3.0`.
With the package-scoped peer allowance resolving it to `2.0.0-rc.9`, the actual
production SSR/client browser harness fails during bundling: `"./store" is not
exported` by Solid 2. The adapter also imports removed `createComputed`,
`mergeProps`, and `onMount` APIs. It cannot reach hydration on this baseline;
this is a build incompatibility, not a passing hydration result. No Solid 1
shim or downgraded runtime is introduced.

The failed adapter is retained only as an exact development dependency and
reproduction fixture in `src/ui/virtual/__tests__/solid/`. Reproduce the failure:

```sh
SOLID_VIRTUAL_COMPATIBILITY=1 pnpm --dir frontend exec playwright test e2e/virtual-list.spec.ts
```

Remove that fixture, development dependency, and scoped peer allowance when a
Solid 2 adapter passes the same gate. The allowance does not assert production
compatibility. The existing unrelated peer diagnostics remain unchanged.

The local contract is:

- SSR renders at most the first 20 items in normal flow, with list semantics and
  full-set positions. It never estimates or measures row geometry, emits inline
  styles, or creates a virtualizer. Hydration uses those same keys and nodes.
- After hydration settles, `enabled` still defaults to false. Disabled lists
  render all supplied items. Callers enable only collections their product
  measurements justify; the adapter additionally requires more than 20 rows and
  more than three viewportfuls, computed from the first page's actual average
  row height. That keyed measurement evidence survives the page leaving the
  mounted window; the current window does not replace the collection sample.
  Evidence is tied to the current collection, item identities, and caller
  enablement. Replaced content must be measured after its DOM update before it
  can activate virtualization, even when its keys are retained.
  Empty, hidden, and unmeasurable lists remain disabled until measurable.
- Resize observation and a coalesced post-update measurement pass recheck the
  threshold after collection/content changes. Pending fresh evidence may retain
  an already-active mode but cannot activate an inactive list. Fresh first-page
  heights can activate or deactivate it; ordinary scrolling retains the same
  collection sample and cannot cause mode oscillation. When changed sample rows
  are offscreen, they are temporarily mounted to obtain fresh evidence.
  Real size changes on already-mounted rows, or a changed container width,
  invalidate offscreen sample geometry even when item identities are unchanged.
  Newly mounted window rows establish a size baseline; their initial observer
  notifications and canvas estimate corrections do not trigger resampling.
  The core measures variable-height rows and positions them only after activation.
  Styles use an external stylesheet plus browser CSSOM property assignments,
  preserving the existing nonce-only CSP. The scroll container has a default
  `max-height: min(70vh, 40rem)`; a containing stylesheet can size
  `.ui-virtual-list` for its layout.
- Keys preserve the row subtree, component state, and focus across append,
  reorder, and same-key data replacement, as well as scroll/measurement updates.
  Solid's keyed entry accessors own each row's live values until disposal, so
  removal, clearing, and full replacement cannot read already-deleted map keys.
  Activation seeds the core with existing row measurements and the current
  scroll offset. Mode transitions preserve the visible keyed item and its
  within-row offset even when preceding estimates differed from real heights.
  Five overscan rows
  surround the visible range. The focused row and its five neighbours on each
  side remain mounted even when pointer scrolling moves the viewport away;
  leaving the list releases that retained range. The initial page remains
  mounted while scrolled to the top.
- Server pagination remains the caller's responsibility. The initial SSR bound
  is not a substitute for a server connection page or its continuation controls.

Run the local gate:

```sh
pnpm --dir frontend test --run src/ui/virtual
pnpm --dir frontend exec playwright test e2e/virtual-list.spec.ts
```

The 20 real-browser cases pass on macOS Chromium and Linux Chromium. They cover
retained SSR nodes with no diagnostics, caller/measurement gating, growth and
shrink, disabling, initially empty data, resizing, variable-height end/back
scrolling, child DOM/state retention with live item/index updates, scrolled mode
transitions, heterogeneous-height mode stability, removal/replacement/clearing
in both modes with owner disposal, fresh activation evidence in both size
directions, offscreen CSS shrink/re-expand with unchanged items, width-only
container invalidation with the sample offscreen, no sample remounts during
ordinary scroll estimate corrections, and forward/backward keyboard traversal
through 35 rows. SSR assertions check the exact 20-row page and retained
child identity/text after hydration. All browser
console warnings/errors fail the gate. The shared fixture also keeps all 16
native-control browser cases passing on Linux Chromium.
