import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("permanent redirect and first page are available without JavaScript", async ({ request, browser }) => {
  const response = await request.get("/", { maxRedirects: 0 });
  expect(response.status()).toBe(308);
  expect(response.headers().location).toBe("/jobs");
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:4176/jobs");
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backend engineer 20", exact: true })).toBeVisible();
  await context.close();
});

test("hydrates without an initial browser fetch, persists URL filters, and corrects invalid input", async ({ page, request }) => {
  const browserRequests: string[] = [];
  page.on("request", req => { if (req.url().endsWith("/graphql")) browserRequests.push(req.url()); });
  await page.goto("/jobs?q=hydration-proof&skill=kotlin%3Arequired&remote=remote&updated=7d&junk=removed");
  await expect(page).toHaveURL(/updated=7d$/);
  await expect(page.getByRole("status").filter({ hasText: /unknown search parameter/ })).toBeVisible();
  await expect(page.getByLabel("검색어", { exact: true }).first()).toHaveValue("hydration-proof");
  expect(browserRequests).toEqual([]);
  const requests = await (await request.get("/__requests")).json();
  const query = requests.findLast((entry: any) => entry.variables.filter.all.some((part: any) => part.textContains === "hydration-proof"));
  expect(query.variables.filter.all).toEqual(expect.arrayContaining([{ hasStatus: "OPEN" }, { hasRemotePolicy: "REMOTE" }, { hasSkill: { slug: "kotlin", level: "REQUIRED" } }, { updatedAfter: expect.any(String) }]));
  await page.reload();
  await expect(page.getByLabel("검색어", { exact: true }).first()).toHaveValue("hydration-proof");
  expect(browserRequests).toEqual([]);
});

test("filters navigate canonically, explain empty results, and allow targeted removal", async ({ page }) => {
  await page.goto("/jobs");
  await page.getByRole("complementary").getByLabel("검색어", { exact: true }).fill("nothing");
  await page.getByRole("button", { name: "필터 적용", exact: true }).first().click();
  await expect(page).toHaveURL(/\/jobs\?q=nothing$/);
  await expect(page.getByText("선택한 조건에 맞는 공고가 없어요.", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: /검색어: nothing 해제/ }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
});

test("pagination retains cards while fetching the next cursor from same-origin GraphQL", async ({ page }) => {
  await page.goto("/jobs?q=pagination");
  const next = page.waitForRequest(req => req.url() === "http://127.0.0.1:4176/graphql" && req.postDataJSON().variables.after === "cursor-20");
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await next;
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "불러오는 중…" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backend engineer 22", exact: true })).toBeAttached();
});

test("mobile filters match desktop state and return focus on close", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/jobs?remote=remote");
  const trigger = page.getByRole("button", { name: "필터 열기" });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "공고 필터" });
  await expect(dialog.getByLabel("원격 근무", { exact: true })).toHaveValue("remote");
  await dialog.getByLabel("검색어", { exact: true }).fill("mobile");
  await dialog.getByRole("button", { name: "필터 적용" }).click();
  await expect(page).toHaveURL(/q=mobile&remote=remote/);
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toBeFocused();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test("recoverable failures expose retry without provider details", async ({ page }) => {
  await page.goto("/jobs?q=network-failure");
  await expect(page.getByText("공고를 불러오지 못했어요.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
  await expect(page.getByText(/문의 번호:/)).toBeVisible();
  await expect(page.getByText(/GraphQL request failed/)).toHaveCount(0);
});

test("a failed continuation keeps the previous cards and exposes retry", async ({ page }) => {
  await page.goto("/jobs?q=pagination-failure");
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await expect(page.getByText(/다음 공고를 불러오지 못했어요/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeAttached();
  await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
});

test("large collections progressively virtualize only after measured pagination", async ({ page }) => {
  await page.goto("/jobs?q=large");
  await expect(page.locator('[data-virtualized]')).toHaveCount(0);
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 40", exact: true })).toBeAttached();
  await expect(page.locator('[data-virtualized]')).toHaveCount(0);
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await expect(page.locator('[data-virtualized="true"]')).toBeAttached();
});

test("an unfiltered empty collection is distinct from filtered empty", async ({ page, context }) => {
  await context.addCookies([{ name: "jobs-empty", value: "1", url: "http://127.0.0.1:4176" }]);
  await page.goto("/jobs");
  await expect(page.getByText("아직 공고가 없어요.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "조건 해제" })).toHaveCount(0);
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", "https://finds.team/jobs");
  await expect(page).toHaveTitle("채용 공고 | finds.team");
});

test("retry bypasses a cached failed connection", async ({ page }) => {
  await page.goto("/jobs?q=retry-connection");
  await expect(page.getByText(/문의 번호:/)).toBeVisible();
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
});

test("authorization and invalid-filter errors have distinct safe states", async ({ page }) => {
  await page.goto("/jobs?q=unauthorized");
  await expect(page.getByText("로그인이 필요해요.", { exact: true })).toBeVisible();
  await page.goto("/jobs?q=forbidden");
  await expect(page.getByText("접근 권한이 없어요.", { exact: true })).toBeVisible();
  await page.goto("/jobs?q=invalid");
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", "noindex, follow");
  await expect(page.getByText("Private diagnostic")).toHaveCount(0);
});
