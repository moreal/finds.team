import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  use: { baseURL: "http://127.0.0.1:4174" },
  projects: [
    { name: "chromium", testIgnore: "ui-catalog.spec.ts", use: { ...devices["Desktop Chrome"] } },
    ...(["light", "dark"] as const).flatMap((colorScheme) => ([
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
  webServer: [{
    command: "node e2e/fixtures/server.ts",
    url: "http://127.0.0.1:4174",
    reuseExistingServer: false,
  }, {
    command: "pnpm dev --host 127.0.0.1 --port 4175 --strictPort",
    url: "http://127.0.0.1:4175",
    reuseExistingServer: false,
  }],
});
