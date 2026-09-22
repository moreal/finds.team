import "@testing-library/jest-dom/vitest";

import { within } from "@solidjs/testing-library";
import { renderToString } from "@solidjs/web";
import { createMemoryHistory } from "@tanstack/solid-router";
import { RouterServer } from "@tanstack/solid-router/ssr/server";
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";

import { getRouter } from "./router";

describe("the root route", () => {
  it("renders the jobs entry point at /", async () => {
    const router = getRouter();
    router.update({
      history: createMemoryHistory({ initialEntries: ["/"] }),
    });
    await router.load();

    const markup = renderToString(() => <RouterServer router={router} />, {
      manifest: {},
    });
    const { document } = new JSDOM(markup).window;
    const page = within(document.body);

    expect(page.getByRole("main")).toBeInTheDocument();
    expect(
      page.getByRole("link", { name: "공고 보기" }),
    ).toHaveAttribute("href", "/jobs");
  });
});

describe("the pinned Solid Start runtime", () => {
  it("provides the server-function URL API expected by TanStack Start", async () => {
    const serverFunctions = await import(
      "@solidjs/web/server-functions/server"
    );

    expect(serverFunctions).toHaveProperty("parseServerFunctionUrl");
  });
});
