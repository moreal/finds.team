import {
  HeadContent,
  Outlet,
  Scripts,
  createRootRoute,
} from "@tanstack/solid-router";
import type { JSX } from "@solidjs/web";

import { createContentSecurityPolicy } from "../security/csp";
import globalCss from "../styles/global.css?url";

export const Route = createRootRoute({
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
  component: Outlet,
  shellComponent: RootDocument,
});

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
