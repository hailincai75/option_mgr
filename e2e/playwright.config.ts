import { defineConfig, devices } from '@playwright/test'
import { E2E_WORKERS } from './support/backend'

/**
 * E2E for Option Manager, run in parallel by spec file.
 * - E2E_WORKERS workers (default 3; E2E_WORKERS=1 = serial). Each worker slot i has its own isolated stack, started on
 *   first use by the worker-scoped fixture (support/fixtures.ts) and stopped by global teardown:
 *   - backend from the boot jar with the `e2e` Spring profile on E2E_BACKEND_PORT + i (default 18080 + i); in-memory
 *     H2 (enforced by the backend), so each JVM has its own database, clock and reset endpoint;
 *   - Vite dev server on E2E_FRONTEND_PORT + i (default 15173 + i) with its /api proxy pointed at that backend
 *     (OPTION_MGR_API_TARGET); the worker's baseURL.
 *   8080 / 5173 (the developer's own app) are never used by default. A port held by a non-e2e backend (reset → 404)
 *   aborts the run before writing anything; the developer's file DB (backend/data/) is never touched.
 * - Before every test the fixture calls POST /api/test-support/reset (must be 204) on the worker's backend.
 * - fullyParallel: false: tests inside one spec file still run in order in one worker; files are spread over workers.
 * Requires the boot jar: `cd backend && gradlew.bat bootJar`.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: E2E_WORKERS,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './support/global-setup.ts',
  use: {
    // baseURL is set per worker by support/fixtures.ts
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
