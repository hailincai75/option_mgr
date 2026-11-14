import { execFileSync, spawn } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Parallel E2E: one isolated stack per Playwright worker slot.
 * Worker slot i (process.env.TEST_PARALLEL_INDEX, 0..workers-1, kept when a worker is restarted after a failure) gets
 * - its own backend on E2E_BACKEND_PORT + i (default 18080 + i), started from the boot jar with the `e2e` Spring
 *   profile. Each JVM has its own in-memory H2 (jdbc:h2:mem:..., enforced by the backend), so the database, the
 *   settable clock and POST /api/test-support/reset are isolated per worker automatically;
 * - its own Vite dev server on E2E_FRONTEND_PORT + i (default 15173 + i) whose /api proxy points at that backend
 *   (OPTION_MGR_API_TARGET).
 * Both are started lazily by the worker-scoped fixture in fixtures.ts (only as many stacks as workers actually used),
 * survive worker restarts (detached, PID files in .backend-logs/) and are stopped by the global teardown.
 * The reset endpoint only exists under the `e2e` profile: a 404 means the server on that port is NOT an e2e backend
 * (maybe the developer's backend on the file DB), and the run stops immediately without writing anything.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const BACKEND_DIR = path.resolve(here, '../../backend')
const FRONTEND_DIR = path.resolve(here, '../../frontend')
const STATE_DIR = path.resolve(here, '../.backend-logs')

/** Number of workers (= isolated stacks). E2E_WORKERS, default 3. */
export const E2E_WORKERS = Math.max(1, Number(process.env.E2E_WORKERS ?? '3') || 3)
/** Worker slot of this process (0 in the main Playwright process). */
export const WORKER_SLOT = Number(process.env.TEST_PARALLEL_INDEX ?? '0')
const BACKEND_BASE_PORT = Number(process.env.E2E_BACKEND_PORT ?? '18080')
const FRONTEND_BASE_PORT = Number(process.env.E2E_FRONTEND_PORT ?? '15173')

/** This worker's backend (all direct API calls of the tests go here). */
export const BACKEND_PORT = String(BACKEND_BASE_PORT + WORKER_SLOT)
export const BACKEND_URL = `http://localhost:${BACKEND_PORT}`
/** This worker's Vite dev server (the tests' baseURL). */
export const FRONTEND_PORT = String(FRONTEND_BASE_PORT + WORKER_SLOT)
export const FRONTEND_URL = `http://localhost:${FRONTEND_PORT}`

const backendPidFile = (port: string) => path.join(STATE_DIR, `backend-${port}.pid`)
const frontendPidFile = (port: string) => path.join(STATE_DIR, `vite-${port}.pid`)

export function javaExecutable(): string {
  const home = process.env.E2E_JAVA_HOME ?? 'C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot'
  const exe = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (!existsSync(exe)) throw new Error(`JDK 21 not found at ${exe}; set E2E_JAVA_HOME`)
  return exe
}

export function bootJar(): string {
  const libs = path.join(BACKEND_DIR, 'build', 'libs')
  const jar = existsSync(libs)
    ? readdirSync(libs).find((f) => f.endsWith('.jar') && !f.endsWith('-plain.jar'))
    : undefined
  if (!jar) throw new Error(`Backend boot jar missing in ${libs}; run "cd backend && gradlew.bat bootJar"`)
  return path.join(libs, jar)
}

export function viteBin(): string {
  const bin = path.join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
  if (!existsSync(bin)) throw new Error(`Vite not found at ${bin}; run "cd frontend && npm install"`)
  return bin
}

async function urlOk(url: string): Promise<boolean> {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(1000) })
    return r.ok
  } catch {
    return false
  }
}

async function urlAnswers(url: string): Promise<boolean> {
  try {
    await fetch(url, { signal: AbortSignal.timeout(1000) })
    return true
  } catch {
    return false
  }
}

export async function backendIsUp(port: string = BACKEND_PORT): Promise<boolean> {
  return urlOk(`http://localhost:${port}/api/health`)
}

async function waitFor(cond: () => Promise<boolean>, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await cond()) return
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(`Timed out waiting for ${what}`)
}

/**
 * Empties the database (fresh migrated state, ids restart at 1). Requires 204; anything else — in particular
 * 404 from a backend without the e2e profile — aborts.
 */
export async function resetBackend(port: string = BACKEND_PORT): Promise<void> {
  let r: Response
  try {
    r = await fetch(`http://localhost:${port}/api/test-support/reset`, {
      method: 'POST',
      signal: AbortSignal.timeout(10_000),
    })
  } catch (e) {
    throw new Error(`E2E backend not reachable on ${port} for reset: ${String(e)}`)
  }
  if (r.status === 404) {
    throw new Error(
      `POST /api/test-support/reset returned 404: the backend on ${port} is NOT running with the e2e profile ` +
        '(maybe your dev backend on backend/data). Refusing to run E2E against it. Stop it and re-run.',
    )
  }
  if (r.status !== 204) throw new Error(`POST /api/test-support/reset returned ${r.status}, expected 204`)
}

/**
 * Spawns a detached process logging straight to a file, so it does not depend on the worker that started it (a
 * worker may be restarted); global teardown stops it via the PID file.
 */
function spawnDetached(
  exe: string,
  args: string[],
  opts: { cwd: string; env: NodeJS.ProcessEnv; logName: string; pidFile: string },
): { pid: number; exited: () => boolean } {
  mkdirSync(STATE_DIR, { recursive: true })
  const logFd = openSync(path.join(STATE_DIR, `${opts.logName}-${Date.now()}.log`), 'w')
  const proc = spawn(exe, args, {
    cwd: opts.cwd,
    env: opts.env,
    stdio: ['ignore', logFd, logFd],
    detached: true,
    windowsHide: true,
  })
  closeSync(logFd)
  if (proc.pid === undefined) throw new Error(`failed to spawn ${opts.logName}`)
  writeFileSync(opts.pidFile, String(proc.pid))
  let exited = false
  proc.once('exit', () => {
    exited = true
  })
  proc.unref()
  return { pid: proc.pid, exited: () => exited }
}

function killTree(pid: string): void {
  try {
    if (process.platform === 'win32') {
      execFileSync('taskkill', ['/PID', pid, '/T', '/F'], { stdio: 'ignore' })
    } else {
      process.kill(-Number(pid), 'SIGKILL')
    }
  } catch {
    // already gone
  }
}

/**
 * Starts a backend with the e2e profile on `port`, waits for health and confirms the reset endpoint exists.
 * If the port is already taken: an e2e-profile backend (reset → 204) is reused and not stopped afterwards;
 * anything else (reset → 404) aborts before any data is written.
 */
export async function startBackend(label = 'backend', port: string = BACKEND_PORT): Promise<number | null> {
  if (await backendIsUp(port)) {
    await resetBackend(port) // throws on 404: not an e2e backend
    return null
  }
  const env: NodeJS.ProcessEnv = { ...process.env, SPRING_PROFILES_ACTIVE: 'e2e' }
  delete env.SPRING_DATASOURCE_URL // application-e2e.yml decides (in-memory, one database per JVM)
  const p = spawnDetached(javaExecutable(), ['-XX:TieredStopAtLevel=1', '-jar', bootJar(), `--server.port=${port}`], {
    cwd: STATE_DIR,
    env,
    logName: `${label}-${port}`,
    pidFile: backendPidFile(port),
  })
  await waitFor(
    async () => {
      if (p.exited()) throw new Error(`backend on ${port} exited during startup, see logs in ${STATE_DIR}`)
      return backendIsUp(port)
    },
    120_000,
    `backend /api/health on ${port}`,
  )
  await resetBackend(port)
  return p.pid
}

/** Stops the backend this suite started on `port` (PID file); no-op if none. */
export async function stopBackend(port: string = BACKEND_PORT): Promise<void> {
  const file = backendPidFile(port)
  if (!existsSync(file)) return
  const pid = readFileSync(file, 'utf8').trim()
  rmSync(file, { force: true })
  killTree(pid)
  await waitFor(async () => !(await backendIsUp(port)), 30_000, `backend on ${port} to stop`)
}

/**
 * Starts this suite's own Vite dev server on `port` with its /api proxy pointed at `backendPort`.
 * A server already on that port is reused only if this suite started it (PID file present, e.g. before a worker
 * restart); any other server there aborts the run (it might proxy to the developer's backend).
 */
export async function startFrontend(port: string, backendPort: string): Promise<void> {
  const url = `http://localhost:${port}`
  if (await urlAnswers(url)) {
    if (existsSync(frontendPidFile(port))) return
    throw new Error(`Port ${port} is in use by a server this E2E run did not start; free it or set E2E_FRONTEND_PORT`)
  }
  const p = spawnDetached(process.execPath, [viteBin(), '--port', port, '--strictPort'], {
    cwd: FRONTEND_DIR,
    env: { ...process.env, OPTION_MGR_API_TARGET: `http://localhost:${backendPort}` },
    logName: `vite-${port}`,
    pidFile: frontendPidFile(port),
  })
  await waitFor(
    async () => {
      if (p.exited()) throw new Error(`Vite on ${port} exited during startup, see logs in ${STATE_DIR}`)
      return urlOk(url)
    },
    120_000,
    `Vite dev server on ${port}`,
  )
}

async function stopFrontend(port: string): Promise<void> {
  const file = frontendPidFile(port)
  if (!existsSync(file)) return
  const pid = readFileSync(file, 'utf8').trim()
  rmSync(file, { force: true })
  killTree(pid)
  await waitFor(async () => !(await urlAnswers(`http://localhost:${port}`)), 30_000, `Vite on ${port} to stop`)
}

/** Makes sure this worker's backend and Vite dev server are running (worker-scoped fixture). */
export async function ensureWorkerStack(): Promise<void> {
  await startBackend('backend', BACKEND_PORT) // reuses a running e2e backend, aborts on a non-e2e one
  await startFrontend(FRONTEND_PORT, BACKEND_PORT)
}

/** Stops every backend and Vite dev server started by this suite (all PID files); used by global teardown. */
export async function stopAll(): Promise<void> {
  if (!existsSync(STATE_DIR)) return
  const jobs = readdirSync(STATE_DIR).flatMap((f) => {
    const m = /^(backend|vite)-(\d+)\.pid$/.exec(f)
    if (!m) return []
    return [m[1] === 'backend' ? stopBackend(m[2]!) : stopFrontend(m[2]!)]
  })
  await Promise.all(jobs)
}

/** Handle given to tests (e.g. to simulate the backend being down). Acts on this worker's backend only. */
export const e2eBackend = {
  reset: (): Promise<void> => resetBackend(BACKEND_PORT),
  stop: (): Promise<void> => stopBackend(BACKEND_PORT),
  start: async (): Promise<void> => {
    await startBackend('restarted', BACKEND_PORT)
  },
}
export type E2EBackend = typeof e2eBackend

// ---------------------------------------------------------------------------------------------------------------
// FEAT-033: every business endpoint needs X-Account-Id. Direct API calls made by the tests themselves (seeding data,
// reading results) go through Node's global fetch; add the header for backend URLs unless the call sets its own.
// The browser is not affected (the app sends its own header for the current account).

let apiAccount: number | null = 1

/** Account used for the tests' own direct backend calls; null = send no X-Account-Id. Reset to 1 per test. */
export function setApiAccount(id: number | null): void {
  apiAccount = id
}

const FETCH_PATCHED = Symbol.for('option-mgr.e2e.fetch-patched')
const g = globalThis as typeof globalThis & { [FETCH_PATCHED]?: boolean }
if (!g[FETCH_PATCHED]) {
  g[FETCH_PATCHED] = true
  const original = globalThis.fetch.bind(globalThis)
  globalThis.fetch = (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (url.startsWith(BACKEND_URL) && apiAccount !== null) {
      const headers = new Headers(init?.headers)
      if (!headers.has('X-Account-Id')) headers.set('X-Account-Id', String(apiAccount))
      return original(input, { ...init, headers })
    }
    return original(input, init)
  }
}
