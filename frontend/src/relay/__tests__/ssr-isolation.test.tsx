import { renderToString } from "@solidjs/web";
import { fetchQuery } from "relay-runtime";
import { afterEach, expect, it, vi } from "vitest";

import { createServerRelayEnvironment } from "../environment";
import { viewer } from "../AccountOperations";
import { RelayEnvironmentProvider } from "../RelayRoot";
import { probeQuery, probeResponse, RelayProbe } from "./RelayProbe";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it("isolates compiled viewer records when authenticated and anonymous SSR requests overlap", async () => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const pending = new Map<string, (response: Response) => void>();
  vi.stubGlobal("fetch", vi.fn((_url, init) => new Promise<Response>((resolve) => {
    pending.set(new Headers(init.headers).get("cookie") ?? "anonymous", resolve);
  })));
  const requests = ["account-a", "account-b", "anonymous"].map((marker) => {
    const environment = createServerRelayEnvironment(new Request("https://finds.team/account", {
      headers: marker === "anonymous" ? {} : { cookie: marker },
    }));
    return { marker, environment, done: fetchQuery(environment, viewer, { first: 1 }).toPromise() };
  });
  const connection = (node: object) => ({
    edges: [{ cursor: "cursor", node }], totalCount: 1, error: null,
    pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: "cursor", endCursor: "cursor" },
  });
  for (const marker of ["account-b", "account-a"]) pending.get(marker)!(Response.json({ data: { viewer: {
    user: { id: marker, roles: ["USER"] },
    passkeys: connection({ id: `${marker}-key`, label: `${marker} laptop`, createdAt: "2026-09-22T00:00:00Z", lastUsedAt: null }),
    sessions: connection({ id: `${marker}-session`, current: true, createdAt: "2026-09-22T00:00:00Z", expiresAt: "2026-09-23T00:00:00Z" }),
  } } }));
  pending.get("anonymous")!(Response.json({ data: { viewer: null } }));
  await Promise.all(requests.map(({ done }) => done));
  for (const { marker, environment } of requests) {
    const records = environment.getStore().getSource();
    const serialized = JSON.stringify(records.toJSON());
    if (marker !== "anonymous") expect(records.get(`${marker}-key`)?.label).toBe(`${marker} laptop`);
    for (const other of ["account-a", "account-b"].filter((name) => name !== marker)) {
      expect(serialized).not.toContain(other);
    }
  }
});

it("keeps records private when requests for the same record overlap", async () => {
  vi.stubEnv("FINDS_INTERNAL_GRAPHQL_URL", "http://backend.test/graphql");
  const pending = new Map<string, (response: Response) => void>();
  vi.stubGlobal("fetch", vi.fn((_url, init) => new Promise<Response>((resolve) => {
    pending.set(new Headers(init.headers).get("cookie")!, resolve);
  })));

  async function renderRequest(marker: string) {
    const environment = createServerRelayEnvironment(new Request("https://finds.team/jobs", {
      headers: { cookie: marker },
    }));
    await fetchQuery(environment, probeQuery, {}).toPromise();
    return {
      environment,
      html: renderToString(() => (
        <RelayEnvironmentProvider environment={environment}>
          <RelayProbe />
        </RelayEnvironmentProvider>
      ), { manifest: {}, nonce: `request-${marker}` }),
      records: JSON.stringify(environment.getStore().getSource().toJSON()),
    };
  }

  const first = renderRequest("private-a");
  const second = renderRequest("private-b");
  expect(pending.size).toBe(2);
  pending.get("private-b")!(Response.json(probeResponse("private-b")));
  pending.get("private-a")!(Response.json(probeResponse("private-a")));
  const [a, b] = await Promise.all([first, second]);

  expect(a.environment).not.toBe(b.environment);
  expect(a.html).toContain("private-a");
  expect(a.html).not.toContain("private-b");
  expect(b.html).toContain("private-b");
  expect(b.html).not.toContain("private-a");
  expect(a.records).not.toContain("private-b");
  expect(b.records).not.toContain("private-a");
});
