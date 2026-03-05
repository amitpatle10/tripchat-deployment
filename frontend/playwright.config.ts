import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',

  // Global setup runs once before all tests — registers test users, saves auth state.
  globalSetup: './e2e/global-setup.ts',

  // Each test file runs in isolation. Failed tests get a trace for debugging.
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'html',

  use: {
    baseURL: 'http://localhost:5173',
    // Capture a trace on the first retry so you can replay exactly what failed.
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // Boot the Vite dev server before running tests.
  // reuseExistingServer: true means if you already have `npm run dev` running locally,
  // Playwright won't start a second one. In CI there is no existing server, so it starts one.
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
  },
})
