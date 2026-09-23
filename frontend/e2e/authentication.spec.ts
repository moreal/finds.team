import { expect, test, type Page } from '@playwright/test';

test.use({ baseURL: 'http://localhost:4175' });
const recoveryCode = 'ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567';
async function authFixture(page: Page) {
  let registered: string | undefined;
  let csrfEpoch = 0;
  let authenticated = false;
  let graphqlRequests = 0;
  await page.route('**/graphql', async route => {
    graphqlRequests++;
    expect(authenticated).toBe(true);
    const { operationName } = route.request().postDataJSON();
    if (operationName === 'AccountOperationsRotateMutation') {
      expect(route.request().headers()['x-csrf-token']).toBe(`csrf-${csrfEpoch}`);
      return route.fulfill({ json: { data: { rotateRecoveryCode: { outcome: 'ROTATED', recoveryCode, error: null, clientMutationId: null } } } });
    }
    const empty = { edges: [], totalCount: 0, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: null, endCursor: null } };
    return route.fulfill({ json: { data: { viewer: { user: { id: 'account-1', roles: ['USER'] }, passkeys: empty, sessions: empty } } } });
  });
  await page.route('**/auth/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/auth/csrf') return route.fulfill({ json: { token: `csrf-${csrfEpoch}`, headerName: 'X-CSRF-TOKEN' } });
    expect(route.request().headers()['x-csrf-token']).toBe(`csrf-${csrfEpoch}`);
    const body = route.request().postDataJSON();
    if (path.endsWith('/request')) {
      expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
      return route.fulfill({ status: 202, json: { accepted: true } });
    }
    if (body.otp !== '123456' || (path.includes('recovery') && body.recoveryCode !== 'saved-recovery')) return route.fulfill({ status: 401, json: {} });
    csrfEpoch++;
    return route.fulfill({ json: { scope: path.includes('recovery') ? 'RECOVERY' : 'ENROLLMENT' } });
  });
  await page.route(/\/(?:webauthn\/.*|login\/webauthn)$/, async route => {
    expect(route.request().headers()['x-csrf-token']).toBe(`csrf-${csrfEpoch}`);
    const path = new URL(route.request().url()).pathname;
    if (path === '/webauthn/register/options') return route.fulfill({ json: {
      challenge: 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8', rp: { id: 'localhost', name: 'finds.team' },
      user: { id: 'AQIDBA', name: 'person@example.com', displayName: 'Account' },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
    } });
    if (path === '/webauthn/authenticate/options') return route.fulfill({ json: { challenge: 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA', rpId: 'localhost', userVerification: 'required', allowCredentials: [] } });
    const body = route.request().postDataJSON();
    if (path === '/webauthn/register') {
      expect(body.publicKey.credential.response.attestationObject).toBeTruthy();
      expect(body.publicKey.credential.response.clientDataJSON).toBeTruthy();
      expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
      registered = body.publicKey.credential.id;
      return route.fulfill({ json: { success: true, recoveryCode } });
    }
    expect(body.response.signature).toBeTruthy();
    if (body.id === registered) { csrfEpoch++; authenticated = true; }
    return route.fulfill({ status: body.id === registered ? 200 : 401, json: { authenticated: body.id === registered } });
  });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });
  return { cdp, authenticatorId, graphqlRequests: () => graphqlRequests };
}
async function verifyEmail(page: Page, mode = 'join') {
  await page.goto(`/${mode}`);
  await page.getByLabel('이메일', { exact: true }).fill('person@example.com');
  await page.getByRole('button', { name: '인증 코드 보내기' }).click();
  await page.getByLabel('이메일 인증 코드').fill('123456');
  if (mode === 'recover') await page.getByLabel('저장한 복구 코드').fill('saved-recovery');
  await page.getByRole('button', { name: '인증 확인' }).click();
}
test('enrollment uses a real virtual Passkey and keeps recovery material ephemeral until acknowledged', async ({ page }) => {
  const fixture = await authFixture(page);
  await verifyEmail(page);
  await page.getByRole('button', { name: 'Passkey 등록', exact: true }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
  expect(fixture.graphqlRequests()).toBe(0);
  await page.screenshot({ path: 'test-results/security-recovery.png', fullPage: true });
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(recoveryCode);
  await expect(page.getByRole('button', { name: '계속' })).toBeDisabled();
  const downloading = page.waitForEvent('download', { timeout: 5000 });
  await page.getByRole('button', { name: '파일로 저장' }).click();
  expect((await downloading).suggestedFilename()).toBe('finds-team-recovery-code.txt');
  const unload = page.waitForEvent('dialog', { timeout: 5000 }).then(async dialog => { expect(dialog.type()).toBe('beforeunload'); await dialog.dismiss(); });
  await Promise.all([unload, page.reload({ timeout: 3000 }).catch(() => undefined)]);
  await expect(page.getByText(recoveryCode)).toBeVisible();
  await page.getByLabel('복구 코드를 안전한 곳에 저장했어요').check();
  await page.getByRole('button', { name: '계속' }).click();
  await expect(page.getByRole('button', { name: 'Passkey로 로그인' })).toBeVisible();
  await expect(page.getByLabel('이메일', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Passkey로 로그인' }).click();
  await expect(page).toHaveURL(/account\/security/);
  await page.getByRole('button', { name: '복구 코드 새로 발급' }).click();
  await page.getByRole('button', { name: '발급 확인' }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
});

test('old credential is rejected after two-proof recovery replaces it', async ({ page }) => {
  const { cdp, authenticatorId } = await authFixture(page);
  await verifyEmail(page);
  await page.getByRole('button', { name: 'Passkey 등록', exact: true }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
  const old = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials[0];
  await page.getByLabel('복구 코드를 안전한 곳에 저장했어요').check();
  await page.getByRole('button', { name: '계속' }).click();
  await expect(page).toHaveURL(/login/);
  await verifyEmail(page, 'recover');
  await page.getByRole('button', { name: 'Passkey 등록', exact: true }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
  await page.getByLabel('복구 코드를 안전한 곳에 저장했어요').check();
  await page.getByRole('button', { name: '계속' }).click();
  await cdp.send('WebAuthn.clearCredentials', { authenticatorId });
  await cdp.send('WebAuthn.addCredential', { authenticatorId, credential: old });
  await page.getByRole('button', { name: 'Passkey로 로그인' }).click();
  await expect(page.getByRole('alert')).toContainText('확인하지 못했어요');
  await expect(page).toHaveURL(/login/);
});

test('browser cancellation has a retryable message distinct from credential validation', async ({ page }) => {
  await authFixture(page);
  await page.addInitScript(() => { navigator.credentials.get = async () => { throw new DOMException('User cancelled', 'NotAllowedError'); }; });
  await page.goto('/login');
  await page.getByRole('button', { name: 'Passkey로 로그인' }).click();
  await expect(page.getByRole('alert')).toContainText('취소');
  await expect(page.getByRole('button', { name: 'Passkey로 로그인' })).toBeEnabled();
});

test('restricted sessions denied by GraphQL never expose account records', async ({ page }) => {
  await page.route('**/graphql', route => route.fulfill({ status: 403, json: { title: 'Request rejected', status: 403 } }));
  await page.goto('/account/security');
  await expect(page.getByRole('alert')).toContainText('접근 권한이 없어요');
  await expect(page.getByRole('heading', { name: 'Passkey', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Passkey로 로그인' })).toBeVisible();
});
test('wrong OTP stays on proof form and recovery requires both proofs before replacement', async ({ page }) => {
  await authFixture(page);
  await page.goto('/recover');
  await page.getByLabel('이메일', { exact: true }).fill('person@example.com');
  await page.getByRole('button', { name: '인증 코드 보내기' }).click();
  await page.getByLabel('이메일 인증 코드').fill('000000');
  await page.getByLabel('저장한 복구 코드').fill('saved-recovery');
  await page.getByRole('button', { name: '인증 확인' }).click();
  await expect(page.getByRole('alert')).toContainText('코드');
  await expect(page.getByRole('button', { name: 'Passkey 등록', exact: true })).toHaveCount(0);
  await page.getByLabel('이메일 인증 코드').fill('123456');
  await page.getByLabel('저장한 복구 코드').fill('wrong');
  await page.getByRole('button', { name: '인증 확인' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByLabel('저장한 복구 코드').fill('saved-recovery');
  await page.getByRole('button', { name: '인증 확인' }).click();
  await page.getByRole('button', { name: 'Passkey 등록', exact: true }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
});

test('account management renames, confirms removal, rotates recovery material and revokes other sessions', async ({ page }) => {
  let label = 'Laptop'; let removed = false; let revoked = false;
  await page.route('**/auth/csrf', route => route.fulfill({ json: { token: 'fresh', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/graphql', async route => {
    const { operationName, variables } = route.request().postDataJSON();
    if (operationName === 'AccountOperationsViewerQuery') return route.fulfill({ json: { data: { viewer: {
      user: { id: 'user-1', roles: ['USER'] },
      passkeys: { edges: [...(removed ? [] : [{ cursor: 'key-1', node: { __typename: 'Passkey', id: 'key-1', label, createdAt: '2026-09-20T00:00:00Z', lastUsedAt: null } }]), { cursor: 'key-2', node: { __typename: 'Passkey', id: 'key-2', label: 'Backup', createdAt: '2026-09-20T00:00:00Z', lastUsedAt: null } }], totalCount: removed ? 1 : 2, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: 'key-1', endCursor: 'key-2' } },
      sessions: { edges: [{ cursor: 'session-1', node: { __typename: 'Session', id: 'session-1', createdAt: '2026-09-20T00:00:00Z', expiresAt: '2026-09-25T00:00:00Z', current: true } }, ...(revoked ? [] : [{ cursor: 'session-2', node: { __typename: 'Session', id: 'session-2', createdAt: '2026-09-20T00:00:00Z', expiresAt: '2026-09-25T00:00:00Z', current: false } }])], totalCount: revoked ? 1 : 2, error: null, pageInfo: { hasNextPage: false, hasPreviousPage: false, startCursor: 'session-1', endCursor: 'session-2' } },
    } } } });
    expect(route.request().headers()['x-csrf-token']).toBe('fresh');
    expect(variables.input.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    let field = '';
    if (operationName === 'AccountOperationsRenameMutation') { field = 'renamePasskey'; label = variables.input.label; }
    if (operationName === 'AccountOperationsRemoveMutation') { field = 'removePasskey'; removed = true; }
    if (operationName === 'AccountOperationsRevokeOthersMutation') { field = 'revokeOtherSessions'; revoked = true; }
    if (operationName === 'AccountOperationsRotateMutation') field = 'rotateRecoveryCode';
    return route.fulfill({ json: { data: { [field]: { outcome: field === 'rotateRecoveryCode' ? 'ROTATED' : 'CHANGED', error: null, clientMutationId: null, ...(field === 'rotateRecoveryCode' ? { recoveryCode } : {}) } } } });
  });
  await page.goto('/account/security');
  await expect(page.getByLabel('Laptop 이름')).toBeVisible();
  await page.screenshot({ path: 'test-results/security-account.png', fullPage: true });
  await page.getByLabel('Laptop 이름').fill('Phone');
  await page.getByRole('button', { name: '이름 저장' }).first().click();
  await expect(page.getByLabel('Phone 이름')).toBeVisible();
  await page.getByRole('button', { name: 'Phone 삭제' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  expect(removed).toBe(false);
  await page.getByRole('button', { name: '삭제 확인' }).click();
  await expect(page.getByLabel('Phone 이름')).toHaveCount(0);
  await expect(page.getByLabel('Backup 이름')).toBeVisible();
  await page.getByRole('button', { name: '다른 세션 모두 종료' }).click();
  await page.getByRole('button', { name: '종료 확인' }).click();
  await expect(page.getByText('다른 세션', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '복구 코드 새로 발급' }).click();
  await page.getByRole('button', { name: '발급 확인' }).click();
  await expect(page.getByText(recoveryCode)).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(recoveryCode);
});
