import { Environment, RecordSource, Store } from "relay-runtime";

import { createBrowserNetwork, createServerNetwork } from "./network";

export function createServerRelayEnvironment(request: Request): Environment {
  return new Environment({
    network: createServerNetwork(request),
    store: new Store(new RecordSource()),
    isServer: true,
  });
}

let browserEnvironment: Environment | undefined;

export function getBrowserRelayEnvironment(records: Record<string, unknown>): Environment {
  const source = new RecordSource(records as ConstructorParameters<typeof RecordSource>[0]);
  // Only a browser document may reuse an environment. SSR always owns its store.
  const existing = typeof document !== "undefined" ? browserEnvironment : undefined;
  if (existing) {
    existing.getStore().publish(source);
    existing.getStore().notify();
    return existing;
  }
  const environment = new Environment({
    network: createBrowserNetwork(),
    store: new Store(source),
  });
  if (typeof document !== "undefined") browserEnvironment = environment;
  return environment;
}
