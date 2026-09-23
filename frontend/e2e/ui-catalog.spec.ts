import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/ui");
  await expect(page.getByRole("heading", { name: "컴포넌트 카탈로그" })).toBeVisible();
});

test("accessible catalog has no serious axe violations and fits its viewport", async ({ page }) => {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter(({ impact }) => impact === "serious" || impact === "critical")).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page).toHaveScreenshot("catalog.png", { fullPage: true });
});

test("native controls meet hit targets and expose focus, hover and active states", async ({ page }, info) => {
  const minimum = info.project.name.includes("mobile") ? 44 : 40;
  for (const control of await page.locator("button:visible, input:visible, a.ui-link:visible").all()) {
    const bounds = await control.boundingBox();
    expect(bounds?.height).toBeGreaterThanOrEqual(minimum);
    expect(bounds?.width).toBeGreaterThanOrEqual(minimum);
  }
  const button = page.getByRole("button", { name: "공고 보기", exact: true });
  await button.focus();
  await expect(button).toBeFocused();
  expect(await button.evaluate((node) => getComputedStyle(node).outlineStyle)).toBe("solid");
  await expect(button).toHaveScreenshot("button-focus.png");
  await button.hover();
  await expect(button).toHaveScreenshot("button-hover.png");
  await page.mouse.down();
  await expect(button).toHaveScreenshot("button-active.png");
  await page.mouse.up();
  for (const control of [page.getByLabel("검색어", { exact: true }), page.getByRole("button", { name: "필터 닫기" }), page.getByRole("link", { name: "채용 페이지" })]) {
    await control.focus();
    await expect(control).toBeFocused();
    expect(await control.evaluate((node) => getComputedStyle(node).outlineStyle)).toBe("solid");
  }
});

test("filled danger appears only in a final confirmation dialog", async ({ page }) => {
  await expect(page.getByRole("button", { name: "삭제 확인", exact: true })).not.toBeVisible();
  await page.getByRole("button", { name: "최종 삭제 확인 예시" }).click();
  const dialog = page.getByRole("dialog", { name: "패스키를 삭제할까요?" });
  await expect(dialog.getByRole("button", { name: "삭제 확인", exact: true })).toBeVisible();
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter(({ impact }) => impact === "serious" || impact === "critical")).toEqual([]);
  await expect(dialog).toHaveScreenshot("confirmation.png");
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
});

test("hydrated updates preserve button state, descriptions and tabular counts", async ({ page }) => {
  await page.getByRole("button", { name: "공고 수 늘리기" }).click();
  await expect(page.getByRole("status", { name: "공고 수" })).toHaveText("129");
  expect(await page.getByRole("status", { name: "공고 수" }).evaluate((node) => getComputedStyle(node).fontVariantNumeric)).toBe("tabular-nums");
  await expect(page.getByRole("button", { name: "저장 중" })).toBeDisabled();
  await expect(page.getByLabel("이메일", { exact: true })).toHaveAccessibleDescription("업무용 이메일을 입력하세요. 이메일 형식을 확인하세요.");
  await page.emulateMedia({ reducedMotion: "reduce" });
  expect(await page.locator(".ui-skeleton").first().evaluate((node) => getComputedStyle(node).animationName)).toBe("none");
});
