import { expect, test } from "@playwright/test";

const publicOrigin = process.env.FINDS_PUBLIC_ORIGIN ?? "http://127.0.0.1:8080";

test("Start root renders and loads a production script", async ({ page }) => {
  const browserErrors: string[] = [];
  const scriptResponses = new Map<string, { status: number; contentType: string | undefined }>();

  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("response", (response) => {
    if (response.request().resourceType() === "script") {
      scriptResponses.set(response.url(), {
        status: response.status(),
        contentType: response.headers()["content-type"],
      });
    }
  });

  const response = await page.goto(`${publicOrigin}/`);
  expect(response?.status()).toBe(200);
  expect(response?.headers()["x-finds-upstream"]).toBe("frontend");
  await expect(page.getByRole("main")).toContainText("finds.team");
  await expect(page.getByRole("link", { name: "공고 보기" })).toHaveAttribute("href", "/jobs");

  const html = await response!.text();
  const scriptPath = html.match(/<script\b[^>]*\bsrc="([^"]+\.js)"[^>]*>/)?.[1];
  expect(scriptPath).toBeTruthy();
  const scriptUrl = new URL(scriptPath!, publicOrigin).href;
  expect(new URL(scriptUrl).origin).toBe(new URL(publicOrigin).origin);
  await page.waitForLoadState("networkidle");
  expect(scriptResponses.get(scriptUrl)?.status).toBe(200);
  expect(scriptResponses.get(scriptUrl)?.contentType).toMatch(/^(?:text|application)\/javascript(?:;|$)/);
  expect(browserErrors).toEqual([]);
});

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
