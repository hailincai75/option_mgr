import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type { ApiErrorResponse, CashSummary, Dashboard, HealthResponse } from '../api/types'
import { App } from '../App'
import { EMPTY_DASHBOARD } from '../test/homeFixtures'
import { DEFAULT_ACCOUNTS, jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { HomePage } from './HomePage'

const health: HealthResponse = {
  status: 'UP',
  version: '0.0.1-SNAPSHOT',
  time: '2026-11-10T22:09:08.676Z',
}

const internalError: ApiErrorResponse = {
  code: 'INTERNAL_ERROR',
  message: 'Internal server error',
  status: 500,
  path: '/api/health',
  timestamp: '2026-11-10T22:09:09.171Z',
  details: [],
}

/** FEAT-008 AC 1 (scenario Q + A/B/C, today 2026-11-12). */
const DASHBOARD_Q: Dashboard = {
  asOfDate: '2026-11-12',
  currentMonth: '2026-11',
  cash: { totalCash: '101043.05', frozenCash: '44000.00', availableCash: '57043.05' },
  realizedPnl: { closedCount: 6, total: '703.05' },
  currentMonthRealizedPnl: { closedCount: 4, total: '633.05' },
  positions: {
    positionCount: 3,
    frozenTotal: '44000.00',
    expiringWithin7DaysCount: 1,
    expiredUnprocessedCount: 1,
  },
  stockPositions: { positionCount: 0 },
}

interface FakeServer {
  /** FEAT-033: the account list is part of the backend too. */
  accounts: () => Promise<Response>
  health: () => Promise<Response>
  dashboard: () => Promise<Response>
  cash: () => Promise<Response>
}

function startFakeServer(overrides: Partial<FakeServer> = {}) {
  const server: FakeServer = {
    accounts: () => Promise.resolve(jsonResponse(DEFAULT_ACCOUNTS)),
    health: () => Promise.resolve(jsonResponse(health)),
    dashboard: () => Promise.resolve(jsonResponse(DASHBOARD_Q)),
    cash: () => Promise.resolve(jsonResponse(DASHBOARD_Q.cash)),
    ...overrides,
  }
  const urls: string[] = []
  const headers: (string | undefined)[] = []
  const fetchMock = mockFetch({ accounts: false })
  fetchMock.mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : ''
    urls.push(url)
    headers.push((init?.headers as Record<string, string> | undefined)?.['X-Account-Id'])
    if (url === '/api/accounts') return server.accounts()
    if (url === '/api/health') return server.health()
    if (url === '/api/dashboard') return server.dashboard()
    if (url === '/api/cash/summary') return server.cash()
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return { fetchMock, urls, headers, server }
}

const byTestId = (id: string) => screen.getByTestId(id)

describe('HomePage: backend status footer (kept from the old home page)', () => {
  it('shows a loading indicator while the health request is pending', () => {
    startFakeServer({ health: () => new Promise<Response>(() => undefined) })

    renderWithProviders(<HomePage />)

    expect(byTestId('health-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('health-status')).not.toBeInTheDocument()
    expect(screen.queryByTestId('health-error')).not.toBeInTheDocument()
  })

  it('shows status, version and server time in New York on success', async () => {
    const { fetchMock } = startFakeServer()

    renderWithProviders(<HomePage />)

    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
    expect(byTestId('health-version')).toHaveTextContent('0.0.1-SNAPSHOT')
    expect(byTestId('health-time')).toHaveTextContent('2026-11-10 17:09:08 EST')
    expect(byTestId('health-card')).toHaveTextContent('后端：')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/health',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('shows the backend error message and recovers after retry', async () => {
    let calls = 0
    const { urls } = startFakeServer({
      health: () => {
        calls += 1
        return Promise.resolve(
          calls === 1 ? jsonResponse(internalError, 500) : jsonResponse(health),
        )
      },
    })

    renderWithProviders(<HomePage />)

    const error = await screen.findByTestId('health-error')
    expect(error).toHaveTextContent('Internal server error')
    expect(error).toHaveTextContent('INTERNAL_ERROR')

    await userEvent.click(byTestId('health-retry'))

    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
    expect(screen.queryByTestId('health-error')).not.toBeInTheDocument()
    expect(urls.filter((url) => url === '/api/health')).toHaveLength(2)
  })
})

describe('HomePage: dashboard (FEAT-008)', () => {
  it('shows every figure from the backend (AC 1)', async () => {
    startFakeServer()
    renderWithProviders(<HomePage />)

    expect(await screen.findByTestId('dashboard-as-of')).toHaveTextContent(
      '截至 2026-11-12（美东）',
    )
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('101,043.05')
    })
    expect(byTestId('cash-summary-frozen')).toHaveTextContent('44,000.00')
    expect(byTestId('cash-summary-available')).toHaveTextContent('57,043.05')

    expect(byTestId('dashboard-realized-total')).toHaveTextContent('+703.05')
    expect(byTestId('dashboard-realized-total')).toHaveStyle({ color: '#389e0d' })
    expect(byTestId('dashboard-realized-count')).toHaveTextContent('6')
    expect(byTestId('dashboard-pnl-card')).toHaveTextContent('总计+703.05（6 笔）')
    expect(byTestId('dashboard-month')).toHaveTextContent('2026-11')
    expect(byTestId('dashboard-month-total')).toHaveTextContent('+633.05')
    expect(byTestId('dashboard-month-count')).toHaveTextContent('4')
    expect(byTestId('dashboard-pnl-card')).toHaveTextContent('本月（2026-11）+633.05（4 笔）')

    expect(byTestId('dashboard-position-count')).toHaveTextContent('3')
    expect(byTestId('dashboard-frozen-total')).toHaveTextContent('44,000.00')
    expect(byTestId('dashboard-expiring-count')).toHaveTextContent('1')
    expect(byTestId('dashboard-expiring-count')).toHaveAttribute('data-highlight', 'warning')
    expect(byTestId('dashboard-expired-count')).toHaveTextContent('1')
    expect(byTestId('dashboard-expired-count')).toHaveAttribute('data-highlight', 'danger')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('期权持仓 3 个')
    expect(byTestId('dashboard-stock-positions-link')).toHaveTextContent('股票持仓 0 个标的')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('7 天内到期 1 个')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('已过期未处理 1 个')
  })

  it('links to cash, P&L summary, this month in /closed and positions (AC 1)', async () => {
    startFakeServer()
    renderWithProviders(<HomePage />)
    await screen.findByTestId('dashboard-month-link')

    expect(byTestId('dashboard-cash-link')).toHaveAttribute('href', '/cash')
    expect(byTestId('dashboard-pnl-link')).toHaveAttribute('href', '/pnl-summary')
    expect(byTestId('dashboard-positions-link')).toHaveAttribute('href', '/positions')
    expect(byTestId('dashboard-month-link')).toHaveAttribute(
      'href',
      '/closed?startDate=2026-11-01&endDate=2026-11-12',
    )
    // FEAT-010 (Q105): the two expiry counts link to the filtered positions page.
    expect(byTestId('dashboard-expiring-link')).toHaveAttribute('href', '/positions?expiry=7')
    expect(byTestId('dashboard-expiring-link')).toHaveTextContent('7 天内到期 1 个')
    expect(byTestId('dashboard-expired-link')).toHaveAttribute('href', '/positions?expiry=expired')
    expect(byTestId('dashboard-expired-link')).toHaveTextContent('已过期未处理 1 个')
  })

  it('shows a negative total in red and zero counts without highlight', async () => {
    startFakeServer({
      dashboard: () =>
        Promise.resolve(
          jsonResponse({
            ...DASHBOARD_Q,
            realizedPnl: { closedCount: 2, total: '-50.00' },
            positions: {
              ...DASHBOARD_Q.positions,
              expiringWithin7DaysCount: 0,
              expiredUnprocessedCount: 0,
            },
          }),
        ),
    })
    renderWithProviders(<HomePage />)
    const total = await screen.findByTestId('dashboard-realized-total')
    expect(total).toHaveTextContent('−50.00')
    expect(total).toHaveStyle({ color: '#cf1322' })
    expect(byTestId('dashboard-expired-count')).toHaveAttribute('data-highlight', 'none')
  })

  it('shows zeros for an empty account (AC 7)', async () => {
    startFakeServer({
      dashboard: () => Promise.resolve(jsonResponse(EMPTY_DASHBOARD)),
      cash: () => Promise.resolve(jsonResponse(EMPTY_DASHBOARD.cash)),
    })
    renderWithProviders(<HomePage />)

    await screen.findByTestId('dashboard-realized-total')
    expect(byTestId('dashboard-pnl-card')).toHaveTextContent('总计0.00（0 笔）')
    await waitFor(() => {
      expect(byTestId('dashboard-pnl-card')).toHaveTextContent('本月（2026-11）0.00（0 笔）')
    })
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('期权持仓 0 个')
    expect(byTestId('dashboard-stock-positions-link')).toHaveTextContent('股票持仓 0 个标的')
    expect(byTestId('dashboard-frozen-total')).toHaveTextContent('0.00')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('7 天内到期 0 个')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('已过期未处理 0 个')
    // FEAT-010 (Q108): still links when the count is 0.
    expect(byTestId('dashboard-expiring-link')).toHaveAttribute('href', '/positions?expiry=7')
    expect(byTestId('dashboard-expired-link')).toHaveAttribute('href', '/positions?expiry=expired')
  })

  it('keeps other cards working when the dashboard request fails, and retries', async () => {
    let calls = 0
    startFakeServer({
      dashboard: () => {
        calls += 1
        return Promise.resolve(
          calls === 1
            ? jsonResponse({ ...internalError, path: '/api/dashboard' }, 500)
            : jsonResponse(DASHBOARD_Q),
        )
      },
    })
    renderWithProviders(<HomePage />)

    expect(await screen.findByTestId('dashboard-pnl-error')).toHaveTextContent('INTERNAL_ERROR')
    expect(byTestId('dashboard-positions-error')).toHaveTextContent('INTERNAL_ERROR')
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('101,043.05')
    })
    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')

    await userEvent.click(byTestId('dashboard-pnl-retry'))
    expect(await screen.findByTestId('dashboard-realized-total')).toHaveTextContent('+703.05')
  })

  it('shows errors on every card and the footer when the backend is down (AC 8)', async () => {
    const down = () => Promise.reject(new TypeError('Failed to fetch'))
    const { urls } = startFakeServer({ accounts: down, health: down, dashboard: down, cash: down })
    renderWithProviders(<HomePage />)

    await waitFor(() => {
      expect(byTestId('health-error')).toHaveTextContent(
        'Unable to reach the server (NETWORK_ERROR)',
      )
    })
    expect(byTestId('health-retry')).toBeEnabled()
    // FEAT-033 follow-up: cards report the missing account in Chinese, with the cause code.
    await waitFor(() => {
      expect(byTestId('cash-summary-error')).toHaveTextContent('账户未就绪，请重试 (NETWORK_ERROR)')
    })
    expect(byTestId('dashboard-pnl-error')).toHaveTextContent('账户未就绪，请重试 (NETWORK_ERROR)')
    expect(byTestId('dashboard-positions-error')).toHaveTextContent('NETWORK_ERROR')
    expect(byTestId('dashboard-pnl-retry')).toBeInTheDocument()
    expect(screen.queryByText(/Account required/)).not.toBeInTheDocument()
    // No account-scoped request was sent at all.
    expect(urls.filter((url) => url === '/api/dashboard' || url === '/api/cash/summary')).toEqual(
      [],
    )
  })

  it('recovers with one click on any card 重试 after the backend comes back (FEAT-033)', async () => {
    const down = () => Promise.reject(new TypeError('Failed to fetch'))
    const { server, urls, headers } = startFakeServer({
      accounts: down,
      dashboard: down,
      cash: down,
    })
    renderWithProviders(<HomePage />)
    expect(await screen.findByTestId('dashboard-pnl-error')).toHaveTextContent('账户未就绪，请重试')

    // Backend is back.
    server.accounts = () => Promise.resolve(jsonResponse(DEFAULT_ACCOUNTS))
    server.dashboard = () => Promise.resolve(jsonResponse(DASHBOARD_Q))
    server.cash = () => Promise.resolve(jsonResponse(DASHBOARD_Q.cash))
    await userEvent.click(byTestId('dashboard-pnl-retry'))

    // Every account-scoped card loads by itself, without a second click.
    expect(await screen.findByTestId('dashboard-realized-total')).toHaveTextContent('+703.05')
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('101,043.05')
    })
    expect(byTestId('dashboard-position-count')).toHaveTextContent('3')
    expect(screen.queryByText(/Account required/)).not.toBeInTheDocument()
    // Every account-scoped request carried the account header.
    const scoped = urls
      .map((url, index) => ({ url, header: headers[index] }))
      .filter(({ url }) => url === '/api/dashboard' || url === '/api/cash/summary')
    expect(scoped.length).toBeGreaterThan(0)
    expect(scoped.every(({ header }) => header === '1')).toBe(true)
  })

  it('refreshes after a deposit without reload (AC 6)', async () => {
    let dashboard: Dashboard = DASHBOARD_Q
    let cash: CashSummary = DASHBOARD_Q.cash
    const fetchMock = mockFetch()
    fetchMock.mockImplementation((input, init) => {
      const url = typeof input === 'string' ? input : ''
      const method = init?.method ?? 'GET'
      if (url === '/api/health') return Promise.resolve(jsonResponse(health))
      if (url === '/api/dashboard') return Promise.resolve(jsonResponse(dashboard))
      if (url === '/api/cash/summary') return Promise.resolve(jsonResponse(cash))
      if (url.startsWith('/api/cash/ledger')) {
        return Promise.resolve(
          jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
        )
      }
      if (method === 'POST' && url === '/api/cash/transfers') {
        cash = { totalCash: '102043.05', frozenCash: '44000.00', availableCash: '58043.05' }
        dashboard = { ...DASHBOARD_Q, cash }
        return Promise.resolve(
          jsonResponse(
            {
              id: 2,
              type: 'DEPOSIT',
              amount: '1000.00',
              occurredAt: '2026-11-12T15:00:00Z',
              remark: null,
              ledgerEntryId: 20,
              createdAt: '2026-11-12T15:00:00Z',
            },
            201,
          ),
        )
      }
      return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
    })

    renderWithProviders(<App />, { route: '/' })
    expect(await screen.findByTestId('dashboard-realized-total')).toHaveTextContent('+703.05')

    const user = userEvent.setup()
    await user.click(byTestId('nav-cash'))
    await user.click(byTestId('cash-transfer-type-deposit'))
    fireEvent.change(byTestId('cash-transfer-amount'), { target: { value: '1000.00' } })
    fireEvent.change(byTestId('cash-transfer-time'), { target: { value: '2026-11-02T10:00' } })
    await user.click(byTestId('cash-transfer-submit'))
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('102,043.05')
    })

    await user.click(byTestId('nav-home'))
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('102,043.05')
    })
    // Realized P&L does not change with a deposit (Q26).
    expect(
      within(byTestId('dashboard-pnl-card')).getByTestId('dashboard-realized-total'),
    ).toHaveTextContent('+703.05')
    expect(
      fetchMock.mock.calls.filter(([input]) => input === '/api/dashboard').length,
    ).toBeGreaterThan(1)
  })
})

describe('HomePage: stock figures (FEAT-040)', () => {
  /** FEAT-040 AC 11 (scenario M + O5, today 2026-11-13). */
  const DASHBOARD_M: Dashboard = {
    asOfDate: '2026-11-13',
    currentMonth: '2026-11',
    cash: { totalCash: '19529.35', frozenCash: '0.00', availableCash: '19529.35' },
    realizedPnl: { closedCount: 5, total: '141.51' },
    currentMonthRealizedPnl: { closedCount: 3, total: '126.51' },
    positions: {
      positionCount: 1,
      frozenTotal: '0.00',
      expiringWithin7DaysCount: 0,
      expiredUnprocessedCount: 0,
    },
    stockPositions: { positionCount: 2 },
  }

  it('shows 期权持仓 and 股票持仓 N 个标的 linking to the stock section (AC 11, Q89, Q93)', async () => {
    startFakeServer({
      dashboard: () => Promise.resolve(jsonResponse(DASHBOARD_M)),
      cash: () => Promise.resolve(jsonResponse(DASHBOARD_M.cash)),
    })
    renderWithProviders(<HomePage />)

    expect(await screen.findByTestId('dashboard-stock-position-count')).toHaveTextContent('2')
    expect(byTestId('dashboard-pnl-card')).toHaveTextContent('总计+141.51（5 笔）')
    expect(byTestId('dashboard-pnl-card')).toHaveTextContent('本月（2026-11）+126.51（3 笔）')
    const card = byTestId('dashboard-positions-card')
    expect(card).toHaveTextContent('期权持仓 1 个')
    expect(card).toHaveTextContent('冻结现金合计 0.00')
    expect(card).toHaveTextContent('7 天内到期 0 个')
    expect(card).toHaveTextContent('已过期未处理 0 个')
    expect(byTestId('dashboard-stock-positions-link')).toHaveTextContent('股票持仓 2 个标的')
    expect(byTestId('dashboard-stock-positions-link')).toHaveAttribute(
      'href',
      '/positions#stock-positions',
    )
  })
})
