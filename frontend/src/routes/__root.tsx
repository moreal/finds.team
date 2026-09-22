import {
  HeadContent,
  Outlet,
  Scripts,
  createRootRouteWithContext,
  useRouter,
} from "@tanstack/solid-router";
import type { JSX } from "@solidjs/web";
import type { Environment } from "relay-runtime";

import { RelayEnvironmentProvider } from "../relay/RelayRoot";
import { createContentSecurityPolicy } from "../security/csp";
import globalCss from "../styles/global.css?url";

export const Route = createRootRouteWithContext<{ relayEnvironment: Environment }>()({
  head: () => ({
    links: [{ href: globalCss, rel: "stylesheet" }],
    meta: [
      { charSet: "utf-8" },
      {
        content: "width=device-width, initial-scale=1",
        name: "viewport",
      },
      { title: "finds.team" },
    ],
  }),
  headers: ({ ssr }) => {
    if (!ssr?.nonce) {
      throw new Error("SSR requires a Content Security Policy nonce");
    }

    return {
      "Content-Security-Policy": createContentSecurityPolicy(ssr.nonce),
    };
  },
  component: RelayRoot,
  shellComponent: RootDocument,
});

function RelayRoot() {
  const router = useRouter();
  return (
    <RelayEnvironmentProvider environment={router.options.context.relayEnvironment}>
      <Outlet />
    </RelayEnvironmentProvider>
  );
}

function RootDocument(props: Readonly<{ children: JSX.Element }>) {
  return (
    <html lang="ko">
      <head />
      <body>
        <HeadContent />
        {props.children}
        <Scripts />
      </body>
    </html>
  );
}
