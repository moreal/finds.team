import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

// A missing heading, inaccessible control, hidden keyboard target, or overflowing
// route must fail independently of pixel rendering differences between hosts.
const routes = ['/jobs', '/jobs/job-1', '/companies/acme', '/skills/kotlin',
  '/join', '/login', '/recover', '/account/security', '/admin', '/admin/sites/failed', '/admin/audit'];

for (const path of routes) {
  test(`${path}: accessible, keyboard reachable, and visually contained`, async ({ page, context }, testInfo) => {
    await context.addCookies([
      { name: 'admin-role', value: 'ADMIN', url: 'http://127.0.0.1:4176' },
      { name: 'security-account', value: 'matrix-user', url: 'http://127.0.0.1:4176' },
    ]);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('main')).toBeVisible();
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)');
    if (path === '/jobs/job-1') {
      // Shared styles may arrive after route styles on a warm dev server or
      // client navigation. The filled CTA must not inherit ordinary link ink.
      await page.addStyleTag({ url: '/src/ui/foundations.css?direct' });
      const apply = page.getByRole('link', { name: /지원하기/ });
      const colors = await apply.evaluate(element => ({ text: getComputedStyle(element).color, fill: getComputedStyle(element).backgroundColor }));
      expect(colors.text, 'Application CTA text must remain visible over its filled background').not.toBe(colors.fill);
    }
    expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([]);
    // Tab from the document into actual route content, not only shared navigation.
    await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
    let reachedMain = false;
    for (let i = 0; i < 35; i++) {
      await page.keyboard.press('Tab');
      const focused = page.locator(':focus');
      await expect(focused).toBeVisible();
      reachedMain = await focused.evaluate(element => !!element.closest('main'));
      if (reachedMain) break;
    }
    expect(reachedMain).toBe(true);
    const first = await page.locator(':focus').evaluate(element => element.outerHTML);
    expect(await page.locator(':focus').evaluate(element => getComputedStyle(element).outlineStyle)).not.toBe('none');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    expect(await page.locator(':focus').evaluate(element => element.outerHTML)).toBe(first);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    expect(errors).toEqual([]);
    const screenshot = testInfo.outputPath('route-visual.png');
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });
    await testInfo.attach('route-visual', { path: screenshot, contentType: 'image/png' });

    const mobile = testInfo.project.name.includes('mobile');
    const target = path === '/jobs' ? mobile ? page.getByRole('button', { name: '필터 열기' }) : page.getByRole('complementary').getByLabel('검색어', { exact: true })
      : path === '/jobs/job-1' ? page.getByRole('link', { name: 'Acme', exact: true })
      : path === '/companies/acme' ? page.getByRole('link', { name: 'Backend engineer 1', exact: true })
      : path === '/skills/kotlin' ? page.getByRole('link', { name: 'Company 1', exact: true })
      : path === '/join' || path === '/recover' ? page.getByLabel('이메일', { exact: true })
      : path === '/login' ? page.getByRole('link', { name: /계정 복구/ })
      : path === '/account/security' ? page.getByLabel('새 Passkey 이름', { exact: true })
      : path === '/admin' ? page.getByRole('link', { name: '사이트 관리', exact: true })
      : path === '/admin/sites/failed' ? page.getByRole('button', { name: '지금 수집', exact: true })
      : page.getByLabel('행위자 ID', { exact: true });
    for (let i = 0; i < 80 && !await target.evaluate(element => element === document.activeElement); i++) await page.keyboard.press('Tab');
    await expect(target).toBeFocused();
    if (path === '/join' || path === '/recover') {
      await page.keyboard.type('not-an-email');
      await page.keyboard.press('Enter');
      await expect(target).toBeFocused();
      expect(await target.evaluate(element => (element as HTMLInputElement).validity.typeMismatch)).toBe(true);
    } else if (path === '/account/security') {
      await page.keyboard.type('Keyboard key');
      await page.keyboard.press('Tab');
      await expect(page.getByRole('button', { name: 'Passkey 추가', exact: true })).toBeFocused();
    } else if (path === '/jobs' && !mobile || path === '/admin/audit') {
      await page.keyboard.type('keyboard');
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(path === '/jobs' ? /q=keyboard/ : /actorUserId=keyboard/);
    } else if (path === '/jobs' || path === '/admin/sites/failed') {
      await page.keyboard.press('Enter');
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(target).toBeFocused();
    } else {
      const href = await target.getAttribute('href');
      await page.keyboard.press('Enter');
      await expect.poll(() => new URL(page.url()).pathname).toBe(new URL(href!, 'http://127.0.0.1:4176').pathname);
    }
  });
}
