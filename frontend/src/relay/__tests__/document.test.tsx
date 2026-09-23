import { createMemoryHistory } from "@tanstack/solid-router";
import { attachRouterServerSsrUtils, renderRouterToString, RouterServer } from "@tanstack/solid-router/ssr/server";
import { JSDOM } from "jsdom";
import { fetchQuery } from "relay-runtime";
import { afterEach, expect, it, vi } from "vitest";

import { getRouter } from "../../router";
import { probeQuery, probeResponse } from "./RelayProbe";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it("restores the router's Relay records before consumers execute the initial query", async () => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const fetch = vi.fn().mockResolvedValue(Response.json(probeResponse("router-hydration")));
  vi.stubGlobal("fetch", fetch);
  const server = getRouter(new Request("https://finds.team/"));
  await fetchQuery(server.options.context.relayEnvironment, probeQuery, {}).toPromise();
  const snapshot = JSON.parse(JSON.stringify(await server.options.dehydrate!()));
  const client = getRouter(new Request("https://finds.team/"));
  fetch.mockClear();

  await client.options.hydrate!(snapshot);
  const result = await fetchQuery(client.options.context.relayEnvironment, probeQuery, {}, { fetchPolicy: "store-or-network" }).toPromise();

  expect(result).toEqual(probeResponse("router-hydration").data);
  expect(fetch).not.toHaveBeenCalled();
});

it("serializes only records through the nonce-protected Start document boundary", async () => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const marker = '</script><script id="injected">globalThis.compromised=true</script>';
  vi.stubGlobal("fetch", vi.fn().mockImplementation((_url, init) => {
    const operation = JSON.parse(init.body).operationName;
    return Promise.resolve(Response.json(operation === "RelayProbeQuery" ? probeResponse(marker) : {
      data: { jobPostings: { edges: [], totalCount: 0, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: null, endCursor: null } } },
    }));
  }));
  const router = getRouter(new Request("https://finds.team/", { headers: { cookie: "private-session" } }));
  router.update({ context: router.options.context, history: createMemoryHistory({ initialEntries: ["/jobs"] }) });
  attachRouterServerSsrUtils({ router, manifest: undefined });
  await router.load();
  await fetchQuery(router.options.context!.relayEnvironment, probeQuery, {}).toPromise();
  const snapshot = await router.options.dehydrate!();
  expect(Object.keys(snapshot)).toEqual(["relayRecords"]);
  expect(snapshot.relayRecords).toEqual(router.options.context!.relayEnvironment.getStore().getSource().toJSON());

  await router.serverSsr!.dehydrate();
  const response = renderRouterToString({
    router, responseHeaders: new Headers(), manifest: {},
    children: () => <RouterServer router={router} />,
  });
  const html = await response.text();
  const document = new JSDOM(html).window.document;
  expect(response.status).toBe(200);
  expect(html).toContain("relayRecords");
  expect(html).not.toContain("private-session");
  expect(html).not.toContain(marker);
  expect(document.querySelector("#injected")).toBeNull();
  expect([...document.querySelectorAll("script")].every((script) => script.nonce === router.options.ssr!.nonce)).toBe(true);
});
