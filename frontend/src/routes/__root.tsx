import {
  HeadContent,
  Outlet,
  Scripts,
  createRootRoute,
} from "@tanstack/solid-router";
import { HydrationScript, type JSX } from "@solidjs/web";

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
  component: Outlet,
  shellComponent: RootDocument,
});

function RootDocument(props: Readonly<{ children: JSX.Element }>) {
  return (
    <html lang="ko">
      <head>
        <HydrationScript />
      </head>
      <body>
        <HeadContent />
        {props.children}
        <Scripts />
      </body>
    </html>
  );
}
