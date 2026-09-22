import { renderToString } from "@solidjs/web";
import { fetchQuery } from "relay-runtime";
import { afterEach, expect, it, vi } from "vitest";

import { createServerRelayEnvironment } from "../environment";
import { RelayEnvironmentProvider } from "../RelayRoot";
import { probeQuery, probeResponse, RelayProbe } from "./RelayProbe";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
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
