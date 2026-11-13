import { defineConfig, devices } from '@playwright/test'
import { BACKEND_URL } from './support/backend'

/**
 * Ports. Defaults 5173 (frontend) / 8080 (backend). To run next to the developer's own app on those ports, set
 * E2E_FRONTEND_PORT and E2E_BACKEND_PORT (e.g. 15173 / 18080): the suite then starts its own Vite dev server on
 * E2E_FRONTEND_PORT with its /api proxy pointed at the e2e backend (OPTION_MGR_API_TARGET) and never talks to
 * 5173 / 8080.
 */
const FRONTEND_PORT = process.env.E2E_FRONTEND_PORT ?? '5173'
const FRONTEND_URL = `http://localhost:${FRONTEND_PORT}`

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
    baseURL: FRONTEND_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: FRONTEND_PORT === '5173' ? 'npm run dev' : `npm run dev -- --port ${FRONTEND_PORT} --strictPort`,
    cwd: '../frontend',
    url: FRONTEND_URL,
    env: { OPTION_MGR_API_TARGET: BACKEND_URL },
    // A custom port is always a fresh server of our own (never someone else's Vite pointed at another backend).
    reuseExistingServer: FRONTEND_PORT === '5173',
    timeout: 120_000,
  },
})
