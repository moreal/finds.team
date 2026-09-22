# Relay on the Solid 2 foundation

The validated baseline is Solid/Web `2.0.0-rc.9`, TanStack Start/Router
`2.0.0-rc.8`, Relay compiler/runtime `20.1.1`, and Vite `8.3.0`.

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

Run the gate with the repository's Node 24 shell:

```sh
pnpm --dir frontend relay
pnpm --dir frontend relay:validate
pnpm --dir frontend test --run
pnpm --dir frontend typecheck
pnpm --dir frontend build
pnpm --dir frontend test:built
```

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

The 17 real-browser cases pass on macOS Chromium and Linux Chromium. They cover
retained SSR nodes with no diagnostics, caller/measurement gating, growth and
shrink, disabling, initially empty data, resizing, variable-height end/back
scrolling, child DOM/state retention with live item/index updates, scrolled mode
transitions, heterogeneous-height mode stability, removal/replacement/clearing
in both modes with owner disposal, fresh activation evidence in both size
directions, and forward/backward keyboard
traversal through 35 rows. SSR assertions check the exact 20-row page and retained
child identity/text after hydration. All browser
console warnings/errors fail the gate. The shared fixture also keeps all 16
native-control browser cases passing on Linux Chromium.
