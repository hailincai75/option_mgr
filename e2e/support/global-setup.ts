import { bootJar, javaExecutable, stopAll, viteBin } from './backend'

/**
 * Checks the prerequisites (JDK, boot jar, Vite) and returns the teardown that stops every backend and Vite dev
 * server this run started. The per-worker stacks themselves are started lazily by the worker-scoped fixture
 * (fixtures.ts), so a run with fewer spec files than workers only starts the stacks it uses.
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  javaExecutable()
  bootJar()
  viteBin()
  return async () => {
    await stopAll()
  }
}
