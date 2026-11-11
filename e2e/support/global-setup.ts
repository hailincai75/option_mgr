import { backendIsUp, bootJar, javaExecutable } from './backend'

export default async function globalSetup(): Promise<void> {
  javaExecutable()
  bootJar()
  if (await backendIsUp()) {
    throw new Error(
      'A backend is already running on 8080. E2E starts its own in-memory backend per test; stop the other one first.',
    )
  }
}
