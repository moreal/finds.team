import { resolve } from "node:path";
import { runInContext } from "node:vm";
import { JSDOM, VirtualConsole } from "jsdom";
import { fetchQuery } from "relay-runtime";
import { build } from "vite";
import relay from "vite-plugin-relay-lite";
import solid from "vite-plugin-solid";
import { afterEach, expect, it, vi } from "vitest";

import { createServerRelayEnvironment } from "../environment";
import { probeQuery, probeResponse } from "./RelayProbe";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

async function buildFixture(server: boolean) {
  // Vitest deliberately disables Solid hydration markers. Build both production
  // conditions so this test exercises the actual SSR/client hydration contract.
  const output = await build({
    configFile: false,
    root: resolve(import.meta.dirname, "../../.."),
    plugins: [relay({ codegen: false }), solid({ ssr: true })],
    logLevel: "silent",
    resolve: { alias: { "solid-js/web": "@solidjs/web" } },
    define: { "process.env.NODE_ENV": '"production"' },
    ssr: { noExternal: true },
    build: {
      write: false,
      minify: false,
      ...(server
        ? { ssr: resolve(import.meta.dirname, "hydrate-server.tsx") }
        : { lib: { entry: resolve(import.meta.dirname, "hydrate-client.tsx"), formats: ["iife" as const], name: "RelayHydrationFixture" } }),
    },
  });
  if ("close" in output) throw new Error("Expected a one-shot build");
  const code = (Array.isArray(output) ? output[0] : output).output.find((chunk) => chunk.type === "chunk")!;
  if (code.type !== "chunk") throw new Error("Expected a JavaScript bundle");
  return code.code;
}

it("hydrates the existing SSR DOM from restored records without a duplicate initial query", async () => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const fetch = vi.fn().mockResolvedValue(Response.json(probeResponse("from-server")));
  vi.stubGlobal("fetch", fetch);
  const server = createServerRelayEnvironment(new Request("https://finds.team/jobs"));
  await fetchQuery(server, probeQuery, {}).toPromise();
  const records = JSON.stringify(server.getStore().getSource().toJSON());
  const [serverCode, clientCode] = await Promise.all([buildFixture(true), buildFixture(false)]);
  const serverFixture = await import(/* @vite-ignore */ `data:text/javascript;base64,${Buffer.from(serverCode).toString("base64")}`);
  const html = serverFixture.renderProbe(server);

  const errors: unknown[] = [];
  const browser = new JSDOM(`<!doctype html>${html}`, {
    url: "https://finds.team/", runScripts: "outside-only",
    virtualConsole: new VirtualConsole().on("error", (error) => errors.push(error)).on("warn", (warning) => errors.push(warning)),
  });
  const original = browser.window.document.querySelector("p");
  fetch.mockClear();
  browser.window.fetch = fetch;
  try {
    const vm = browser.getInternalVMContext();
    for (const script of browser.window.document.querySelectorAll("script")) {
      runInContext(script.textContent ?? "", vm);
    }
    runInContext(clientCode, vm);
    const fixture = (browser.window as unknown as {
      RelayHydrationFixture: { hydrateProbe(records: Record<string, unknown>): Promise<() => void> };
    }).RelayHydrationFixture;
    const dispose = await fixture.hydrateProbe(JSON.parse(records));
    expect(browser.window.document.querySelector("p")).toBe(original);
    expect(original?.textContent).toBe("from-server");
    expect(fetch).not.toHaveBeenCalled();
    expect(errors).toEqual([]);
    dispose();
  } finally {
    browser.window.close();
  }
}, 20_000);
