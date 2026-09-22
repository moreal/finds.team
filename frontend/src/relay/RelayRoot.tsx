import type { JSX } from "@solidjs/web";
import type { Environment } from "relay-runtime";
import { createContext, useContext, type Accessor } from "solid-js";

const RelayContext = createContext<Accessor<Environment>>();

// This is the isolated Solid 2 binding boundary until upstream bindings support it.
export function RelayEnvironmentProvider(props: Readonly<{
  environment: Environment;
  children: JSX.Element;
}>) {
  return (
    <RelayContext value={() => props.environment}>
      {props.children}
    </RelayContext>
  );
}

export function useRelayEnvironment(): Accessor<Environment> {
  return useContext(RelayContext);
}
