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
  expect(count).toBe(20);
  expect(await rows.evaluateAll((nodes) => nodes.every((node) => getComputedStyle(node).position !== "absolute"))).toBe(true);
  const handles = await rows.elementHandles();
  const children = await section.getByRole("button").elementHandles();
  const texts = await section.getByRole("button").allTextContents();
  release();
  await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
  for (const handle of handles) expect(await handle.evaluate((node) => node.isConnected)).toBe(true);
  for (const [index, handle] of children.entries()) {
    expect(await section.getByRole("button").nth(index).evaluate((node, original) => node === original, handle)).toBe(true);
  }
  expect(await section.getByRole("button").allTextContents()).toEqual(texts);
  expect(response?.headers()["content-security-policy"]).not.toMatch(/unsafe-inline|unsafe-eval/);
});

test.describe("review regressions", () => {
  test("append and reorder retain the focused child DOM and component state while updating position", async ({ page }) => {
    await page.goto("/virtual");
    await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
    const list = page.getByRole("region", { name: "Stateful rows", exact: true }).getByRole("list");
    const child = list.locator("[data-state-row='0']");
    await child.click();
    await expect(child).toHaveText("State 0: 1");
    const original = await child.elementHandle();
    for (const [action, position] of [["Append stateful rows", "0"], ["Reorder stateful rows", "2"], ["Refresh stateful rows", "2"]] as const) {
      // Dispatch without moving focus out of the row being tested.
      await page.getByRole("button", { name: action }).evaluate((node: HTMLButtonElement) => node.click());
      await expect(list).toHaveAttribute("data-virtualized", "true");
      expect(await child.evaluate((node, handle) => node === handle, original)).toBe(true);
      await expect(child).toHaveText("State 0: 1");
      await expect(child).toBeFocused();
      await expect(child).toHaveAttribute("data-position", position);
    }
    await expect(child).toHaveAttribute("data-label", "Updated 0");
  });

  test("activation and disable/re-enable preserve a scrolled visible anchor despite inaccurate estimates", async ({ page }) => {
    await page.goto("/virtual");
    await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
    let list = page.getByRole("region", { name: "Variable rows", exact: true }).getByRole("list");
    const anchor = () => list.evaluate((node) => {
      const top = node.getBoundingClientRect().top;
      const row = [...node.querySelectorAll<HTMLElement>("[data-row]")]
        .find((child) => child.getBoundingClientRect().bottom > top);
      return row ? { key: row.dataset.row, offset: Math.round(row.getBoundingClientRect().top - top) } : null;
    });
    await list.evaluate((node) => { node.scrollTop = 4321; });
    expect(await anchor()).toEqual({ key: "43", offset: -61 });
    for (const mode of ["true", "false", "true"]) {
      await page.getByRole("button", { name: "Toggle virtualization" }).evaluate((node: HTMLButtonElement) => node.click());
      await expect(list).toHaveAttribute("data-virtualized", mode);
      await expect.poll(anchor, { message: `visible anchor after data-virtualized=${mode}` }).toEqual({ key: "43", offset: -61 });
    }
    // This list activated with only its SSR page measured, so unknown preceding
    // rows still use estimates when the user scrolls deeper into the collection.
    list = page.getByRole("region", { name: "Initially enabled", exact: true }).getByRole("list");
    await list.evaluate((node) => { node.scrollTop = 4321; });
    await expect.poll(async () => Number((await anchor())?.key)).toBeGreaterThan(20);
    await list.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    const estimatedAnchor = await anchor();
    for (const mode of ["false", "true"]) {
      await page.getByRole("button", { name: "Toggle initially enabled rows" }).evaluate((node: HTMLButtonElement) => node.click());
      await expect(list).toHaveAttribute("data-virtualized", mode);
      await expect.poll(anchor, { message: `estimated anchor after data-virtualized=${mode}` }).toEqual(estimatedAnchor);
    }
  });

  test("tall-first-page and short-tail rows reach the end without scrolling changing mode", async ({ page }) => {
    await page.goto("/virtual");
    await expect(page.locator("html")).toHaveAttribute("data-hydrated", "true");
    const list = page.getByRole("region", { name: "Heterogeneous rows", exact: true }).getByRole("list");
    await list.scrollIntoViewIfNeeded();
    await expect(list).toHaveAttribute("data-virtualized", "true");
    await list.evaluate((node) => {
      node.setAttribute("data-test-modes", "true");
      const observer = new MutationObserver(() => {
        node.setAttribute("data-test-modes", `${node.getAttribute("data-test-modes")},${node.getAttribute("data-virtualized")}`);
      });
      observer.observe(node, { attributes: true, attributeFilter: ["data-virtualized"] });
    });
    const last = list.locator("[data-heterogeneous-row='199']");
    await expect(async () => {
      await list.evaluate((node) => { node.scrollTop = node.scrollHeight; });
      await expect(last).toBeInViewport({ timeout: 250 });
    }).toPass({ timeout: 5000 });
    await expect(list).toHaveAttribute("data-virtualized", "true");
    expect(await list.getAttribute("data-test-modes")).not.toContain("false");
  });
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
