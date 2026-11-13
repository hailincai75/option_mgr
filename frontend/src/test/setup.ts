import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { setApiAccountId } from '../api/client'

// findBy* / waitFor default to 1 s, which is too tight for antd pages under parallel runs.
configure({ asyncUtilTimeout: 5_000 })

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  // FEAT-033: no account or remembered choice leaks into the next test.
  setApiAccountId(null)
  try {
    window.localStorage.clear()
  } catch {
    // storage unavailable
  }
})

// jsdom does not implement matchMedia, which Ant Design's responsive utilities use.
if (typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string): MediaQueryList => ({
      matches: false,
      media: query,
      onchange: null,
      // Deprecated in the DOM spec but still required by the MediaQueryList type.
      // eslint-disable-next-line @typescript-eslint/no-deprecated
      addListener: () => undefined,
      // eslint-disable-next-line @typescript-eslint/no-deprecated
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  })
}

// jsdom does not implement ResizeObserver, which Ant Design's Menu/Table/TextArea use.
if (typeof globalThis.ResizeObserver !== 'function') {
  class ResizeObserverStub implements ResizeObserver {
    observe(): void {
      // no layout in jsdom
    }
    unobserve(): void {
      // no layout in jsdom
    }
    disconnect(): void {
      // no layout in jsdom
    }
  }
  globalThis.ResizeObserver = ResizeObserverStub
}
