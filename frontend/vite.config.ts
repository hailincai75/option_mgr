/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // Safety margin: antd-heavy page tests take 1–2 s alone and several times that when all
    // files run in parallel; the 5 s default made the suite flaky.
    testTimeout: 15_000,
    // Each worker runs jsdom + antd (several hundred MB). The default (cores − 1 = 11 workers)
    // exhausted memory on a 16 GB dev machine, so workers took > 60 s to start and tests timed
    // out. Four workers keep the suite fast and stable.
    maxWorkers: 4,
    // Reuse each worker for several test files instead of spawning a new Node process per file:
    // antd is imported once per worker (imports were ~60% of the run time) and the hard-coded
    // 60 s worker start limit is no longer hit on a loaded machine. Files stay independent:
    // setup.ts unmounts, restores mocks and globals after every test.
    isolate: false,
  },
})
