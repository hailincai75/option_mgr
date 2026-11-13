import { execFileSync, spawn } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * One backend for the whole suite, started with the `e2e` Spring profile (in-memory H2 enforced by the backend,
 * startup fails otherwise) and reset before every test via POST /api/test-support/reset.
 * That endpoint only exists under the `e2e` profile: a 404 means the server on 8080 is NOT an e2e backend
 * (maybe the developer's backend on the file DB), and the suite stops immediately without writing anything.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
export const BACKEND_URL = 'http://localhost:8080'
const BACKEND_DIR = path.resolve(here, '../../backend')
const STATE_DIR = path.resolve(here, '../.backend-logs')
/** PID of the backend process started by this suite (shared by global setup, workers and teardown). */
const PID_FILE = path.join(STATE_DIR, 'backend.pid')

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

export async function backendIsUp(): Promise<boolean> {
  try {
    const r = await fetch(`${BACKEND_URL}/api/health`, { signal: AbortSignal.timeout(1000) })
    return r.ok
  } catch {
    return false
  }
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
export async function resetBackend(): Promise<void> {
  let r: Response
  try {
    r = await fetch(`${BACKEND_URL}/api/test-support/reset`, { method: 'POST', signal: AbortSignal.timeout(10_000) })
  } catch (e) {
    throw new Error(`E2E backend not reachable on 8080 for reset: ${String(e)}`)
  }
  if (r.status === 404) {
    throw new Error(
      'POST /api/test-support/reset returned 404: the backend on 8080 is NOT running with the e2e profile ' +
        '(maybe your dev backend on backend/data). Refusing to run E2E against it. Stop it and re-run.',
    )
  }
  if (r.status !== 204) throw new Error(`POST /api/test-support/reset returned ${r.status}, expected 204`)
}

/**
 * Starts a backend with the e2e profile, waits for health and confirms the reset endpoint exists.
 * If 8080 is already taken: an e2e-profile backend (reset → 204) is reused and not stopped afterwards;
 * anything else (reset → 404) aborts before any data is written.
 */
export async function startBackend(label = 'backend'): Promise<number | null> {
  if (await backendIsUp()) {
    await resetBackend() // throws on 404: not an e2e backend
    return null
  }
  mkdirSync(STATE_DIR, { recursive: true })
  const logFd = openSync(path.join(STATE_DIR, `${label}-${Date.now()}.log`), 'w')
  const env: NodeJS.ProcessEnv = { ...process.env, SPRING_PROFILES_ACTIVE: 'e2e' }
  delete env.SPRING_DATASOURCE_URL // application-e2e.yml decides (in-memory)
  // Detached, logging straight to a file: the process does not depend on whoever started it
  // (a worker may restart it), and global teardown stops it via the PID file.
  const proc = spawn(javaExecutable(), ['-XX:TieredStopAtLevel=1', '-jar', bootJar(), '--server.port=8080'], {
    cwd: STATE_DIR,
    env,
    stdio: ['ignore', logFd, logFd],
    detached: true,
    windowsHide: true,
  })
  closeSync(logFd)
  if (proc.pid === undefined) throw new Error('failed to spawn backend')
  writeFileSync(PID_FILE, String(proc.pid))
  let exited = false
  proc.once('exit', () => {
    exited = true
  })
  proc.unref()
  await waitFor(
    async () => {
      if (exited) throw new Error(`backend exited during startup, see logs in ${STATE_DIR}`)
      return backendIsUp()
    },
    90_000,
    'backend /api/health',
  )
  await resetBackend()
  return proc.pid
}

/** Stops the backend started by this suite (PID file); no-op if none. */
export async function stopBackend(): Promise<void> {
  if (!existsSync(PID_FILE)) return
  const pid = readFileSync(PID_FILE, 'utf8').trim()
  rmSync(PID_FILE, { force: true })
  try {
    if (process.platform === 'win32') {
      execFileSync('taskkill', ['/PID', pid, '/T', '/F'], { stdio: 'ignore' })
    } else {
      process.kill(-Number(pid), 'SIGKILL')
    }
  } catch {
    // already gone
  }
  await waitFor(async () => !(await backendIsUp()), 30_000, 'backend to stop')
}

/** Handle given to tests (e.g. to simulate the backend being down). */
export const e2eBackend = {
  reset: resetBackend,
  stop: stopBackend,
  start: async (): Promise<void> => {
    await startBackend('restarted')
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
