import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";

const server = await import("../dist/server/server.js");
const handleRequest = server.handleRequest ?? server.default?.fetch;

test("development catalog is absent from the production router and bundles", async () => {
  for (const path of ["/ui", "/__dev/ui"]) {
    const response = await handleRequest(new Request(`https://finds.team${path}`));
    assert.equal(response.status, 404);
    assert.doesNotMatch(await response.text(), /컴포넌트 카탈로그/);
  }
  for (const side of ["client", "server"]) {
    const directory = new URL(`../dist/${side}/`, import.meta.url);
    const files = await readdir(directory, { recursive: true });
    for (const file of files.filter((name) => name.endsWith(".js"))) {
      assert.doesNotMatch(await readFile(new URL(file, directory), "utf8"), /컴포넌트 카탈로그|최종 삭제 확인 예시/, join(side, file));
    }
  }
});

function nonceFrom(policy) {
  return /(?:^|;)\s*script-src\s+'nonce-([^']+)'/.exec(policy)?.[1];
}

async function renderRoot() {
  const response = await handleRequest(new Request("https://finds.team/jobs"));
  return { response, html: await response.text() };
}

test("the built root permanently redirects to jobs", async () => {
  const response = await handleRequest(new Request("https://finds.team/"));
  assert.equal(response.status, 308);
  assert.equal(response.headers.get("location"), "/jobs");
});

test("the built jobs document renders Relay data from the internal endpoint", async (t) => {
  const previous = process.env.FINDS_INTERNAL_GRAPHQL_URL;
  process.env.FINDS_INTERNAL_GRAPHQL_URL = "http://jobs-backend.test/graphql";
  t.after(() => { if (previous === undefined) delete process.env.FINDS_INTERNAL_GRAPHQL_URL; else process.env.FINDS_INTERNAL_GRAPHQL_URL = previous; });
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.equal(url, "http://jobs-backend.test/graphql");
    assert.equal(init.headers.get("cookie"), "session=built-ssr");
    assert.deepEqual(JSON.parse(init.body).variables.filter.all, [{ hasStatus: "OPEN" }, { textContains: "production" }]);
    return Response.json({ data: { jobPostings: {
      edges: [{ cursor: "one", node: { __typename: "JobPosting", id: "built-job", title: "Production SSR engineer", canonicalUrl: "https://example.com/job", status: "OPEN", updatedAt: "2026-09-20T00:00:00Z", careerSite: { id: "built-site", slug: "built-company", displayName: "Built Company" }, classification: null } }],
      totalCount: 1, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: "one", endCursor: "one" },
    } } });
  });
  const response = await handleRequest(new Request("https://finds.team/jobs?q=production", { headers: { cookie: "session=built-ssr" } }));
  const html = await response.text();
  assert.equal(response.status, 200);
  const document = new JSDOM(html).window.document;
  assert.equal(document.querySelector('a[href="/jobs/built-job"]')?.textContent, "Production SSR engineer");
  assert.equal(document.querySelector('a[href="/companies/built-company"]')?.textContent, "Built Company");
  assert.match(html, /relayRecords/);
  assert.doesNotMatch(html, /session=built-ssr/);
});

test("the built handler applies a unique strict CSP nonce to every script", async () => {
  const [first, second] = await Promise.all([renderRoot(), renderRoot()]);
  const firstPolicy = first.response.headers.get("content-security-policy");
  const secondPolicy = second.response.headers.get("content-security-policy");
  const firstNonce = nonceFrom(firstPolicy ?? "");
  const secondNonce = nonceFrom(secondPolicy ?? "");

  assert.equal(first.response.status, 200);
  assert.match(first.html, /<main\b/);
  assert.match(first.html, /href="\/jobs"/);
  assert.match(first.html, /relayRecords/);
  assert.ok(firstNonce);
  assert.ok(secondNonce);
  assert.notEqual(firstNonce, secondNonce);
  assert.doesNotMatch(firstPolicy, /strict-dynamic|unsafe-eval|unsafe-inline/);
  assert.match(
    first.html,
    new RegExp(
      `<meta property="csp-nonce" content="${firstNonce}"[^>]*>`,
    ),
  );

  const scripts = first.html.match(/<script\b[^>]*>/g) ?? [];
  assert.ok(scripts.length > 0);
  for (const script of scripts) {
    assert.match(script, new RegExp(`\\bnonce="${firstNonce}"`));
  }
});

test("the built document emits one hydration bootstrap", async () => {
  const { html } = await renderRoot();
  const bootstraps = html.match(/window\._\$HY\|\|/g) ?? [];

  assert.equal(bootstraps.length, 1);
});

test("production bundles require no dynamic evaluation or internal browser endpoint", async () => {
  for (const side of ["client", "server"]) {
    const directory = new URL(`../dist/${side}/`, import.meta.url);
    const files = await readdir(directory, { recursive: true });
    for (const file of files.filter((name) => name.endsWith(".js"))) {
      const code = await readFile(new URL(file, directory), "utf8");
      assert.doesNotMatch(code, /\beval\s*\(|\bnew\s+Function\s*\(/, join(side, file));
      if (side === "client") assert.doesNotMatch(code, /FINDS_INTERNAL_GRAPHQL_URL/, file);
    }
  }
});
