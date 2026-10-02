import { defineConfig } from '@playwright/test';

// Runs against an already-running stack: docker compose up + the Spring Boot app on :8080.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  globalSetup: './tests/global-setup.ts',
  use: {
    baseURL: process.env.APP_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
