import { expect, test, type Page } from "@playwright/test";

const diagnostics = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  const messages: string[] = [];
  diagnostics.set(page, messages);
  page.on("console", (message) => {
    if (["warning", "error"].includes(message.type())) messages.push(message.text());
  });
  page.on("pageerror", (error) => messages.push(error.message));
});
test.afterEach(async ({ page }) => expect(diagnostics.get(page)).toEqual([]));

test("SSR is bounded and hydration reuses every first-page row without warnings", async ({ page }) => {
  let release!: () => void;
  const paused = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/virtual-client.js", async (route) => { await paused; await route.continue(); });
  const response = await page.goto("/virtual", { waitUntil: "commit" });
  const section = page.getByRole("region", { name: "Initially enabled", exact: true });
  await expect(section.getByRole("button", { name: "Initial 0", exact: true })).toBeVisible();
  const rows = section.getByRole("listitem");
  const count = await rows.count();
  expect(count).toBeGreaterThan(0);
  expect(count).toBeLessThanOrEqual(30);
  expect(await rows.evaluateAll((nodes) => nodes.every((node) => getComputedStyle(node).position !== "absolute"))).toBe(true);
  const handles = await rows.elementHandles();
  release();
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  for (const handle of handles) expect(await handle.evaluate((node) => node.isConnected)).toBe(true);
  expect(response?.headers()["content-security-policy"]).not.toMatch(/unsafe-inline|unsafe-eval/);
});

test("requires caller enablement and a measured size threshold, then responds to growth and disablement", async ({ page }) => {
  await page.goto("/virtual");
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  const list = page.getByRole("region", { name: "Variable rows", exact: true }).getByRole("list");
  const short = page.getByRole("region", { name: "Short rows", exact: true }).getByRole("list");
  await expect(list.getByRole("listitem")).toHaveCount(200);
  await expect(list).toHaveAttribute("data-virtualized", "false");
  await expect(short.getByRole("listitem")).toHaveCount(3);
  await expect(short).toHaveAttribute("data-virtualized", "false");
  await page.getByRole("button", { name: "Toggle virtualization" }).click();
  await expect(list).toHaveAttribute("data-virtualized", "true");
  expect(await list.getByRole("listitem").count()).toBeLessThan(100);
  await page.getByRole("button", { name: "Grow short list" }).click();
  await expect(short).toHaveAttribute("data-virtualized", "true");
  await page.getByRole("button", { name: "Shrink short list" }).click();
  await expect(short).toHaveAttribute("data-virtualized", "false");
  await expect(short.getByRole("listitem")).toHaveCount(3);
  await page.getByRole("button", { name: "Toggle virtualization" }).click();
  await expect(list).toHaveAttribute("data-virtualized", "false");
  await expect(list.getByRole("listitem")).toHaveCount(200);
});

test("variable-height rows stay reachable when scrolling down and back up", async ({ page }) => {
  await page.goto("/virtual");
  await page.getByRole("button", { name: "Toggle virtualization" }).click();
  const list = page.getByRole("region", { name: "Variable rows", exact: true }).getByRole("list");
  await expect(list).toHaveAttribute("data-virtualized", "true");
  const last = list.getByRole("button", { name: "Row 199", exact: true });
  await expect(async () => {
    await list.evaluate((node) => { node.scrollTop = node.scrollHeight; });
    await expect(last).toBeInViewport({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await last.focus();
  await expect(last).toBeFocused();
  await list.evaluate((node) => { node.scrollTop = 0; });
  await expect(list.getByRole("button", { name: "Row 0", exact: true })).toBeInViewport();
  expect(await list.getByRole("listitem").count()).toBeLessThan(100);
});

test("measures newly loaded rows after an initially empty page", async ({ page }) => {
  await page.goto("/virtual");
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  const list = page.getByRole("region", { name: "Initially empty", exact: true }).getByRole("list");
  await expect(list.getByRole("listitem")).toHaveCount(0);
  await page.getByRole("button", { name: "Load empty list" }).click();
  await expect(list).toHaveAttribute("data-virtualized", "true");
});

test("activation uses measured row sizes and responds to resized content", async ({ page }) => {
  await page.goto("/virtual");
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  const list = page.getByRole("region", { name: "Compact rows", exact: true }).getByRole("list");
  await expect(list).toHaveAttribute("data-virtualized", "false");
  await expect(list.getByRole("listitem")).toHaveCount(25);
  await page.getByRole("button", { name: "Expand compact rows" }).click();
  await expect(list).toHaveAttribute("data-virtualized", "true");
});

test("retains focused row DOM across scroll updates and forward/backward keyboard transitions", async ({ page }) => {
  await page.goto("/virtual");
  await page.getByRole("button", { name: "Toggle virtualization" }).click();
  const list = page.getByRole("region", { name: "Variable rows", exact: true }).getByRole("list");
  await expect(list).toHaveAttribute("data-virtualized", "true");
  const row = list.getByRole("button", { name: "Row 2", exact: true });
  await row.focus();
  const original = await row.elementHandle();
  // Row 2 ends at 300px: at 480px it is outside the viewport but in overscan.
  await list.evaluate((node) => { node.scrollTop = 480; });
  await expect(row).toBeFocused();
  expect(await row.evaluate((node, handle) => node === handle, original)).toBe(true);
  for (let id = 3; id <= 35; id++) {
    await page.keyboard.press("Tab");
    await expect(list.getByRole("button", { name: `Row ${id}`, exact: true })).toBeFocused();
  }
  for (let id = 34; id >= 2; id--) {
    await page.keyboard.press("Shift+Tab");
    await expect(list.getByRole("button", { name: `Row ${id}`, exact: true })).toBeFocused();
  }
  await list.evaluate((node) => { node.scrollTop = 3000; });
  await expect(row).toBeFocused();
  await page.getByRole("button", { name: "Toggle virtualization" }).focus();
  await expect(row).toHaveCount(0);
});
