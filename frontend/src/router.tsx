import { createRouter } from "@tanstack/solid-router";
import { createIsomorphicFn } from "@tanstack/solid-start";
import { getRequest } from "@tanstack/solid-start/server";

import { createServerRelayEnvironment, getBrowserRelayEnvironment } from "./relay/environment";
import { routeTree } from "./routeTree.gen";
import { getCspNonce } from "./security/csp";

const createRouterEnvironment = createIsomorphicFn()
  .server((request?: Request) => createServerRelayEnvironment(request ?? getRequest()))
  .client(() => getBrowserRelayEnvironment({}));

export function getRouter(request?: Request) {
  const context = { relayEnvironment: createRouterEnvironment(request) };
  return createRouter({
    routeTree,
    context,
    // Discovery URLs use ordinary repeated parameters, never JSON arrays.
    parseSearch: (search) => {
      const params = new URLSearchParams(search);
      const values: Record<string, string | string[]> = {};
      for (const key of new Set(params.keys())) {
        const entries = params.getAll(key);
        Object.defineProperty(values, key, { value: entries.length > 1 ? entries : entries[0], enumerable: true });
      }
      return values;
    },
    stringifySearch: (search) => {
      const params = new URLSearchParams();
      for (const [key, value] of Object.entries(search)) {
        if (value === undefined) continue;
        for (const entry of Array.isArray(value) ? value : [value]) params.append(key, String(entry));
      }
      const body = params.toString();
      return body ? `?${body}` : "";
    },
    scrollRestoration: true,
    ssr: { nonce: getCspNonce() },
    // Only normalized records cross Start's escaped, nonce-protected boundary.
    dehydrate: () => ({
      relayRecords: context.relayEnvironment.getStore().getSource().toJSON(),
    }),
    hydrate: (data) => {
      context.relayEnvironment = getBrowserRelayEnvironment(data.relayRecords);
    },
  });
}

declare module "@tanstack/solid-router" {
  interface Register {
    router: ReturnType<typeof getRouter>;
  }
}
