import { expect, test, type Page } from "@playwright/test";

const diagnostics = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }, info) => {
  test.skip(info.project.name === "kobalte-alpha" && process.env.KOBALTE_COMPATIBILITY !== "1",
    "KOBALTE-ALPHA2-SOLID-RC9: COMPATIBILITY.md#kobalte-alpha2-solid-rc9; opt in to reproduce the pinned alpha hydration failure");
  const messages: string[] = [];
  diagnostics.set(page, messages);
  page.on("console", (message) => {
    if (["warning", "error"].includes(message.type())) messages.push(message.text());
  });
  page.on("pageerror", (error) => messages.push(error.message));
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
});

test.afterEach(async ({ page }) => {
  expect(diagnostics.get(page) ?? []).toEqual([]);
});

test("hydrates the existing server DOM with stable labels under nonce-only CSP", async ({ page }) => {
  let release!: () => void;
  const paused = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/client.js", async (route) => { await paused; await route.continue(); });
  const response = await page.goto("/", { waitUntil: "commit" });
  await expect(page.getByRole("button", { name: "Edit preferences" })).toBeVisible();
  const root = await page.locator("#root").elementHandle();
  const trigger = await page.getByRole("button", { name: "Edit preferences" }).elementHandle();
  const select = await page.locator("select[name=role]").elementHandle();
  release();
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  expect(await page.locator("#root").evaluate((node, original) => node === original, root)).toBe(true);
  expect(await page.getByRole("button", { name: "Edit preferences" }).evaluate((node, original) => node === original, trigger)).toBe(true);
  expect(await page.locator("select[name=role]").evaluate((node, original) => node === original, select)).toBe(true);
  expect(response?.headers()["content-security-policy"]).not.toMatch(/unsafe-inline|unsafe-eval|strict-dynamic/);
  await expect(page.getByLabel("Role", { exact: true })).toHaveCount(1);
});

test("traps forward and backward keyboard focus inside the hydrated dialog", async ({ page }) => {
  const trigger = page.getByRole("button", { name: "Edit preferences" });
  await trigger.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Preferences", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAccessibleDescription("Choose your preferences.");
  await expect(dialog.getByRole("button", { name: "First action" })).toBeFocused();
  const sequences = [
    ["Tab", ["Last action", "Close preferences", "First action", "Last action", "Close preferences"]],
    ["Shift+Tab", ["Last action", "First action", "Close preferences", "Last action", "First action"]],
  ] as const;
  for (const [key, names] of sequences) {
    for (const name of names) {
      await page.keyboard.press(key);
      await expect(dialog.getByRole("button", { name, exact: true })).toBeFocused();
    }
  }
});

test("Escape closes the hydrated dialog and restores trigger focus", async ({ page }) => {
  const trigger = page.getByRole("button", { name: "Edit preferences" });
  await trigger.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "Preferences", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "Preferences", exact: true })).not.toBeVisible();
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
});

test("ArrowDown and Enter commit a typed select value after hydration", async ({ page }, info) => {
  const control = page.getByLabel("Role", { exact: true });
  await control.focus();
  await expect(control).toBeFocused();
  await page.keyboard.press("ArrowDown");
  // Kobalte's first ArrowDown opens its listbox at the current selection;
  // a native select moves to the next option immediately on Linux.
  if (info.project.name === "kobalte-alpha") await page.keyboard.press("ArrowDown");
  // macOS headless Chromium does not navigate its native popup with arrows
  // (also reproduced with a plain HTML select). Native typeahead still works.
  if (process.platform === "darwin" && info.project.name !== "kobalte-alpha") await page.keyboard.press("e");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status", { name: "Selected role" })).toHaveText("Engineering");
  expect(await control.evaluate((node) => new FormData((node as HTMLSelectElement).form!).get("role"))).toBe("engineering");
});

test("controlled dialog follows parent state and the close button restores its trigger", async ({ page }) => {
  await page.getByRole("button", { name: "Open externally" }).click();
  await expect(page.getByRole("dialog", { name: "Controlled preferences", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Dismiss controlled dialog" }).click();
  await expect(page.getByRole("status", { name: "Controlled dialog state" })).toHaveText("closed");
  await expect(page.getByRole("button", { name: "Controlled dialog", exact: true })).toBeFocused();
});

test("native form dismissal synchronizes controlled state and permits reopening", async ({ page }) => {
  const trigger = page.getByRole("button", { name: "Controlled dialog", exact: true });
  await trigger.click();
  await page.getByRole("button", { name: "Finish", exact: true }).click();
  await expect(page.getByRole("status", { name: "Controlled dialog state" })).toHaveText("closed");
  await expect(trigger).toBeFocused();
  await trigger.press("Enter");
  await expect(page.getByRole("dialog", { name: "Controlled preferences", exact: true })).toBeVisible();
});

test.describe("review regressions", () => {
  for (const [key, start, end] of [
    ["Tab", "Before hidden", "After hidden"],
    ["Shift+Tab", "After hidden", "Before hidden"],
  ] as const) {
    test(`${key} skips controls under visibility:hidden ancestors`, async ({ page }) => {
      await page.getByRole("button", { name: "Keyboard cases", exact: true }).click();
      await page.getByRole("button", { name: start, exact: true }).focus();
      await page.keyboard.press(key);
      await expect(page.getByRole("button", { name: end, exact: true })).toBeFocused();
    });
  }

  test("native editable fields participate in both Tab directions", async ({ page }) => {
    await page.getByRole("button", { name: "Keyboard cases", exact: true }).click();
    const before = page.getByRole("button", { name: "After hidden", exact: true });
    const editable = page.getByRole("textbox", { name: "Native editable" });
    const after = page.getByRole("button", { name: "After editable", exact: true });
    await before.focus();
    await page.keyboard.press("Tab");
    await expect(editable).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(after).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(editable).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(before).toBeFocused();
  });

  test("dialog respects a child's already-handled Tab event", async ({ page }) => {
    await page.getByRole("button", { name: "Keyboard cases", exact: true }).click();
    const control = page.getByRole("button", { name: "Handles Tab" });
    await control.focus();
    await page.keyboard.press("Tab");
    await expect(control).toBeFocused();
  });

  test("failed focus attempts continue to a reachable control in either direction", async ({ page }) => {
    await page.getByRole("button", { name: "Keyboard cases", exact: true }).click();
    const before = page.getByRole("button", { name: "After editable", exact: true });
    const after = page.getByRole("button", { name: "After redirect", exact: true });
    await before.focus();
    await page.keyboard.press("Tab");
    await expect(after).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(before).toBeFocused();
  });

  test("declined native dialog closure keeps the unchanged true prop authoritative", async ({ page }) => {
    const trigger = page.getByRole("button", { name: "Held dialog", exact: true });
    const dialog = page.getByRole("dialog", { name: "Held preferences", exact: true });
    await trigger.click();
    await page.getByRole("button", { name: "Finish held dialog" }).click();
    await expect(page.getByRole("status", { name: "Held dialog state" })).toHaveText("open");
    await expect(trigger).toHaveAttribute("aria-expanded", "true");
    await expect(dialog).toBeVisible();
    expect(await dialog.evaluate((node) => node.matches(":modal"))).toBe(true);
    await page.getByRole("button", { name: "Accept external close" }).click();
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
  });

  test("accepted select changes update the DOM, typed value and form value", async ({ page }) => {
    const select = page.getByLabel("Role", { exact: true });
    await select.selectOption("engineering");
    await expect(select).toHaveValue("engineering");
    await expect(page.getByRole("status", { name: "Selected role", exact: true })).toHaveText("Engineering");
    expect(await select.evaluate((node) => new FormData((node as HTMLSelectElement).form!).get("role"))).toBe("engineering");
  });

  test("rejected select changes restore the unchanged prop before form submission", async ({ page }) => {
    const select = page.getByLabel("Held role", { exact: true });
    await select.selectOption("engineering");
    await expect(select).toHaveValue("all");
    await expect(page.getByRole("status", { name: "Held role state" })).toHaveText("All roles");
    expect(await select.evaluate((node) => new FormData((node as HTMLSelectElement).form!).get("held-role"))).toBe("all");
  });

  test("external select value changes remain authoritative after rejection", async ({ page }) => {
    const select = page.getByLabel("Held role", { exact: true });
    await page.getByRole("button", { name: "Select engineering externally" }).click();
    await expect(select).toHaveValue("engineering");
    await select.selectOption("all");
    await expect(select).toHaveValue("engineering");
    await page.getByRole("button", { name: "Select all externally" }).click();
    await expect(select).toHaveValue("all");
    expect(await select.evaluate((node) => new FormData((node as HTMLSelectElement).form!).get("held-role"))).toBe("all");
  });

  test("dialog border box stays inside a narrow and short viewport", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 240 });
    await page.getByRole("button", { name: "Keyboard cases", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Keyboard cases", exact: true });
    const bounds = await dialog.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(16);
    expect(bounds!.y).toBeGreaterThanOrEqual(16);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(304);
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(224);
    await page.getByRole("button", { name: "Close keyboard cases" }).focus();
    await expect(page.getByRole("button", { name: "Close keyboard cases" })).toBeInViewport();
  });
});
