import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test.use({ baseURL: 'http://127.0.0.1:4176' });
test.beforeEach(async ({ page }) => { page.on('pageerror', error => { console.error(error); }); });
async function asAdmin(context: any) {
  await context.addCookies([{ name: 'admin-role', value: 'ADMIN', domain: '127.0.0.1', path: '/' }]);
}
test('anonymous redirects and ordinary users cannot request operational data', async ({ page, context }) => {
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/login/);
  await context.addCookies([{ name: 'admin-role', value: 'USER', domain: '127.0.0.1', path: '/' }]);
  await page.goto('/admin/sites');
  await expect(page.getByRole('alert')).toContainText('접근 권한');
  await expect(page.getByRole('button', { name: '사이트 등록' })).toHaveCount(0);
  const requests = await (await page.request.get('/__admin-requests')).json();
  expect(requests.filter((r: any) => r.role !== 'ADMIN' && r.operation !== 'AdminOperationsViewerQuery')).toEqual([]);
});
test('SSR dashboard ranks attention and hydrates without browser data fetch', async ({ page, context }) => {
  await asAdmin(context);
  const browserQueries: string[] = [];
  page.on('request', r => { if (r.url().endsWith('/graphql')) browserQueries.push(r.postData() ?? ''); });
  const response = await page.goto('/admin');
  expect(await response!.text()).toContain('Failed source');
  await expect(page.locator('[data-status]')).toHaveText(['실패', '오래됨', '실행 중', '정상']);
  expect(browserQueries).toEqual([]);
  await page.getByRole('link', { name: '사이트 관리' }).click();
  await page.getByRole('link', { name: 'Failed source', exact: true }).click();
  await expect(page.getByRole('heading', { name: '수집 기록' })).toBeVisible();
  await expect(page.getByText('CRAWL_FAILED', { exact: true })).toBeVisible();
});
test('registration uncertain retry freezes key and inputs', async ({ page, context }) => {
  await asAdmin(context);
  await page.goto('/admin/sites');
  const inputs: any[] = [];
  await page.route('**/graphql', async route => {
    const body = route.request().postDataJSON();
    if (body.operationName !== 'AdminOperationsRegisterMutation') return route.continue();
    inputs.push(body.variables.input);
    if (inputs.length === 1) return route.abort();
    return route.fulfill({ json: { data: { registerCareerSite: { site: { id: 'new', slug: 'new', displayName: 'New source' }, error: null, clientMutationId: null } } } });
  });
  await page.getByRole('button', { name: '사이트 등록', exact: true }).click();
  await page.getByLabel('사이트 이름', { exact: true }).fill('New source');
  await page.getByLabel('채용 페이지 URL').fill('https://example.com/jobs');
  await page.getByRole('button', { name: '등록 확인' }).click();
  await page.getByRole('button', { name: '같은 요청 다시 시도' }).click();
  await expect(page.getByRole('status')).toContainText('등록했어요');
  expect(inputs).toHaveLength(2);
  expect(inputs[1]).toEqual(inputs[0]);
  expect(inputs[0].idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
});
test('crawl requires confirmation and retries exactly the same command', async ({ page, context }) => {
  await asAdmin(context);
  await page.goto('/admin/sites/failed');
  const inputs: any[] = [];
  await page.route('**/graphql', async route => {
    const body = route.request().postDataJSON();
    if (body.operationName !== 'AdminOperationsTriggerMutation') return route.continue();
    inputs.push(body.variables.input);
    if (inputs.length === 1) return route.abort();
    return route.fulfill({ json: { data: { triggerCrawl: { outcome: 'SUCCEEDED', runId: 'same-run', error: null, clientMutationId: null } } } });
  });
  await page.getByRole('button', { name: '지금 수집' }).click();
  expect(inputs).toEqual([]);
  await page.getByRole('button', { name: '수집 확인' }).click();
  await page.getByRole('button', { name: '같은 요청 다시 시도' }).click();
  await expect(page.getByRole('status')).toContainText('same-run');
  expect(inputs[1]).toEqual(inputs[0]);
});
test('audit filters survive reload and are submitted to the server', async ({ page, context }) => {
  await asAdmin(context);
  await page.goto('/admin/audit');
  await page.getByLabel('행위자 ID').fill('actor-1');
  await page.getByLabel('대상 ID').fill('site-1');
  await page.getByRole('button', { name: '필터 적용' }).click();
  await expect(page).toHaveURL(/actorUserId=actor-1/);
  await page.reload();
  await expect(page.getByLabel('대상 ID')).toHaveValue('site-1');
  await expect(page.getByRole('heading', { name: 'MANUAL_CRAWL_TRIGGERED', exact: true })).toBeVisible();
});

test('revoked administrator is forbidden before any mutation and protected content disappears', async ({ page, context }) => {
  await asAdmin(context); await page.goto('/admin/sites/failed');
  await page.getByRole('button', { name: '지금 수집' }).click();
  let writes = 0;
  await page.route('**/graphql', route => {
    const body = route.request().postDataJSON();
    if (body.operationName === 'AdminOperationsViewerQuery') return route.fulfill({ status: 403, json: {} });
    writes++; return route.continue();
  });
  await page.getByRole('button', { name: '수집 확인' }).click();
  await expect(page.getByRole('alert')).toContainText('접근 권한');
  await expect(page.getByRole('heading', { name: '수집 기록' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Passkey 인증 후 계속' })).toHaveCount(0);
  expect(writes).toBe(0);
});

for (const fault of ['http-forbidden', 'graphql-forbidden', 'semantic-forbidden']) {
  test(`query ${fault} is forbidden, never a step-up prompt`, async ({ page, context }) => {
    await asAdmin(context);
    await context.addCookies([{ name: 'admin-fault', value: fault, domain: '127.0.0.1', path: '/' }]);
    await page.goto('/admin');
    await expect(page.getByRole('alert')).toContainText('접근 권한');
    await expect(page.getByText('Failed source', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Passkey 인증 후 계속' })).toHaveCount(0);
  });
}

test('recent authentication resumes the confirmed crawl with the same input and key', async ({ page, context }) => {
  await context.addCookies([{ name: 'admin-role', value: 'ADMIN', domain: 'localhost', path: '/' }]); await page.goto('http://localhost:4176/admin/sites/failed');
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });
  await page.evaluate(async () => { await navigator.credentials.create({ publicKey: { challenge: new Uint8Array([1, 2, 3]), rp: { id: 'localhost', name: 'finds.team' }, user: { id: new Uint8Array([1]), name: 'admin@example.com', displayName: 'Administrator' }, pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' } } }); });
  let authenticated = false;
  const inputs: any[] = [];
  await page.route('**/webauthn/authenticate/options', route => route.fulfill({ json: { challenge: 'AQIDBA', rpId: 'localhost', userVerification: 'required', allowCredentials: [] } }));
  await page.route('**/login/webauthn', route => { authenticated = true; return route.fulfill({ json: { authenticated: true } }); });
  await page.route('**/graphql', route => {
    const body = route.request().postDataJSON();
    if (body.operationName !== 'AdminOperationsTriggerMutation') return route.continue();
    inputs.push(body.variables.input);
    return route.fulfill({ json: { data: { triggerCrawl: { outcome: authenticated ? 'SUCCEEDED' : 'FORBIDDEN', runId: authenticated ? 'resumed-run' : null, error: authenticated ? null : { code: 'FORBIDDEN', message: 'private' }, clientMutationId: null } } } });
  });
  await page.getByRole('button', { name: '지금 수집' }).click();
  await page.getByRole('button', { name: '수집 확인' }).click();
  await page.getByRole('button', { name: 'Passkey 인증 후 계속' }).click();
  await expect(page.getByRole('status')).toContainText('resumed-run');
  expect(inputs).toHaveLength(2); expect(inputs[1]).toEqual(inputs[0]);
});

test('site pagination retains earlier results on failure and retries the cursor', async ({ page, context }) => {
  await asAdmin(context);
  await context.addCookies([{ name: 'admin-fault', value: 'pagination', domain: '127.0.0.1', path: '/' }]);
  await page.goto('/admin/sites');
  let attempts = 0;
  await page.route('**/graphql', async route => {
    const body = route.request().postDataJSON();
    if (body.operationName !== 'AdminOperationsSitesQuery') return route.continue();
    expect(body.variables.after).toBe('healthy');
    if (++attempts === 1) return route.abort();
    return route.continue();
  });
  await page.getByRole('button', { name: '더 보기' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Healthy source' })).toBeVisible();
  await page.getByRole('button', { name: '더 보기' }).click();
  await expect(page.getByRole('link', { name: 'Failed source', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Healthy source', exact: true })).toHaveCount(1);
});

test('admin pages fit mobile and have accessible controls', async ({ page, context }) => {
  await asAdmin(context); await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/audit');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test('site name filter is URL backed and names its loaded-page scope', async ({ page, context }) => {
  await asAdmin(context); await page.goto('/admin/sites');
  await page.getByLabel('사이트 이름 검색').fill('Failed');
  await page.getByRole('button', { name: '사이트 필터 적용' }).click();
  await expect(page).toHaveURL(/q=Failed/);
  await expect(page.getByRole('link', { name: 'Failed source', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Healthy source', exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel('사이트 이름 검색')).toHaveValue('Failed');
  await expect(page.getByText(/불러온 사이트에서 검색/)).toBeVisible();
});

test('two submit events in one turn create only one registration command', async ({ page, context }) => {
  await asAdmin(context); await page.goto('/admin/sites');
  const inputs: unknown[] = [];
  await page.route('**/graphql', async route => {
    const body = route.request().postDataJSON();
    if (body.operationName !== 'AdminOperationsRegisterMutation') return route.continue();
    inputs.push(body.variables.input);
    return route.fulfill({ json: { data: { registerCareerSite: { site: { id: 'new', slug: 'new', displayName: 'New source' }, error: null, clientMutationId: null } } } });
  });
  await page.getByRole('button', { name: '사이트 등록', exact: true }).click();
  await page.getByLabel('사이트 이름', { exact: true }).fill('New source');
  await page.getByLabel('채용 페이지 URL').fill('https://example.com/jobs');
  await page.getByRole('dialog').locator('form').evaluate(form => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  await expect(page.getByRole('status')).toContainText('등록했어요');
  expect(inputs).toHaveLength(1);
});
