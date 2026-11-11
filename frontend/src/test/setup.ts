import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
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
