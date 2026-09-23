import { defineConfig, devices } from '@playwright/test';

// Opt into live infrastructure explicitly; fixture runs never select this file.
export default defineConfig({
  testDir: './e2e',
  projects: [{ name: 'production', testMatch: 'same-origin-routing.spec.ts', use: { ...devices['Desktop Chrome'] } }],
});
