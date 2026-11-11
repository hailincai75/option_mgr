import { defineConfig, devices } from '@playwright/test'

/**
 * E2E for Option Manager.
 * - Frontend: Vite dev server on 5173 (reused if already running, otherwise started here).
 * - Backend: global setup starts ONE backend from the boot jar with the `e2e` Spring profile (in-memory H2,
 *   enforced by the backend) on 8080 and stops it at the end. Before every test the fixture calls
 *   POST /api/test-support/reset (must be 204) so each test starts with an empty database. If 8080 is held by a
 *   backend without the e2e profile (reset → 404) the run aborts before writing anything; the developer's file
 *   DB (backend/data/) is never touched. Requires the boot jar: `cd backend && gradlew.bat bootJar`.
 * - All tests share one database, so they run serially (workers: 1, not fully parallel).
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
