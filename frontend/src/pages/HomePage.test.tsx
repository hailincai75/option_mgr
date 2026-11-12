import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type { ApiErrorResponse, CashSummary, Dashboard, HealthResponse } from '../api/types'
import { App } from '../App'
import { EMPTY_DASHBOARD } from '../test/homeFixtures'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
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
}

interface FakeServer {
  health: () => Promise<Response>
  dashboard: () => Promise<Response>
  cash: () => Promise<Response>
}

function startFakeServer(overrides: Partial<FakeServer> = {}) {
  const server: FakeServer = {
    health: () => Promise.resolve(jsonResponse(health)),
    dashboard: () => Promise.resolve(jsonResponse(DASHBOARD_Q)),
    cash: () => Promise.resolve(jsonResponse(DASHBOARD_Q.cash)),
    ...overrides,
  }
  const urls: string[] = []
  const fetchMock = mockFetch()
  fetchMock.mockImplementation((input) => {
    const url = typeof input === 'string' ? input : ''
    urls.push(url)
    if (url === '/api/health') return server.health()
    if (url === '/api/dashboard') return server.dashboard()
    if (url === '/api/cash/summary') return server.cash()
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return { fetchMock, urls }
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
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('持仓 3 个')
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
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('持仓 0 个')
    expect(byTestId('dashboard-frozen-total')).toHaveTextContent('0.00')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('7 天内到期 0 个')
    expect(byTestId('dashboard-positions-card')).toHaveTextContent('已过期未处理 0 个')
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
    startFakeServer({
      health: () => Promise.reject(new TypeError('Failed to fetch')),
      dashboard: () => Promise.reject(new TypeError('Failed to fetch')),
      cash: () => Promise.reject(new TypeError('Failed to fetch')),
    })
    renderWithProviders(<HomePage />)

    await waitFor(() => {
      expect(byTestId('health-error')).toHaveTextContent(
        'Unable to reach the server (NETWORK_ERROR)',
      )
    })
    expect(byTestId('health-retry')).toBeEnabled()
    expect(byTestId('cash-summary-error')).toHaveTextContent('NETWORK_ERROR')
    expect(byTestId('dashboard-pnl-error')).toHaveTextContent('NETWORK_ERROR')
    expect(byTestId('dashboard-positions-error')).toHaveTextContent('NETWORK_ERROR')
    expect(byTestId('dashboard-pnl-retry')).toBeInTheDocument()
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
