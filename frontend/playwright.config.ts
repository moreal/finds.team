import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  use: { baseURL: "http://127.0.0.1:4174" },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    {
      name: "kobalte-alpha",
      testMatch: "kobalte-hydration.spec.ts",
      grep: /hydrates the|traps forward|Escape closes|ArrowDown/,
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "node e2e/fixtures/server.ts",
    url: "http://127.0.0.1:4174",
    reuseExistingServer: false,
  },
});
