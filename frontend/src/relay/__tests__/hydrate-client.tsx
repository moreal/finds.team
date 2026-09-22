import { hydrate } from "@solidjs/web";
import { fetchQuery } from "relay-runtime";

import { getBrowserRelayEnvironment } from "../environment";
import { RelayEnvironmentProvider } from "../RelayRoot";
import { probeQuery, RelayProbe } from "./RelayProbe";

export async function hydrateProbe(records: Record<string, unknown>) {
  // The router creates its environment before Start invokes its hydrate hook.
  const environment = getBrowserRelayEnvironment({});
  getBrowserRelayEnvironment(records);
  await fetchQuery(environment, probeQuery, {}, { fetchPolicy: "store-or-network" }).toPromise();
  return hydrate(() => (
    <RelayEnvironmentProvider environment={environment}>
      <RelayProbe />
    </RelayEnvironmentProvider>
  ), document.getElementById("root")!);
}
