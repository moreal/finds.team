import { generateHydrationScript, renderToString } from "@solidjs/web";
import type { Environment } from "relay-runtime";

import { RelayEnvironmentProvider } from "../RelayRoot";
import { RelayProbe } from "./RelayProbe";

export function renderProbe(environment: Environment) {
  const html = renderToString(() => (
    <RelayEnvironmentProvider environment={environment}>
      <RelayProbe />
    </RelayEnvironmentProvider>
  ), { manifest: {}, nonce: "hydration-test" });
  return `${generateHydrationScript({ nonce: "hydration-test" })}<div id="root">${html}</div>`;
}
