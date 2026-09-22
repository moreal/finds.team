import { createRouter } from "@tanstack/solid-router";

import { routeTree } from "./routeTree.gen";
import { getCspNonce } from "./security/csp";

export function getRouter() {
  return createRouter({
    routeTree,
    scrollRestoration: true,
    ssr: { nonce: getCspNonce() },
  });
}

declare module "@tanstack/solid-router" {
  interface Register {
    router: ReturnType<typeof getRouter>;
  }
}
