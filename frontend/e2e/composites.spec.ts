import { expect, test, type Page } from "@playwright/test";

const diagnostics = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  const errors: string[] = [];
  diagnostics.set(page, errors);
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (["warning", "error"].includes(message.type())) errors.push(message.text()); });
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
});

test.afterEach(({ page }) => { expect(diagnostics.get(page)).toEqual([]); });

test("combobox filters, selects using arrows, and dismisses with Escape", async ({ page }) => {
  const input = page.getByRole("combobox", { name: "Skill search" });
  await input.focus();
  await input.press("ArrowDown");
  await input.press("ArrowDown");
  await input.press("Enter");
  await expect(page.getByRole("status", { name: "Selected skill" })).toHaveText("rust");
  await expect(input).toHaveValue("Rust");
  await input.fill("sol");
  await expect(page.getByRole("listbox").getByRole("option")).toHaveCount(1);
  await input.press("ArrowDown");
  await input.press("Enter");
  await expect(input).toHaveValue("Solid");
  await input.press("ArrowDown");
  await input.press("Escape");
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await expect(input).toBeFocused();
});

test("frequent controls keep 44px targets and immediate feedback in both motion preferences", async ({ page }) => {
  for (const reducedMotion of ["no-preference", "reduce"] as const) {
    await page.emulateMedia({ reducedMotion });
    const controls = page.getByRole("region", { name: "Composite controls" }).locator("input:not([type=hidden]), button");
    for (const control of await controls.all()) {
      const style = await control.evaluate(node => {
        const css = getComputedStyle(node);
        return { height: node.getBoundingClientRect().height, animation: css.animationName, transition: css.transitionDuration };
      });
      expect(style.height).toBeGreaterThanOrEqual(44);
      expect(style.animation).toBe("none");
      expect(style.transition).toBe("0s");
    }
  }
});

test("popover returns focus on Escape and dismisses outside without trapping", async ({ page }) => {
  const trigger = page.getByRole("button", { name: "More filters" });
  await trigger.click();
  await expect(page.getByRole("dialog", { name: "Filter options" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Apply filter" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
  await expect(page.getByRole("dialog", { name: "Filter options" })).toHaveCount(0);
  await trigger.click();
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("dialog", { name: "Filter options" })).toHaveCount(0);
});

test("tabs use roving focus with arrows and Home/End", async ({ page }) => {
  await page.getByRole("tab", { name: "Jobs", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(page.getByRole("tab", { name: "Companies", exact: true })).toBeFocused();
  await expect(page.getByRole("tabpanel")).toHaveText("Company results");
  await page.keyboard.press("Home");
  await expect(page.getByRole("tabpanel")).toHaveText("Job results");
  await page.keyboard.press("End");
  await expect(page.getByRole("tabpanel")).toHaveText("Company results");
});

test("tooltip supports focus and Escape while toast announcements persist until dismissed", async ({ page }) => {
  const help = page.getByRole("button", { name: "Search help" });
  await help.focus();
  await expect(page.getByRole("tooltip")).toHaveText("Search by skill");
  await expect(help).toHaveAccessibleDescription("Search by skill");
  await help.press("Escape");
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved changes" })).toBeVisible();
  await page.getByRole("button", { name: "알림 닫기" }).click();
  await expect(page.getByText("Saved changes", { exact: true })).toHaveCount(0);
});
