import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
export const BACKEND_URL = 'http://localhost:8080'
const BACKEND_DIR = path.resolve(here, '../../backend')
const LOG_DIR = path.resolve(here, '../.backend-logs')

/** In-memory H2: a brand-new empty database for every backend process. Never the dev file DB. */
const E2E_DB_URL =
  'jdbc:h2:mem:e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1'

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

/** A backend process owned by one test. */
export class E2EBackend {
  private proc: ChildProcess | null = null
  private runs = 0

  constructor(private readonly label: string) {}

  async start(): Promise<void> {
    if (this.proc) throw new Error('backend already started')
    if (await backendIsUp()) {
      // Something else (e.g. a dev backend on the file DB) holds 8080: refuse, never write test data there.
      throw new Error('Port 8080 is already serving /api/health. Stop the other backend before running E2E.')
    }
    mkdirSync(LOG_DIR, { recursive: true })
    const log = createWriteStream(path.join(LOG_DIR, `${this.label}-${++this.runs}.log`))
    const proc = spawn(
      javaExecutable(),
      ['-XX:TieredStopAtLevel=1', '-jar', bootJar(), '--server.port=8080'],
      {
        cwd: LOG_DIR,
        env: { ...process.env, SPRING_DATASOURCE_URL: E2E_DB_URL, SPRING_PROFILES_ACTIVE: '' },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
      },
    )
    proc.stdout?.pipe(log)
    proc.stderr?.pipe(log)
    this.proc = proc
    let exited = false
    proc.once('exit', () => {
      exited = true
    })
    await waitFor(
      async () => {
        if (exited) throw new Error(`backend exited during startup, see ${LOG_DIR}`)
        return backendIsUp()
      },
      90_000,
      'backend /api/health',
    )
    // Prove it is a fresh, empty database.
    const s = (await (await fetch(`${BACKEND_URL}/api/cash/summary`)).json()) as { totalCash: string }
    if (s.totalCash !== '0.00') throw new Error(`fresh backend is not empty: ${JSON.stringify(s)}`)
  }

  async stop(): Promise<void> {
    const proc = this.proc
    if (!proc) return
    this.proc = null
    if (proc.exitCode === null && proc.pid !== undefined) {
      try {
        if (process.platform === 'win32') {
          execFileSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' })
        } else {
          proc.kill('SIGKILL')
        }
      } catch {
        // already gone
      }
    }
    await waitFor(async () => !(await backendIsUp()), 30_000, 'backend to stop')
  }
}
