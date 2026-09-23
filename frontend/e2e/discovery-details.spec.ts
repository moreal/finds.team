import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("job SSR includes metadata, skill requirements and safe external application", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:4176/jobs/job-1");
  await expect(page).toHaveTitle("Backend engineer 1 · Acme | finds.team");
  await expect(page.locator('meta[name="description"]')).toHaveAttribute("content", /Build reliable services/);
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", "https://finds.team/jobs/job-1");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Backend engineer 1");
  await expect(page.getByText("정규직", { exact: true })).toBeVisible();
  await expect(page.getByText("Seoul", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "필수 기술" }).getByRole("link", { name: "Kotlin" })).toBeVisible();
  await expect(page.getByRole("region", { name: "우대 기술" }).getByRole("link", { name: "Java" })).toBeVisible();
  const apply = page.getByRole("link", { name: /지원하기.*외부.*새 창/ });
  await expect(apply).toHaveAttribute("href", "https://example.com/jobs/1");
  await expect(apply).toHaveAttribute("target", "_blank");
  await expect(apply).toHaveAttribute("rel", /noopener noreferrer/);
  await expect(page.getByText("2026-09-20", { exact: true }).first()).toBeVisible();
  await context.close();
});

test("company hydrates without refetch and paginates open postings", async ({ page }) => {
  const graphql: string[] = [];
  page.on("request", req => { if (req.url().endsWith("/graphql")) graphql.push(req.url()); });
  await page.goto("/companies/acme");
  await expect(page).toHaveTitle("Acme 채용 | finds.team");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", "https://finds.team/companies/acme");
  await expect(page.getByText(/최근 확인/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
  expect(graphql).toEqual([]);
  await page.getByRole("button", { name: "공고 더 보기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 22", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeAttached();
  expect(graphql).toEqual(["http://127.0.0.1:4176/graphql"]);
});

test("skill connections paginate independently without losing other lists", async ({ page }) => {
  await page.goto("/skills/kotlin");
  await expect(page).toHaveTitle("#Kotlin 채용 | finds.team");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", "https://finds.team/skills/kotlin");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("#Kotlin");
  await expect(page.getByRole("region", { name: "관련 회사" }).getByRole("link", { name: "Company 1", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "회사 더 보기", exact: true }).click();
  await expect(page.getByRole("link", { name: "Company 22", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "공고 더 보기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 22", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Company 22", exact: true })).toBeAttached();
  await page.getByRole("button", { name: "기술 더 보기", exact: true }).click();
  await expect(page.getByRole("link", { name: "#Related 22", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Company 22", exact: true })).toBeAttached();
});

for (const path of ["/jobs/missing", "/companies/missing", "/skills/missing"]) {
  test(`${path} is a real 404 with a recovery link`, async ({ page }) => {
    const response = await page.goto(path);
    expect(response?.status()).toBe(404);
    await expect(page.getByRole("heading", { name: "찾을 수 없어요." })).toBeVisible();
    await expect(page.getByRole("link", { name: "채용 공고 둘러보기" })).toHaveAttribute("href", "/jobs");
  });
}

test("detail routes are accessible and fit a mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const path of ["/jobs/job-1", "/companies/acme", "/skills/kotlin"]) {
    await page.goto(path);
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

test("detail query failures distinguish access and network errors with safe retry", async ({ page }) => {
  await page.goto("/jobs/unauthorized");
  await expect(page.getByText("로그인이 필요해요.", { exact: true })).toBeVisible();
  await page.goto("/companies/forbidden");
  await expect(page.getByText("접근 권한이 없어요.", { exact: true })).toBeVisible();
  await page.goto("/skills/unavailable");
  await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
  await expect(page.getByText(/문의 번호:/)).toBeVisible();
  await expect(page.getByText(/GraphQL request failed/)).toHaveCount(0);
});

test("company continuation failure keeps existing postings and offers retry", async ({ page }) => {
  await page.goto("/companies/pagination-failure");
  await page.getByRole("button", { name: "공고 더 보기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Backend engineer 1", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
  await expect(page.getByText(/문의 번호:/)).toBeVisible();
});
