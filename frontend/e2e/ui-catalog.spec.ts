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

test("placeholder text meets normal-text contrast in both themes", async ({ page }, info) => {
  const contrast = await page.getByLabel("검색어", { exact: true }).evaluate((input) => {
    const placeholder = getComputedStyle(input, "::placeholder");
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 1;
    const context = canvas.getContext("2d")!;
    // Let the browser resolve OKLCH into sRGB and composite any placeholder
    // alpha/opacity over the real input background before measuring contrast.
    context.fillStyle = getComputedStyle(input).backgroundColor;
    context.fillRect(0, 0, 1, 1);
    const background = context.getImageData(0, 0, 1, 1).data;
    context.globalAlpha = Number(placeholder.opacity);
    context.fillStyle = placeholder.color;
    context.fillRect(0, 0, 1, 1);
    const foreground = context.getImageData(0, 0, 1, 1).data;
    const luminance = (color: Uint8ClampedArray) => {
      const linear = Array.from(color.slice(0, 3), (channel) => {
        const value = channel / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
      });
      return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
    };
    const first = luminance(foreground);
    const second = luminance(background);
    return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
  });
  console.log(`${info.project.name} placeholder contrast: ${contrast.toFixed(3)}:1`);
  expect(contrast).toBeGreaterThanOrEqual(4.5);
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
