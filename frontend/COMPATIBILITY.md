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
