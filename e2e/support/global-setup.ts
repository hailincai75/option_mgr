import { bootJar, javaExecutable, startBackend, stopBackend } from './backend'

/**
 * Starts ONE e2e-profile backend for the whole run (fails fast if 8080 is taken or the reset endpoint is
 * missing) and returns the teardown that stops it.
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  javaExecutable()
  bootJar()
  await startBackend('suite')
  return async () => {
    await stopBackend()
  }
}
