import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  use: { baseURL: "http://127.0.0.1:4174" },
  projects: [
    ...(process.env.FINDS_PUBLIC_ORIGIN ? [{ name: 'production', testMatch: 'same-origin-routing.spec.ts', use: { ...devices['Desktop Chrome'] } }] : []),
    { name: "chromium", testIgnore: ["same-origin-routing.spec.ts", "accessibility.spec.ts", "ui-catalog.spec.ts", "jobs-list.spec.ts", "discovery-details.spec.ts"], use: { ...devices["Desktop Chrome"] } },
    { name: "jobs", testMatch: ["jobs-list.spec.ts", "discovery-details.spec.ts"], use: { ...devices["Desktop Chrome"], baseURL: "http://127.0.0.1:4176" } },
    ...(["light", "dark"] as const).flatMap((colorScheme) => ([
      { name: `routes-desktop-${colorScheme}`, testMatch: 'accessibility.spec.ts', use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:4176', colorScheme } },
      { name: `routes-mobile-${colorScheme}`, testMatch: 'accessibility.spec.ts', use: { ...devices['Pixel 7'], baseURL: 'http://127.0.0.1:4176', colorScheme } },
      { name: `catalog-desktop-${colorScheme}`, testMatch: "ui-catalog.spec.ts", use: { ...devices["Desktop Chrome"], baseURL: "http://127.0.0.1:4175", colorScheme } },
      { name: `catalog-mobile-${colorScheme}`, testMatch: "ui-catalog.spec.ts", use: { ...devices["Pixel 7"], baseURL: "http://127.0.0.1:4175", colorScheme } },
    ])),
    {
      name: "kobalte-alpha",
      testMatch: "kobalte-hydration.spec.ts",
      grep: /hydrates the|traps forward|Escape closes|ArrowDown/,
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: process.env.FINDS_PUBLIC_ORIGIN ? [] : [{
    command: "node e2e/fixtures/server.ts",
    url: "http://127.0.0.1:4174",
    reuseExistingServer: false,
  }, {
    command: "FINDS_INTERNAL_GRAPHQL_URL=http://127.0.0.1:4176/graphql pnpm dev --host 127.0.0.1 --port 4175 --strictPort",
    url: "http://127.0.0.1:4175",
    reuseExistingServer: false,
  }, {
    command: "node e2e/fixtures/jobs-server.ts",
    url: "http://127.0.0.1:4176/__requests",
    reuseExistingServer: false,
  }],
});
