import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import type {
  AdhocClosureAlert,
  AdhocClosureAlertsResponse,
  Account,
  OptionPosition,
  OptionPositionsResponse,
} from '../api/types'
import { App } from '../App'
import { firstAdhocClosureRow } from '../components/position/adhocClosure'
import { refreshAfterChange } from '../components/trade/tradeFormSupport'
import { EMPTY_DASHBOARD, HEALTH_UP } from '../test/homeFixtures'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'
import { PositionsPage } from './PositionsPage'

// ---------------------------------------------------------------------------
// FEAT-042 scenario (fictional tickers): account A has P1 QRTV 2026-12-04 50P, P2 IXQZ 2026-12-04
// 9C (both on the ad-hoc closure 2026-12-04) and P3 QRTV 2026-12-18 45P; account B has none.
// ---------------------------------------------------------------------------

const EVENT = 'National Day of Mourning'

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-12-04',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '100.00',
    averageNetPerContract: '100.00',
    frozenCash: '5000.00',
    dte: 3,
    remainingTradingDays: 2,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 0,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: 1,
    expiryAt: '2026-12-04T21:00:00Z',
    expiryAtLocal: '2026-12-04T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    expiresOnAdhocClosure: false,
    adhocClosureEventName: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const P2 = position({
  symbol: 'IXQZ',
  strike: '9',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 2,
  closeAction: 'STC',
  expiresOnAdhocClosure: true,
  adhocClosureEventName: EVENT,
})
const P1 = position({ expiresOnAdhocClosure: true, adhocClosureEventName: EVENT })
const P3 = position({
  expirationDate: '2026-12-18',
  strike: '45',
  dte: 17,
  remainingTradingDays: 12,
})

function positions(rows: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-12-01', positionCount: rows.length, frozenTotal: '0.00', positions: rows }
}

function alert(date: string, eventName: string, count: number): AdhocClosureAlert {
  return {
    date,
    eventName,
    affectedPositionCount: count,
    message: `${date} 临时休市（${eventName}），你有 ${String(count)} 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日`,
  }
}

const ALERT_A = alert('2026-12-04', EVENT, 2)

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

interface FakeServer {
  /** Alerts by account id (missing → `[]`). */
  alerts: Record<number, AdhocClosureAlert[]>
  positions?: OptionPositionsResponse
}

interface Recorded {
  url: string
  account: string | undefined
}

function startFakeServer(server: FakeServer): Recorded[] {
  const requests: Recorded[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    requests.push({ url, account })
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    if (url === '/api/adhoc-closure-alerts') {
      const body: AdhocClosureAlertsResponse = { alerts: server.alerts[Number(account)] ?? [] }
      return Promise.resolve(jsonResponse(body))
    }
    if (url === '/api/option-positions') {
      return Promise.resolve(jsonResponse(server.positions ?? positions([])))
    }
    if (url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    if (url === '/api/dashboard') return Promise.resolve(jsonResponse(EMPTY_DASHBOARD))
    if (url === '/api/cash/summary') return Promise.resolve(jsonResponse(EMPTY_DASHBOARD.cash))
    if (url === '/api/health') return Promise.resolve(jsonResponse(HEALTH_UP))
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return requests
}

function Probe() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname + location.search}</span>
}

const byTestId = (id: string) => screen.getByTestId(id)

function alertMessages(): string[] {
  return screen.queryAllByTestId('adhoc-closure-alert-message').map((el) => el.textContent)
}

function rowByContract(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (row === undefined) throw new Error(`no row ${contract}`)
  return row
}

// ---------------------------------------------------------------------------

describe('firstAdhocClosureRow', () => {
  it('returns the first flagged row on that date in backend order', () => {
    expect(firstAdhocClosureRow([P3, P2, P1], '2026-12-04')).toBe(P2)
    expect(firstAdhocClosureRow([P3, P2, P1], '2025-01-09')).toBeUndefined()
    // Same date without the backend flag does not count.
    expect(firstAdhocClosureRow([position({})], '2026-12-04')).toBeUndefined()
  })
})

describe('FEAT-042 home page alerts', () => {
  it('shows the message verbatim with 去调整 to the positions page and no close button (AC 3, 7, Q128)', async () => {
    const requests = startFakeServer({ alerts: { 1: [ALERT_A] } })
    renderWithProviders(
      <>
        <App />
        <Probe />
      </>,
      { route: '/' },
    )

    await waitFor(() => {
      expect(alertMessages()).toEqual([
        '2026-12-04 临时休市（National Day of Mourning），你有 2 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日',
      ])
    })
    const line = byTestId('adhoc-closure-alert')
    expect(line).toHaveAttribute('data-date', '2026-12-04')
    expect(line.querySelector('.ant-alert-close-icon')).toBeNull()
    expect(within(line).getAllByRole('link')).toHaveLength(1)
    expect(
      requests.filter((r) => r.url === '/api/adhoc-closure-alerts').every((r) => r.account === '1'),
    ).toBe(true)

    await userEvent.click(byTestId('adhoc-closure-alert-adjust'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent(/^\/positions$/)
    })
  })

  it('refetches for the new account after a switch; none for account B (AC 4)', async () => {
    const requests = startFakeServer({ alerts: { 1: [ALERT_A] } })
    renderWithProviders(<App />, { route: '/' })
    await waitFor(() => {
      expect(alertMessages()).toHaveLength(1)
    })

    await userEvent.click(byTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))

    await waitFor(() => {
      expect(screen.queryByTestId('adhoc-closure-alerts')).toBeNull()
    })
    expect(
      requests.some((r) => r.url === '/api/adhoc-closure-alerts' && r.account === '2'),
    ).toBe(true)
  })

  it('shows one line per date in backend order (AC 6)', async () => {
    startFakeServer({
      alerts: { 1: [alert('2025-01-09', 'National Mourning Day', 1), alert('2026-12-04', EVENT, 1)] },
    })
    renderWithProviders(<App />, { route: '/' })
    await waitFor(() => {
      expect(screen.getAllByTestId('adhoc-closure-alert').map((el) => el.dataset.date)).toEqual([
        '2025-01-09',
        '2026-12-04',
      ])
    })
    expect(alertMessages()[0]).toContain('你有 1 个持仓')
  })
})

describe('FEAT-042 positions page', () => {
  it('shows the alert and the 临时休市 tag on P1 and P2 only, with the eventName on hover (AC 3)', async () => {
    startFakeServer({ alerts: { 1: [ALERT_A] }, positions: positions([P2, P1, P3]) })
    renderWithProviders(<PositionsPage />, { route: '/positions' })

    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(3)
    })
    await waitFor(() => {
      expect(alertMessages()).toEqual([ALERT_A.message])
    })
    for (const row of [P2, P1]) {
      const tag = within(rowByContract(row.contractLabel)).getByTestId('position-adhoc-closure-tag')
      expect(tag).toHaveTextContent(/^临时休市$/)
      expect(tag).toHaveAttribute('data-event-name', EVENT)
    }
    expect(
      within(rowByContract(P3.contractLabel)).queryByTestId('position-adhoc-closure-tag'),
    ).toBeNull()
    // The expiration cell text itself is unchanged.
    expect(
      within(rowByContract(P1.contractLabel)).getByTestId('position-cell-expiration'),
    ).toHaveTextContent(/^2026-12-04$/)

    await userEvent.hover(
      within(rowByContract(P1.contractLabel)).getByTestId('position-adhoc-closure-tag'),
    )
    expect(await screen.findByRole('tooltip')).toHaveTextContent(EVENT)
  })

  it('去调整 scrolls to and highlights the first affected row (rule 4)', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    })
    try {
      startFakeServer({ alerts: { 1: [ALERT_A] }, positions: positions([P3, P2, P1]) })
      renderWithProviders(<PositionsPage />, { route: '/positions' })
      await waitFor(() => {
        expect(screen.getAllByTestId('position-row')).toHaveLength(3)
      })
      const adjust = await screen.findByTestId('adhoc-closure-alert-adjust')
      expect(adjust.tagName).toBe('BUTTON')
      await userEvent.click(adjust)

      await waitFor(() => {
        expect(scrollIntoView).toHaveBeenCalledTimes(1)
      })
      expect(scrollIntoView.mock.contexts[0]).toBe(rowByContract(P2.contractLabel))
      expect(rowByContract(P2.contractLabel)).toHaveAttribute('data-highlighted', 'true')
      expect(rowByContract(P1.contractLabel)).not.toHaveAttribute('data-highlighted')
    } finally {
      Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })

  it('去调整 clears a 到期 filter that hides the row, then scrolls to it', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    })
    try {
      startFakeServer({ alerts: { 1: [ALERT_A] }, positions: positions([P2, P1, P3]) })
      renderWithProviders(
        <>
          <PositionsPage />
          <Probe />
        </>,
        { route: '/positions?expiry=expired' },
      )
      await waitFor(() => {
        expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'expired')
      })
      await userEvent.click(await screen.findByTestId('adhoc-closure-alert-adjust'))

      await waitFor(() => {
        expect(scrollIntoView).toHaveBeenCalledTimes(1)
      })
      expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'all')
      expect(scrollIntoView.mock.contexts[0]).toBe(rowByContract(P2.contractLabel))
    } finally {
      Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })

  it('refetches after a write; the line updates and disappears at 0 (AC 5, Q129)', async () => {
    const server: FakeServer = { alerts: { 1: [ALERT_A] }, positions: positions([P2, P1, P3]) }
    startFakeServer(server)
    const { queryClient } = renderWithProviders(<PositionsPage />, { route: '/positions' })
    await waitFor(() => {
      expect(alertMessages()).toEqual([ALERT_A.message])
    })

    // P2 closed (STC 2): the backend now counts 1.
    server.alerts = { 1: [alert('2026-12-04', EVENT, 1)] }
    server.positions = positions([P1, P3])
    await refreshAfterChange(queryClient)
    await waitFor(() => {
      expect(alertMessages()[0]).toContain('你有 1 个持仓在该日到期')
    })

    // P1 closed too: no alerts, no tags.
    server.alerts = { 1: [] }
    server.positions = positions([P3])
    await refreshAfterChange(queryClient)
    await waitFor(() => {
      expect(screen.queryByTestId('adhoc-closure-alerts')).toBeNull()
    })
    expect(screen.queryByTestId('position-adhoc-closure-tag')).toBeNull()
  })
})
