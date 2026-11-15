import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { ACCOUNT_STORAGE_KEY } from '../account/accountStorage'
import type {
  Account,
  AdhocClosureAlertsResponse,
  ApiErrorResponse,
  CashSummary,
  Dashboard,
  OptionPosition,
  OptionPositionsResponse,
  PnlSummary,
  StockPosition,
  StockPositionsResponse,
  UnrealizedSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'

// ---------------------------------------------------------------------------
// FEAT-035 scene S (fictional accounts 甲账户 / 乙账户 and tickers QRTV / IXQZ), served per
// `X-Account-Id` (1, 2 or ALL) exactly like the contract examples.
// ---------------------------------------------------------------------------

const JIA: Account = { id: 1, name: '甲账户', brokerNote: null, createdAt: '2026-11-01T14:00:00Z' }
const YI: Account = { id: 2, name: '乙账户', brokerNote: null, createdAt: '2026-11-02T14:00:00Z' }

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-12-18',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '100.00',
    averageNetPerContract: '100.00',
    frozenCash: '5000.00',
    dte: 25,
    remainingTradingDays: 18,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 1,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: 1,
    expiryAt: '2026-12-18T21:00:00Z',
    expiryAtLocal: '2026-12-18T16:00:00',
    assignable: true,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    expiresOnAdhocClosure: false,
    adhocClosureEventName: null,
    unrealizedStatus: 'OK',
    unrealizedLabel: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const B4 = position({
  accountId: 2,
  accountName: '乙账户',
  expirationDate: '2026-11-20',
  strike: '45',
  openingNetTotal: '60.00',
  frozenCash: '4500.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  remainingTradingDays: null,
  closable: false,
  expirable: true,
  marketValue: null,
  unrealizedPnl: null,
  unrealizedStatus: 'EXPIRED_PENDING',
  unrealizedLabel: '已到期，待处理',
})
const A4 = position({
  accountId: 1,
  accountName: '甲账户',
  symbol: 'IXQZ',
  expirationDate: '2026-11-27',
  strike: '65',
  optionType: 'CALL',
  openingNetTotal: '84.35',
  frozenCash: '0.00',
  dte: 4,
  marketValue: '-85.00',
  unrealizedPnl: '-0.65',
})
const A1 = position({
  accountId: 1,
  accountName: '甲账户',
  openingNetTotal: '118.70',
  marketValue: '-125.00',
  unrealizedPnl: '-6.30',
})
const B3 = position({
  accountId: 2,
  accountName: '乙账户',
  openingNetTotal: '120.00',
  marketValue: '-125.00',
  unrealizedPnl: '-5.00',
})

function withClosure(row: OptionPosition): OptionPosition {
  return { ...row, expiresOnAdhocClosure: true, adhocClosureEventName: 'Test Closure' }
}

type Scope = '1' | '2' | 'ALL'

function optionPositions(rows: OptionPosition[], frozenTotal: string): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: rows.length, frozenTotal, positions: rows }
}

const IXQZ_STOCK: StockPosition = {
  accountId: 1,
  accountName: '甲账户',
  symbol: 'IXQZ',
  shares: 100,
  coveredShares: 100,
  availableShares: 0,
  totalCost: '5880.00',
  averageCost: '58.8000',
  lotCount: 1,
  lots: [],
  marketValue: '6110.00',
  unrealizedPnl: '230.00',
  unrealizedPnlPercent: '3.91',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
}

const CASH: Record<Scope, CashSummary> = {
  '1': {
    totalCash: '50263.05',
    frozenCash: '5000.00',
    availableCash: '45263.05',
    accounts: [
      {
        accountId: 1,
        accountName: '甲账户',
        totalCash: '50263.05',
        frozenCash: '5000.00',
        availableCash: '45263.05',
      },
    ],
  },
  '2': {
    totalCash: '20210.00',
    frozenCash: '9500.00',
    availableCash: '10710.00',
    accounts: [
      {
        accountId: 2,
        accountName: '乙账户',
        totalCash: '20210.00',
        frozenCash: '9500.00',
        availableCash: '10710.00',
      },
    ],
  },
  ALL: {
    totalCash: '70473.05',
    frozenCash: '14500.00',
    availableCash: '55973.05',
    accounts: [
      {
        accountId: 1,
        accountName: '甲账户',
        totalCash: '50263.05',
        frozenCash: '5000.00',
        availableCash: '45263.05',
      },
      {
        accountId: 2,
        accountName: '乙账户',
        totalCash: '20210.00',
        frozenCash: '9500.00',
        availableCash: '10710.00',
      },
    ],
  },
}

const DASHBOARD_ALL: Dashboard = {
  asOfDate: '2026-11-23',
  currentMonth: '2026-11',
  cash: { totalCash: '70473.05', frozenCash: '14500.00', availableCash: '55973.05' },
  realizedPnl: { closedCount: 2, total: '90.00' },
  currentMonthRealizedPnl: { closedCount: 1, total: '60.00' },
  positions: {
    positionCount: 4,
    frozenTotal: '14500.00',
    expiringWithin7DaysCount: 1,
    expiredUnprocessedCount: 1,
  },
  stockPositions: { positionCount: 1 },
}

const UNREALIZED_ALL: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: {
    marketValue: '-335.00',
    unrealizedPnl: '-11.95',
    includedCount: 3,
    noQuoteCount: 0,
    expiredPendingCount: 1,
  },
  stock: {
    marketValue: '6110.00',
    unrealizedPnl: '230.00',
    includedCount: 1,
    noQuoteCount: 0,
    expiredPendingCount: 0,
  },
  total: {
    marketValue: '5775.00',
    unrealizedPnl: '218.05',
    includedCount: 4,
    noQuoteCount: 0,
    expiredPendingCount: 1,
  },
  excludedLabel: '1 个持仓已到期待处理，未计入',
}

function pnl(closedCount: number, total: string, months: [string, number, string][]): PnlSummary {
  const part = (count: number, value: string) => ({ closedCount: count, realizedPnl: value })
  const none = part(0, '0.00')
  return {
    startDate: null,
    endDate: null,
    closedCount,
    realizedPnlTotal: total,
    option: part(closedCount, total),
    stock: none,
    bySymbol: [
      { symbol: 'QRTV', closedCount, realizedPnl: total, option: part(closedCount, total), stock: none },
    ],
    byMonth: months.map(([month, count, value]) => ({
      month,
      closedCount: count,
      realizedPnl: value,
      option: part(count, value),
      stock: none,
    })),
  }
}

const ALERT_MESSAGE =
  '2026-12-18 临时休市（Test Closure），你有 2 个持仓在该日到期（甲账户 1 个、乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日'

interface Recorded {
  method: string
  path: string
  account: string | undefined
}

interface ServerOptions {
  accounts?: Account[]
  /** Adds the 2026-12-18 ad-hoc closure (acceptance 8). */
  closure?: boolean
}

function notSupported(path: string): Response {
  const body: ApiErrorResponse = {
    code: 'ALL_ACCOUNTS_NOT_SUPPORTED',
    message: '此接口暂不支持全部账户，请选择一个账户',
    status: 400,
    path,
    timestamp: '2026-11-23T15:00:00Z',
    details: [{ field: 'X-Account-Id', message: '请选择一个账户' }],
  }
  return jsonResponse(body, 400)
}

function startServer({ accounts = [JIA, YI], closure = false }: ServerOptions = {}) {
  const requests: Recorded[] = []
  const rows = (scope: Scope): OptionPosition[] => {
    const all = closure ? [B4, A4, withClosure(A1), withClosure(B3)] : [B4, A4, A1, B3]
    if (scope === 'ALL') return all
    return all.filter((row) => String(row.accountId) === scope)
  }
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    const { pathname: path } = new URL(url, 'http://localhost')
    requests.push({ method, path, account })
    const scope = (account ?? '1') as Scope

    switch (path) {
      case '/api/accounts':
        return Promise.resolve(jsonResponse(accounts))
      case '/api/health':
        return Promise.resolve(
          jsonResponse({ status: 'UP', version: 'dev', time: '2026-11-23T15:00:00Z' }),
        )
      case '/api/dashboard':
        return Promise.resolve(jsonResponse(DASHBOARD_ALL))
      case '/api/cash/summary':
        return Promise.resolve(jsonResponse(CASH[scope]))
      // FEAT-046: the ledger accepts ALL (rows are covered by AllAccountsLists.test.tsx).
      case '/api/cash/ledger':
        return Promise.resolve(
          jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
        )
      case '/api/option-positions': {
        const shown = rows(scope)
        const frozen = { '1': '5000.00', '2': '9500.00', ALL: '14500.00' }[scope]
        return Promise.resolve(jsonResponse(optionPositions(shown, frozen)))
      }
      case '/api/stock-positions': {
        const shown = scope === '2' ? [] : [IXQZ_STOCK]
        const body: StockPositionsResponse = { positionCount: shown.length, positions: shown }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/positions/unrealized-summary':
        return Promise.resolve(jsonResponse(UNREALIZED_ALL))
      case '/api/pnl-summary':
        return Promise.resolve(
          jsonResponse(
            scope === 'ALL'
              ? pnl(2, '90.00', [
                  ['2026-11', 1, '60.00'],
                  ['2026-10', 1, '30.00'],
                ])
              : pnl(1, '30.00', [['2026-10', 1, '30.00']]),
          ),
        )
      case '/api/adhoc-closure-alerts': {
        const body: AdhocClosureAlertsResponse = { alerts: [] }
        if (closure && scope === 'ALL') {
          body.alerts.push({
            date: '2026-12-18',
            eventName: 'Test Closure',
            affectedPositionCount: 2,
            byAccount: [
              { accountId: 1, accountName: '甲账户', affectedPositionCount: 1 },
              { accountId: 2, accountName: '乙账户', affectedPositionCount: 1 },
            ],
            message: ALERT_MESSAGE,
          })
        } else if (closure) {
          body.alerts.push({
            date: '2026-12-18',
            eventName: 'Test Closure',
            affectedPositionCount: 1,
            byAccount: [
              {
                accountId: Number(scope),
                accountName: scope === '1' ? '甲账户' : '乙账户',
                affectedPositionCount: 1,
              },
            ],
            message:
              '2026-12-18 临时休市（Test Closure），你有 1 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日',
          })
        }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/market-data/status':
        return Promise.resolve(
          jsonResponse({
            stale: false,
            warning: null,
            lastSuccessAt: '2026-11-23T15:00:00Z',
            lastSuccessAtLocal: '2026-11-23T10:00:00',
            lastAttemptAt: '2026-11-23T15:00:00Z',
            lastAttemptAtLocal: '2026-11-23T10:00:00',
            lastRefreshAt: null,
            lastRefreshAtLocal: null,
            refreshAvailableAt: null,
            refreshAllowed: true,
            trackedSymbolCount: 2,
          }),
        )
      case '/api/trading-calendar/status':
        return Promise.resolve(
          jsonResponse({
            stale: false,
            warning: null,
            lastAttemptAt: null,
            lastAttemptResult: null,
            lastFailureDetail: null,
            lastSuccessAt: null,
            lastSuccessAtLocal: null,
            coveredYears: [2026],
          }),
        )
      default:
        // Unsupported reads with ALL (FEAT-035 contract) — the pages must not send them.
        if (scope === 'ALL') return Promise.resolve(notSupported(path))
        return Promise.resolve(jsonResponse({}, 404))
    }
  })
  return requests
}

function LocationProbe() {
  const location = useLocation()
  return <span data-testid="probe-location">{location.pathname + location.search}</span>
}

function renderApp(route: string) {
  return renderWithProviders(
    <>
      <App />
      <LocationProbe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function nth<T>(items: readonly T[], index: number): T {
  const item = items[index]
  if (item === undefined) throw new Error('missing item')
  return item
}

async function chooseInSwitcher(optionTestId: string) {
  await userEvent.click(byTestId('account-switcher'))
  await userEvent.click(await screen.findByTestId(optionTestId))
}

function rememberAll() {
  window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'ALL')
}

describe('FEAT-035 全部账户 switcher (rule 1, Q219 / Q220)', () => {
  it('offers 全部账户 only with at least 2 accounts', async () => {
    startServer({ accounts: [JIA] })
    renderApp('/')
    await userEvent.click(await screen.findByTestId('account-switcher'))
    expect(await screen.findByTestId('account-option-1')).toBeInTheDocument()
    expect(screen.queryByTestId('account-option-all')).not.toBeInTheDocument()
  })

  it('falls back to the lowest id when ALL is remembered but only one account is left', async () => {
    rememberAll()
    const requests = startServer({ accounts: [YI] })
    renderApp('/')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('乙账户')
    })
    expect(screen.queryByTestId('all-accounts-banner')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(requests.some((r) => r.path === '/api/dashboard')).toBe(true)
    })
    expect(requests.every((r) => r.account !== 'ALL')).toBe(true)
  })

  it('selecting 全部账户 shows the read-only state, sends ALL and is remembered', async () => {
    const requests = startServer()
    renderApp('/positions')
    await screen.findAllByTestId('position-row')
    await chooseInSwitcher('account-option-all')

    expect(byTestId('account-switcher')).toHaveTextContent('当前：全部账户（只读）')
    expect(byTestId('all-accounts-read-only-hint')).toHaveTextContent(
      '全部账户（只读）：录入、修改、删除请先选择一个账户',
    )
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('ALL')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(4)
    })
    const allReads = requests.filter((r) => r.account === 'ALL')
    expect(allReads.map((r) => r.path)).toEqual(
      expect.arrayContaining(['/api/option-positions', '/api/stock-positions']),
    )
    expect(allReads.every((r) => r.method === 'GET')).toBe(true)
    // The 录入交易 nav entry is hidden (rule 5).
    expect(screen.queryByTestId('nav-open-trade')).not.toBeInTheDocument()
  })

  it('restores 全部账户 after a reload and switching to 甲账户 restores the single view', async () => {
    rememberAll()
    startServer()
    renderApp('/positions')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(4)
    })
    expect(byTestId('account-current')).toHaveTextContent('全部账户（只读）')
    expect(screen.getAllByRole('columnheader', { name: '账户' })).toHaveLength(2)

    await chooseInSwitcher('account-option-1')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(2)
    })
    expect(byTestId('account-current')).toHaveTextContent('甲账户')
    expect(screen.queryByTestId('all-accounts-banner')).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '账户' })).not.toBeInTheDocument()
    expect(screen.queryAllByTestId('position-cell-account')).toHaveLength(0)
    expect(screen.getAllByTestId('position-close').length).toBeGreaterThan(0)
    expect(screen.getAllByTestId('position-assign').length).toBeGreaterThan(0)
    expect(byTestId('nav-open-trade')).toBeInTheDocument()
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('1')
  })
})

describe('FEAT-035 home page (acceptance 2)', () => {
  it('shows the backend totals and hides 录入交易', async () => {
    rememberAll()
    startServer()
    renderApp('/')
    expect(await screen.findByTestId('dashboard-position-count')).toHaveTextContent('4')
    expect(byTestId('cash-summary-total')).toHaveTextContent('70,473.05')
    expect(byTestId('cash-summary-frozen')).toHaveTextContent('14,500.00')
    expect(byTestId('cash-summary-available')).toHaveTextContent('55,973.05')
    expect(byTestId('dashboard-realized-total')).toHaveTextContent('+90.00')
    expect(byTestId('dashboard-realized-count')).toHaveTextContent('2')
    expect(byTestId('dashboard-month-total')).toHaveTextContent('+60.00')
    expect(byTestId('dashboard-frozen-total')).toHaveTextContent('14,500.00')
    expect(byTestId('dashboard-expiring-count')).toHaveTextContent('1')
    expect(byTestId('dashboard-expired-count')).toHaveTextContent('1')
    expect(byTestId('dashboard-stock-position-count')).toHaveTextContent('1')
    expect(screen.queryByTestId('dashboard-new-trade')).not.toBeInTheDocument()
  })
})

describe('FEAT-035 cash page (acceptance 3)', () => {
  it('lists every account plus 合计, without deposit / withdraw', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/cash')
    const rows = await screen.findAllByTestId('cash-summary-account-row')
    expect(rows).toHaveLength(2)
    const cells = (row: HTMLElement) => [
      within(row).getByTestId('cash-summary-account-name').textContent,
      within(row).getByTestId('cash-summary-account-totalCash').textContent,
      within(row).getByTestId('cash-summary-account-frozenCash').textContent,
      within(row).getByTestId('cash-summary-account-availableCash').textContent,
    ]
    expect(cells(nth(rows, 0))).toEqual(['甲账户', '50,263.05', '5,000.00', '45,263.05'])
    expect(cells(nth(rows, 1))).toEqual(['乙账户', '20,210.00', '9,500.00', '10,710.00'])
    const sum = byTestId('cash-summary-sum-row')
    expect(within(sum).getByTestId('cash-summary-sum-label')).toHaveTextContent('合计')
    expect(within(sum).getByTestId('cash-summary-total')).toHaveTextContent('70,473.05')
    expect(within(sum).getByTestId('cash-summary-frozen')).toHaveTextContent('14,500.00')
    expect(within(sum).getByTestId('cash-summary-available')).toHaveTextContent('55,973.05')

    expect(screen.queryByTestId('cash-transfer-card')).not.toBeInTheDocument()
    // FEAT-046: the ledger is no longer a placeholder (merged ledger, see AllAccountsLists.test.tsx).
    expect(await screen.findByTestId('cash-ledger')).toBeInTheDocument()
    expect(screen.queryByTestId('cash-ledger-all-accounts')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(requests.find((r) => r.path === '/api/cash/ledger')?.account).toBe('ALL')
    })
  })

  it('switching to 乙账户 shows the normal single-account page', async () => {
    rememberAll()
    startServer()
    renderApp('/cash')
    await screen.findAllByTestId('cash-summary-account-row')
    await chooseInSwitcher('account-option-2')
    expect(await screen.findByTestId('cash-transfer-card')).toBeInTheDocument()
    expect(byTestId('account-current')).toHaveTextContent('乙账户')
    expect(screen.queryByTestId('cash-summary-accounts')).not.toBeInTheDocument()
  })
})

describe('FEAT-035 positions page (acceptance 4, 5, 6, 9, 10)', () => {
  it('shows the 账户 first column, backend order, totals and no write action', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/positions')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(4)
    })
    const rows = screen.getAllByTestId('position-row')
    expect(rows.map((row) => within(row).getByTestId('position-cell-account').textContent)).toEqual(
      ['乙账户', '甲账户', '甲账户', '乙账户'],
    )
    expect(rows.map((row) => row.getAttribute('data-contract'))).toEqual([
      'QRTV 2026-11-20 45 P',
      'IXQZ 2026-11-27 65 C',
      'QRTV 2026-12-18 50 P',
      'QRTV 2026-12-18 50 P',
    ])
    // 账户 is the first column.
    const headers = screen.getAllByRole('columnheader').map((header) => header.textContent)
    expect(headers.filter((text) => text !== '')[0]).toBe('账户')
    expect(within(nth(rows, 2)).getByTestId('position-cell-opening-net')).toHaveTextContent('118.70')
    expect(within(nth(rows, 3)).getByTestId('position-cell-opening-net')).toHaveTextContent('120.00')
    expect(byTestId('positions-count')).toHaveTextContent('共 4 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('14,500.00')
    expect(await screen.findByTestId('unrealized-summary-excluded')).toHaveTextContent(
      '1 个持仓已到期待处理，未计入',
    )

    // Stock table: 账户 column, no 卖出 / 录入.
    const stockRow = await screen.findByTestId('stock-position-row')
    expect(within(stockRow).getByTestId('stock-position-account')).toHaveTextContent('甲账户')
    expect(screen.queryByTestId('stock-position-sell')).not.toBeInTheDocument()
    expect(screen.queryByTestId('stock-positions-new')).not.toBeInTheDocument()

    // Rule 5: no write buttons; rule 6: quote refresh and manual mark stay.
    for (const id of [
      'position-close',
      'position-expire',
      'position-assign',
      'position-exercise',
      'position-adjust-expiration',
      'positions-batch-expire',
    ]) {
      expect(screen.queryByTestId(id)).not.toBeInTheDocument()
    }
    expect(screen.getAllByTestId('position-mark-set')).toHaveLength(4)
    expect(await screen.findByTestId('market-data-refresh')).toBeInTheDocument()

    // Every request went out as a GET.
    expect(requests.filter((r) => r.account === 'ALL').every((r) => r.method === 'GET')).toBe(true)
  })

  it('keeps 批量作废 hidden under 已过期未处理 and the totals unfiltered', async () => {
    rememberAll()
    startServer()
    renderApp('/positions?expiry=expired')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    })
    const row = byTestId('position-row')
    expect(within(row).getByTestId('position-cell-account')).toHaveTextContent('乙账户')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('14,500.00')
    expect(screen.queryByTestId('positions-batch-expire')).not.toBeInTheDocument()
  })
  // FEAT-047: the merged Greeks summary replaced the single-account note (AllAccountsStrategies.test.tsx).
})

describe('FEAT-035 ad-hoc closure alerts (acceptance 8)', () => {
  it('lists 切换到 <账户> 去调整 per account; a click switches and locates that row', async () => {
    rememberAll()
    startServer({ closure: true })
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    })
    renderApp('/')
    expect(await screen.findByTestId('adhoc-closure-alert-message')).toHaveTextContent(
      ALERT_MESSAGE,
    )
    expect(byTestId('adhoc-closure-alert-switch-1')).toHaveTextContent('切换到 甲账户 去调整')
    expect(byTestId('adhoc-closure-alert-switch-2')).toHaveTextContent('切换到 乙账户 去调整')
    expect(screen.queryByTestId('adhoc-closure-alert-adjust')).not.toBeInTheDocument()

    await userEvent.click(byTestId('adhoc-closure-alert-switch-2'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('乙账户')
    })
    expect(byTestId('probe-location')).toHaveTextContent('/positions')
    await waitFor(() => {
      expect(
        screen
          .getAllByTestId('position-row')
          .find((row) => row.getAttribute('data-highlighted') === 'true')
          ?.getAttribute('data-contract'),
      ).toBe('QRTV 2026-12-18 50 P')
    })
    const highlighted = screen
      .getAllByTestId('position-row')
      .filter((row) => row.getAttribute('data-highlighted') === 'true')
    expect(highlighted).toHaveLength(1)
    expect(nth(highlighted, 0).getAttribute('data-account-id')).toBe('2')
    expect(scrollIntoView).toHaveBeenCalled()
    // Single account again: 去调整 is back.
    expect(await screen.findByTestId('adhoc-closure-alert-adjust')).toBeInTheDocument()
  })

  it('on the positions page the switch keeps the URL filters and clears a hiding one', async () => {
    rememberAll()
    startServer({ closure: true })
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: vi.fn(),
    })
    renderApp('/positions?expiry=7')
    await userEvent.click(await screen.findByTestId('adhoc-closure-alert-switch-1'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('甲账户')
    })
    // A1 (12-18) is hidden by 7 天内: the filter goes back to 全部 to show it.
    await waitFor(() => {
      expect(byTestId('probe-location')).toHaveTextContent('/positions')
      expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'all')
    })
    await waitFor(() => {
      const row = screen
        .getAllByTestId('position-row')
        .find((candidate) => candidate.getAttribute('data-highlighted') === 'true')
      expect(row?.getAttribute('data-account-id')).toBe('1')
    })
  })
})

describe('FEAT-035 pnl summary (acceptance 7)', () => {
  it('shows the backend all-account rows without an 账户 column', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/pnl-summary')
    expect(await screen.findByTestId('pnl-total-line')).toHaveTextContent('+90.00')
    expect(byTestId('pnl-by-symbol')).toHaveTextContent('QRTV')
    expect(byTestId('pnl-by-month')).toHaveTextContent('2026-10')
    expect(screen.queryByRole('columnheader', { name: '账户' })).not.toBeInTheDocument()
    expect(requests.find((r) => r.path === '/api/pnl-summary')?.account).toBe('ALL')
  })
})

describe('FEAT-035 unsupported pages and entry pages (rule 5, rule 7)', () => {
  // FEAT-046: /trades, /stocks and /closed show merged lists now (AllAccountsLists.test.tsx);
  // FEAT-047: /strategies shows merged cards (AllAccountsStrategies.test.tsx). Only /audit is left.
  it('/audit shows the placeholder and sends no request with ALL', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/audit')
    expect(await screen.findByTestId('all-accounts-placeholder-message')).toHaveTextContent(
      '此页暂不支持全部账户，请选择一个账户查看',
    )
    expect(byTestId('all-accounts-placeholder-account-1')).toHaveTextContent('甲账户')
    expect(byTestId('all-accounts-placeholder-account-2')).toHaveTextContent('乙账户')
    expect(requests.filter((r) => r.path === '/api/audit-log')).toEqual([])
  })

  it('a placeholder button switches to that account', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/audit')
    await userEvent.click(await screen.findByTestId('all-accounts-placeholder-account-2'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('乙账户')
    })
    expect(screen.queryByTestId('all-accounts-placeholder')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(requests.some((r) => r.path === '/api/audit-log' && r.account === '2')).toBe(true)
    })
  })

  it.each([
    ['/trades/new', 'open-trade-page'],
    ['/stocks/new', 'stock-entry-page'],
  ])(
    '%s asks for an account; choosing 乙账户 shows the form',
    async (route, pageTestId) => {
      rememberAll()
      startServer()
      renderApp(route)
      expect(await screen.findByTestId('all-accounts-entry-message')).toHaveTextContent(
        '录入需要选择一个账户',
      )
      expect(byTestId('all-accounts-entry-account-1')).toHaveTextContent('甲账户')
      expect(screen.queryByTestId(pageTestId)).not.toBeInTheDocument()

      await userEvent.click(byTestId('all-accounts-entry-account-2'))
      await waitFor(() => {
        expect(byTestId('account-current')).toHaveTextContent('乙账户')
      })
      expect(await screen.findByTestId(pageTestId)).toBeInTheDocument()
      expect(screen.queryByTestId('all-accounts-entry')).not.toBeInTheDocument()
      expect(byTestId('probe-location')).toHaveTextContent(route)
    },
  )
})
