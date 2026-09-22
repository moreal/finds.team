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
