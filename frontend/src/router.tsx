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
