import { QueryClient } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import { AccountProvider } from '../account/AccountProvider'
import { AppProviders } from '../AppProviders'
import type { Account } from '../api/types'

/**
 * Renders `ui` with the app's providers, a fresh no-retry QueryClient, a MemoryRouter and the
 * account context (the account list comes from `mockFetch`'s default `GET /api/accounts`).
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/' }: { route?: string } = {},
): RenderResult & { queryClient: QueryClient } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  const result = render(
    <AppProviders queryClient={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        {/* Like the app shell (FEAT-033): account-scoped queries run once the account is known. */}
        <AccountProvider>{() => ui}</AccountProvider>
      </MemoryRouter>
    </AppProviders>,
  )
  return { ...result, queryClient }
}

/** Builds a JSON `Response` like the backend would send. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** FEAT-033: the account list every test sees unless it handles `/api/accounts` itself. */
export const DEFAULT_ACCOUNTS: Account[] = [
  { id: 1, name: '默认账户', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

/**
 * Replaces global fetch with a vi.fn (restored after each test by setup.ts). `GET /api/accounts`
 * (loaded by the app shell since FEAT-033) answers `DEFAULT_ACCOUNTS` without reaching the mock,
 * so page tests keep describing only their own endpoints; pass `{ accounts: false }` to handle
 * account requests in the test.
 */
export function mockFetch({ accounts = true }: { accounts?: boolean } = {}) {
  const fetchMock = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (accounts && url === '/api/accounts' && (init?.method ?? 'GET') === 'GET') {
      return Promise.resolve(jsonResponse(DEFAULT_ACCOUNTS))
    }
    return fetchMock(input, init)
  })
  return fetchMock
}
