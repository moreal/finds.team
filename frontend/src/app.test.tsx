import "@testing-library/jest-dom/vitest";

import { within } from "@solidjs/testing-library";
import { renderToString } from "@solidjs/web";
import {
  GET,
  createServerReference,
  parseServerFunctionActionUrl,
  registerServerReference,
  serverFunctionActionUrl,
  serverFunctionUrl,
} from "@solidjs/web/server-functions/server";
import { createMemoryHistory } from "@tanstack/solid-router";
import { RouterServer } from "@tanstack/solid-router/ssr/server";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";

import { getRouter } from "./router";
import { getCspNonce } from "./security/csp";

async function renderRootRoute() {
  const router = getRouter();
  router.update({
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  await router.load();

  const markup = renderToString(() => <RouterServer router={router} />, {
    manifest: {},
    nonce: router.options.ssr?.nonce,
  });

  return { document: new JSDOM(markup).window.document, router };
}

describe("the root route", () => {
  it("renders the jobs entry point at /", async () => {
    const { document } = await renderRootRoute();
    const page = within(document.body);

    expect(page.getByRole("main")).toBeInTheDocument();
    expect(
      page.getByRole("link", { name: "공고 보기" }),
    ).toHaveAttribute("href", "/jobs");
  });
});

describe("SSR content security policy", () => {
  it("returns a nonce-only script policy", async () => {
    const { router } = await renderRootRoute();
    const rootMatch = router.stores.matches
      .get()
      .find((match) => match.routeId === "__root__");
    const nonce = router.options.ssr?.nonce;
    const policy = rootMatch?.headers?.["Content-Security-Policy"];

    expect(nonce).toBeTruthy();
    expect(policy).toContain(`script-src 'nonce-${nonce}'`);
    expect(policy).not.toContain("'unsafe-eval'");
    expect(policy).not.toContain("'unsafe-inline'");
  });

  it("puts the request nonce on every rendered script", async () => {
    const { document, router } = await renderRootRoute();
    const scripts = [...document.querySelectorAll("script")];

    expect(scripts.length).toBeGreaterThan(0);
    expect(scripts.every((script) => script.nonce === router.options.ssr?.nonce))
      .toBe(true);
  });

  it("creates a unique nonce for each router request scope", () => {
    expect(getRouter().options.ssr?.nonce).not.toBe(
      getRouter().options.ssr?.nonce,
    );
  });

  it("reuses the server nonce while hydrating in the browser", () => {
    const browser = new JSDOM(
      '<meta property="csp-nonce" content="server-request-nonce">',
    );
    vi.stubGlobal("document", browser.window.document);

    try {
      expect(getCspNonce()).toBe("server-request-nonce");
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe("the pinned server-function compatibility boundary", () => {
  it("preserves TanStack's legacy action URL generation and parsing", () => {
    const url = serverFunctionActionUrl("finds/legacy", "hello world");

    expect(url).toBe(
      "/_server/finds%2Flegacy?args=%5B%22hello%20world%22%5D",
    );
    expect(parseServerFunctionActionUrl(`https://finds.team${url}`)).toBe(
      "finds/legacy",
    );
  });

  it("leaves Solid Web rc.9 GET URLs on the native data path", () => {
    const reference = createServerReference(
      registerServerReference("finds/native", async (value: string) => value),
    );
    const read = GET(reference);

    expect(serverFunctionUrl(read, "hello world")).toBe(
      "/_server/data/finds%2Fnative?args=%5B%22hello%20world%22%5D",
    );
  });
});
