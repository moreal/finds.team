import { afterEach, expect, it, vi } from "vitest";
import type { RequestParameters } from "relay-runtime";

import { createBrowserNetwork, createServerNetwork } from "../network";

const query: RequestParameters = {
  cacheID: "test-query", id: null, metadata: {}, name: "TestQuery",
  operationKind: "query", text: "query TestQuery { jobPostings { totalCount } }",
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it('refreshes CSRF for each browser mutation, including after session rotation', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(Response.json({ token: 'fresh', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ data: {} }))
    .mockResolvedValueOnce(Response.json({ token: 'rotated', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ data: {} }));
  vi.stubGlobal('fetch', fetch);
  await createBrowserNetwork().execute({ ...query, operationKind: 'mutation' }, {}, {}).toPromise();
  expect(fetch.mock.calls[0][0]).toBe('/auth/csrf');
  expect(new Headers(fetch.mock.calls[1][1].headers).get('X-CSRF-TOKEN')).toBe('fresh');
  await createBrowserNetwork().execute({ ...query, operationKind: 'mutation' }, {}, {}).toPromise();
  expect(new Headers(fetch.mock.calls[3][1].headers).get('X-CSRF-TOKEN')).toBe('rotated');
});

it.each(["query", "mutation"] as const)("forwards only allowed headers for a server %s", async (operationKind) => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const fetch = vi.fn().mockResolvedValue(Response.json({ data: {} }));
  vi.stubGlobal("fetch", fetch);
  const network = createServerNetwork(new Request("https://finds.team/jobs", {
    headers: {
      cookie: "session=a", "accept-language": "ko", "x-request-id": "request-a",
      "x-csrf-token": "csrf-a", authorization: "do-not-forward", "x-forwarded-host": "untrusted",
    },
  }));
  await network.execute({ ...query, operationKind }, { first: 1 }, {}).toPromise();
  const [url, init] = fetch.mock.calls[0]!;

  expect(url).toBe("http://backend.test/graphql");
  expect(Object.fromEntries(new Headers(init.headers))).toEqual({
    "content-type": "application/json", accept: "application/graphql-response+json, application/json",
    cookie: "session=a", "accept-language": "ko", "x-request-id": "request-a",
    ...(operationKind === "mutation" ? { "x-csrf-token": "csrf-a" } : {}),
  });
  expect(JSON.parse(init.body)).toEqual({ query: query.text, variables: { first: 1 }, operationName: "TestQuery" });
});

it("uses a relative same-origin browser request", async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ data: {} }));
  vi.stubGlobal("fetch", fetch);
  await createBrowserNetwork().execute(query, {}, {}).toPromise();

  expect(fetch).toHaveBeenCalledWith("/graphql", expect.objectContaining({
    method: "POST", credentials: "same-origin",
  }));
});

it("rejects a failed HTTP response instead of accepting its body as query data", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("unavailable", { status: 503 })));
  await expect(createBrowserNetwork().execute(query, {}, {}).toPromise()).rejects.toThrow("503");
});

it("preserves HTTP authorization status and correlation id for safe route error states", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("private provider detail", { status: 403, headers: { "x-request-id": "request-forbidden" } })));
  await expect(createBrowserNetwork().execute(query, {}, {}).toPromise()).rejects.toMatchObject({
    status: 403, correlationId: "request-forbidden",
  });
});
