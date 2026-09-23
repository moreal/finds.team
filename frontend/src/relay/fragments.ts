import RelayRuntime, { type Environment, type GraphQLTaggedNode, type OperationType, type Variables } from "relay-runtime";
import { createEffect, createMemo, createSignal } from "solid-js";
import { useRelayEnvironment } from "./RelayRoot";

const { getFragment, getSelector, getRequest, createOperationDescriptor, fetchQuery } = RelayRuntime;
export function readFragment<Key extends { readonly " $data"?: unknown }>(environment: Environment, fragment: GraphQLTaggedNode, key: Key): NonNullable<Key[" $data"]> {
  const selector = getSelector(getFragment(fragment), key);
  if (!selector || selector.kind !== "SingularReaderSelector") throw new Error("Expected a singular Relay fragment");
  return environment.lookup(selector).data as NonNullable<Key[" $data"]>;
}

export function useFragment<Key extends { readonly " $data"?: unknown }>(fragment: GraphQLTaggedNode, key: () => Key) {
  const environment = useRelayEnvironment();
  const [revision, setRevision] = createSignal(0);
  createEffect(key, reference => {
    const selector = getSelector(getFragment(fragment), reference);
    if (!selector || selector.kind !== "SingularReaderSelector") return;
    const subscription = environment().subscribe(environment().lookup(selector), () => setRevision(value => value + 1));
    const retained = environment().retain(createOperationDescriptor(selector.owner.node, selector.owner.variables));
    return () => { subscription.dispose(); retained.dispose(); };
  });
  return createMemo(() => { revision(); return readFragment(environment(), fragment, key()); });
}

export function readQuery<Query extends OperationType>(environment: Environment, query: GraphQLTaggedNode, variables: Query["variables"]): Query["response"] {
  return environment.lookup(createOperationDescriptor(getRequest(query), variables).fragment).data as Query["response"];
}

export async function fetchDetail(environment: Environment, query: GraphQLTaggedNode, variables: Variables, force = false) {
  await fetchQuery(environment, query, variables, { fetchPolicy: force ? "network-only" : "store-or-network" }).toPromise();
}
