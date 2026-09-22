import assert from "node:assert/strict";
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
  assert.ok(firstNonce);
  assert.ok(secondNonce);
  assert.notEqual(firstNonce, secondNonce);
  assert.doesNotMatch(firstPolicy, /unsafe-eval|unsafe-inline/);
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
