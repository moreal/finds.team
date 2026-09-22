import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

const server = await import("../dist/server/server.js");
const handleRequest = server.handleRequest ?? server.default?.fetch;

function nonceFrom(policy) {
  return /(?:^|;)\s*script-src\s+'nonce-([^']+)'/.exec(policy)?.[1];
}

async function renderRoot() {
  const response = await handleRequest(new Request("https://finds.team/"));
  return { response, html: await response.text() };
}

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
