// Driven by AuthenticationBrowserSmokeTest. Never served or bundled with the application.
import { chromium } from "@playwright/test";
import { createInterface } from "node:readline";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const lines = createInterface({ input: process.stdin })[Symbol.asyncIterator]();
const config = JSON.parse((await lines.next()).value);
assert.match(config.origin, /^https:\/\/localhost:\d+$/);
const browser = await chromium.launch();
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(`${config.origin}/auth/csrf`);
  assert.equal(await page.evaluate(() => window.isSecureContext), true);
  const cdp = await context.newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", { options: {
    protocol: "ctap2", transport: "internal", hasResidentKey: true,
    hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true,
  } });
  let token;
  async function call(path, body, key = randomUUID(), csrf = token) {
    return page.evaluate(async ({ path, body, key, csrf }) => {
      const response = await fetch(path, { method: body === undefined ? "GET" : "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key,
          ...(csrf ? { "X-CSRF-TOKEN": csrf } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      return { status: response.status, data: await response.json() };
    }, { path, body, key, csrf });
  }
  async function refresh() {
    const response = await call("/auth/csrf"); assert.equal(response.status, 200); token = response.data.token;
  }
  async function cookie() {
    const found = (await context.cookies()).find(c => c.name === "JSESSIONID");
    assert.ok(found?.secure && found.httpOnly && found.sameSite === "Lax"); return found.value;
  }
  async function delivered(purpose) {
    const response = await call(`/auth/${purpose}/otp/request`, { email: config.email });
    assert.equal(response.status, 202); assert.deepEqual(response.data, { accepted: true });
    process.stdout.write(`otp:${purpose}\n`);
    const otp = (await lines.next()).value; assert.ok(/^\d{8}$/.test(otp), "recorded OTP must have eight digits"); return otp;
  }
  async function register() {
    const options = await call("/webauthn/register/options", {}); assert.equal(options.status, 200);
    assert.equal(options.data.authenticatorSelection.residentKey, "required");
    const credential = await page.evaluate(async options => {
      const created = await navigator.credentials.create({ publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options) });
      return created.toJSON();
    }, options.data);
    const body = { publicKey: { label: "Browser test", credential } }; const key = randomUUID();
    const completed = await call("/webauthn/register", body, key); assert.equal(completed.status, 200);
    assert.ok(/^[A-Z2-7]{5}(?:-[A-Z2-7]{5}){3}-[A-Z2-7]{6}$/.test(completed.data.recoveryCode), "128-bit recovery code format");
    const replay = await call("/webauthn/register", body, key);
    assert.equal(replay.status, 200); assert.equal(replay.data.recoveryCode, undefined);
    return { recoveryCode: completed.data.recoveryCode, credentialId: credential.id };
  }
  async function login() {
    const options = await call("/webauthn/authenticate/options", {}); assert.equal(options.status, 200);
    assert.deepEqual(options.data.allowCredentials, []); assert.equal(options.data.userVerification, "required");
    const credential = await page.evaluate(async options => {
      const assertion = await navigator.credentials.get({ publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options) });
      return assertion.toJSON();
    }, options.data);
    const before = await cookie();
    const response = await call("/login/webauthn", credential);
    if (response.status === 200) { assert.notEqual(await cookie(), before); await refresh(); }
    return response.status;
  }
  await refresh(); const initialCookie = await cookie();
  const otp = await delivered("enrollment");
  assert.equal((await call("/auth/enrollment/otp/verify", { email: config.email, otp })).status, 200);
  assert.notEqual(await cookie(), initialCookie);
  assert.equal((await call("/webauthn/register/options", {})).status, 403); // old CSRF token
  await refresh();
  assert.equal((await call("/auth/session")).status, 401);
  assert.equal((await call("/graphql", { query: "{ jobPostings { totalCount } }" })).status, 403);
  const first = await register();
  assert.equal((await call("/auth/session")).status, 401);
  assert.equal(await login(), 200);
  const session = await call("/auth/session"); assert.equal(session.status, 200); assert.deepEqual(session.data.roles, ["USER"]);
  const saved = (await cdp.send("WebAuthn.getCredentials", { authenticatorId })).credentials;
  assert.equal(saved.length, 1); assert.equal(saved[0].isResidentCredential, true); assert.equal(saved[0].rpId, "localhost");
  const oldContext = await browser.newContext({ ignoreHTTPSErrors: true });
  await oldContext.addCookies(await context.cookies());
  const recoveryOtp = await delivered("recovery");
  assert.equal((await call("/auth/recovery/otp/verify", { email: config.email, otp: recoveryOtp })).status, 401);
  assert.equal((await call("/auth/session")).status, 200);
  assert.equal((await call("/auth/recovery/otp/verify", { email: config.email, otp: recoveryOtp, recoveryCode: first.recoveryCode })).status, 200);
  await refresh(); assert.equal((await call("/auth/session")).status, 401);
  await cdp.send("WebAuthn.clearCredentials", { authenticatorId });
  const replacement = await register(); assert.ok(replacement.recoveryCode !== first.recoveryCode, "recovery rotates the saved code");
  const fresh = (await cdp.send("WebAuthn.getCredentials", { authenticatorId })).credentials[0];
  assert.equal((await oldContext.request.get(`${config.origin}/auth/session`)).status(), 401);
  // Put the old private key back into the authenticator: server must reject its valid signature.
  await cdp.send("WebAuthn.clearCredentials", { authenticatorId });
  await cdp.send("WebAuthn.addCredential", { authenticatorId, credential: saved[0] });
  assert.equal(await login(), 401);
  await cdp.send("WebAuthn.clearCredentials", { authenticatorId });
  await cdp.send("WebAuthn.addCredential", { authenticatorId, credential: fresh });
  assert.equal(await login(), 200); assert.equal((await call("/auth/session")).status, 200);
  process.stdout.write("passed\n");
} finally { await browser.close(); process.stdin.destroy(); }
