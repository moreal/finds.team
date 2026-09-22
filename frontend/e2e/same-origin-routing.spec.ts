import { expect, test } from "@playwright/test";

const publicOrigin = process.env.FINDS_PUBLIC_ORIGIN ?? "http://127.0.0.1:8080";

test("page routes reach the Start production server", async ({ request }) => {
  const response = await request.get(`${publicOrigin}/jobs`);

  expect(response.status()).toBe(404);
  expect(response.headers()["content-type"]).toMatch(/^text\/html(?:;|$)/);
  expect(response.headers()["x-finds-upstream"]).toBe("frontend");
});

test("browser GraphQL stays same-origin and bypasses Start", async ({ page }) => {
  await page.goto(`${publicOrigin}/jobs`);

  const result = await page.evaluate(async () => {
    const response = await fetch("/graphql", {
      method: "POST",
      headers: {
        accept: "application/graphql-response+json, application/json",
        "content-type": "application/json",
        "x-request-id": "same-origin-routing-test",
      },
      body: JSON.stringify({ query: "{ __typename }" }),
    });

    return {
      body: await response.json(),
      contentType: response.headers.get("content-type"),
      corsOrigin: response.headers.get("access-control-allow-origin"),
      status: response.status,
      upstream: response.headers.get("x-finds-upstream"),
      url: response.url,
    };
  });

  expect(new URL(result.url).origin).toBe(publicOrigin);
  expect(result.status).toBe(200);
  expect(result.contentType).toMatch(/^application\/json(?:;|$)/);
  expect(result.upstream).toBe("backend");
  expect(result.corsOrigin).toBeNull();
  expect(result.body).toEqual({ data: { __typename: "Query" } });
});

test("backend-owned auth prefixes bypass Start", async ({ request }) => {
  for (const path of ["/auth/session", "/webauthn/registration"]) {
    const response = await request.get(`${publicOrigin}${path}`, {
      headers: { accept: "application/json" },
    });

    expect(response.status()).toBe(404);
    expect(response.headers()["content-type"]).toMatch(/^application\/json(?:;|$)/);
    expect(response.headers()["x-finds-upstream"]).toBe("backend");
  }
});
