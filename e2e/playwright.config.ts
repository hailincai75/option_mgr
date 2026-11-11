import { defineConfig, devices } from '@playwright/test'

/**
 * E2E for Option Manager.
 * - Frontend: Vite dev server on 5173 (reused if already running, otherwise started here).
 * - Backend: NOT started here. Each test starts its own fresh backend (in-memory H2) on 8080 and stops it
 *   afterwards (see support/fixtures.ts), so every test begins with an empty database and the developer's
 *   file DB (backend/data/) is never touched. Requires the boot jar: `cd backend && gradlew.bat bootJar`.
 * - Tests share port 8080, so they run serially (workers: 1).
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './support/global-setup.ts',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    cwd: '../frontend',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
