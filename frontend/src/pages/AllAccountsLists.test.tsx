import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { ACCOUNT_STORAGE_KEY } from '../account/accountStorage'
import type {
  Account,
  ApiErrorResponse,
  AuditLogPage,
  CashLedgerEntry,
  CashLedgerPage,
  CashSummary,
  ClosedOptionRecord,
  ClosedStockRecord,
  ClosedTrade,
  ClosedTradePage,
  OptionTrade,
  OptionTradePage,
  PnlSummary,
  StockTrade,
  StockTradePage,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'

// ---------------------------------------------------------------------------
// FEAT-046 scene L (fictional accounts 甲账户 id 1 / 乙账户 id 2, tickers QRTV / IXQZ), served per
// `X-Account-Id` like the contract examples. Rows are listed in backend order; the mock only
// filters by account and by the few query parameters the tests use — every number is fixed.
// ---------------------------------------------------------------------------

const JIA: Account = { id: 1, name: '甲账户', brokerNote: null, createdAt: '2026-10-01T13:00:00Z' }
const YI: Account = { id: 2, name: '乙账户', brokerNote: null, createdAt: '2026-10-01T13:00:00Z' }
const NAMES: Record<number, string> = { 1: '甲账户', 2: '乙账户' }

type Scope = '1' | '2' | 'ALL'

function inScope<T extends { accountId?: number }>(rows: T[], scope: Scope): T[] {
  return scope === 'ALL' ? rows : rows.filter((row) => String(row.accountId) === scope)
}

function trade(
  accountId: number,
  id: number,
  partial: Partial<OptionTrade> & Pick<OptionTrade, 'action' | 'tradedAt' | 'cashChange'>,
): OptionTrade {
  const merged: OptionTrade = {
    accountId,
    accountName: NAMES[accountId],
    id,
    symbol: 'QRTV',
    expirationDate: '2026-12-18',
    strike: '50',
    optionType: 'PUT',
    contracts: 1,
    multiplier: 100,
    netAmount: '0.00',
    tradedAtLocal: '',
    frozenChange: '0.00',
    contractLabel: '',
    note: null,
    ledgerEntryId: null,
    createdAt: '2026-11-23T15:00:00Z',
    coveredLots: null,
    realizedPnl: null,
    hasAuditLog: false,
    expiredDirection: null,
    assignment: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

// Backend order (time desc, later-entered first, Q229). Scene numbers in comments.
const TRADES: OptionTrade[] = [
  trade(1, 8, {
    symbol: 'IXQZ',
    expirationDate: '2026-11-27',
    strike: '65',
    optionType: 'CALL',
    action: 'STO',
    netAmount: '84.35',
    cashChange: '84.35',
    tradedAt: '2026-11-16T15:30:00Z',
  }), // #11
  trade(2, 7, {
    action: 'STO',
    netAmount: '120.00',
    cashChange: '120.00',
    frozenChange: '5000.00',
    tradedAt: '2026-11-16T15:00:00Z',
    note: '同价位',
    hasAuditLog: true,
  }), // #10
  trade(1, 6, {
    action: 'STO',
    netAmount: '118.70',
    cashChange: '118.70',
    frozenChange: '5000.00',
    tradedAt: '2026-11-16T15:00:00Z',
  }), // #9
  trade(1, 5, {
    strike: '48',
    action: 'BTC',
    netAmount: '40.00',
    cashChange: '-40.00',
    frozenChange: '-4800.00',
    tradedAt: '2026-11-10T15:00:00Z',
    realizedPnl: '60.00',
  }), // #8
  trade(2, 4, {
    expirationDate: '2026-11-20',
    strike: '45',
    action: 'STO',
    netAmount: '60.00',
    cashChange: '60.00',
    frozenChange: '4500.00',
    tradedAt: '2026-11-02T15:00:00Z',
  }), // #7
  trade(1, 3, {
    strike: '48',
    action: 'STO',
    netAmount: '100.00',
    cashChange: '100.00',
    frozenChange: '4800.00',
    tradedAt: '2026-11-02T15:00:00Z',
  }), // #6
  trade(2, 2, {
    strike: '55',
    optionType: 'CALL',
    action: 'STC',
    netAmount: '80.00',
    cashChange: '80.00',
    tradedAt: '2026-10-20T14:00:00Z',
    realizedPnl: '30.00',
  }), // #5
  trade(2, 1, {
    strike: '55',
    optionType: 'CALL',
    action: 'BTO',
    netAmount: '50.00',
    cashChange: '-50.00',
    tradedAt: '2026-10-15T14:00:00Z',
  }), // #4
]

function stock(accountId: number, partial: Partial<StockTrade> & Pick<StockTrade, 'id'>): StockTrade {
  return {
    accountId,
    accountName: NAMES[accountId],
    type: 'BUY',
    symbol: 'QRTV',
    shares: 100,
    amount: '0.00',
    cashChange: '0.00',
    pricePerShare: '0.0000',
    tradedAt: '',
    tradedAtLocal: '',
    tradeDate: null,
    note: null,
    ledgerEntryId: null,
    createdAt: '2026-11-23T15:00:00Z',
    hasAuditLog: false,
    soldCost: null,
    realizedPnl: null,
    soldLots: null,
    assignmentTradeId: null,
    editable: true,
    deletable: true,
    ...partial,
  }
}

const STOCKS: StockTrade[] = [
  stock(2, {
    id: 3,
    type: 'SELL',
    shares: 50,
    amount: '2450.00',
    cashChange: '2450.00',
    pricePerShare: '49.0000',
    tradedAt: '2026-11-18T15:00:00Z',
    tradedAtLocal: '2026-11-18T10:00:00',
    soldCost: '2400.00',
    realizedPnl: '50.00',
    soldLots: [
      {
        tradeId: 2,
        type: 'BUY',
        tradedAt: '2026-11-17T15:00:00Z',
        tradedAtLocal: '2026-11-17T10:00:00',
        tradeDate: null,
        shares: 50,
        cost: '2400.00',
      },
    ],
  }), // #13
  stock(2, {
    id: 2,
    amount: '4800.00',
    cashChange: '-4800.00',
    pricePerShare: '48.0000',
    tradedAt: '2026-11-17T15:00:00Z',
    tradedAtLocal: '2026-11-17T10:00:00',
  }), // #12
  stock(1, {
    id: 1,
    type: 'TRANSFER_IN',
    symbol: 'IXQZ',
    amount: '5880.00',
    pricePerShare: '58.8000',
    tradedAt: '2026-10-01T04:00:00Z',
    tradedAtLocal: '2026-10-01T00:00:00',
    tradeDate: '2026-10-01',
  }), // #3
]

const CLOSED_STOCK: ClosedStockRecord = {
  accountId: 2,
  accountName: '乙账户',
  type: 'STOCK',
  id: 3,
  tradedAt: '2026-11-18T15:00:00Z',
  tradedAtLocal: '2026-11-18T10:00:00',
  symbol: 'QRTV',
  expirationDate: null,
  strike: null,
  optionType: null,
  contractLabel: null,
  action: 'SELL',
  closedDirection: null,
  contracts: null,
  multiplier: null,
  shares: 50,
  openingNetTotal: '-2400.00',
  openingNetPerContract: null,
  costPerShare: '48.0000',
  closeNetAmount: '2450.00',
  closeNetPerContract: null,
  pricePerShare: '49.0000',
  realizedPnl: '50.00',
  note: null,
  coveredLots: null,
  soldLots: [
    {
      tradeId: 2,
      type: 'BUY',
      tradedAt: '2026-11-17T15:00:00Z',
      tradedAtLocal: '2026-11-17T10:00:00',
      tradeDate: null,
      shares: 50,
      netAmount: '-2400.00',
    },
  ],
  assignment: null,
  hasAuditLog: false,
}

function closedOption(
  accountId: number,
  partial: Partial<ClosedOptionRecord> & Pick<ClosedOptionRecord, 'id' | 'strike'>,
): ClosedOptionRecord {
  const merged: ClosedOptionRecord = {
    accountId,
    accountName: NAMES[accountId],
    type: 'OPTION',
    tradedAt: '',
    tradedAtLocal: '',
    symbol: 'QRTV',
    expirationDate: '2026-12-18',
    optionType: 'PUT',
    contractLabel: '',
    action: 'BTC',
    closedDirection: 'SHORT',
    contracts: 1,
    multiplier: 100,
    shares: null,
    openingNetTotal: '0.00',
    openingNetPerContract: '0.00',
    costPerShare: null,
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    pricePerShare: null,
    realizedPnl: '0.00',
    note: null,
    coveredLots: [],
    soldLots: null,
    assignment: null,
    hasAuditLog: false,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const CLOSED: ClosedTrade[] = [
  CLOSED_STOCK, // #13
  closedOption(1, {
    id: 5,
    strike: '48',
    tradedAt: '2026-11-10T15:00:00Z',
    tradedAtLocal: '2026-11-10T10:00:00',
    openingNetTotal: '100.00',
    openingNetPerContract: '100.00',
    closeNetAmount: '-40.00',
    closeNetPerContract: '-40.00',
    realizedPnl: '60.00',
  }), // #8
  closedOption(2, {
    id: 2,
    strike: '55',
    optionType: 'CALL',
    action: 'STC',
    closedDirection: 'LONG',
    tradedAt: '2026-10-20T14:00:00Z',
    tradedAtLocal: '2026-10-20T10:00:00',
    openingNetTotal: '-50.00',
    openingNetPerContract: '-50.00',
    closeNetAmount: '80.00',
    closeNetPerContract: '80.00',
    realizedPnl: '30.00',
  }), // #5
]

/** Closed totals per scope and filter, as the backend would give them (Q216). */
function closedTotals(scope: Scope, monthOnly: boolean): [number, string] {
  if (scope === '1') return [1, '60.00']
  if (scope === '2') return monthOnly ? [1, '50.00'] : [2, '80.00']
  return monthOnly ? [2, '110.00'] : [3, '140.00']
}

function ledger(
  accountId: number,
  id: number,
  partial: Partial<CashLedgerEntry> & Pick<CashLedgerEntry, 'amount' | 'balanceAfter'>,
): CashLedgerEntry {
  return {
    accountId,
    accountName: NAMES[accountId],
    id,
    occurredAt: '2026-11-16T15:00:00Z',
    sourceType: 'OPTION_OPEN',
    sourceId: id,
    frozenChange: '0.00',
    description: null,
    remark: null,
    hasAuditLog: false,
    ...partial,
  }
}

// First rows and last two rows of acceptance 7 (12 entries in all; the mock serves only these).
const LEDGER: CashLedgerEntry[] = [
  ledger(2, 12, {
    occurredAt: '2026-11-18T15:00:00Z',
    sourceType: 'STOCK_SELL',
    sourceId: 3,
    amount: '2450.00',
    balanceAfter: '17860.00',
  }),
  ledger(2, 11, {
    occurredAt: '2026-11-17T15:00:00Z',
    sourceType: 'STOCK_BUY',
    sourceId: 2,
    amount: '-4800.00',
    balanceAfter: '15410.00',
  }),
  ledger(1, 10, {
    occurredAt: '2026-11-16T15:30:00Z',
    sourceId: 8,
    amount: '84.35',
    balanceAfter: '50263.05',
  }),
  ledger(2, 9, { sourceId: 7, amount: '120.00', balanceAfter: '20210.00', remark: '同价位' }),
  ledger(1, 8, { sourceId: 6, amount: '118.70', balanceAfter: '50178.70' }),
  ledger(2, 2, {
    occurredAt: '2026-10-01T13:30:00Z',
    sourceType: 'DEPOSIT',
    sourceId: 2,
    amount: '20000.00',
    balanceAfter: '20000.00',
    hasAuditLog: true,
  }),
  ledger(1, 1, {
    occurredAt: '2026-10-01T13:30:00Z',
    sourceType: 'DEPOSIT',
    sourceId: 1,
    amount: '50000.00',
    balanceAfter: '50000.00',
  }),
]

const CASH: Record<Scope, CashSummary> = {
  '1': { totalCash: '50263.05', frozenCash: '5000.00', availableCash: '45263.05' },
  '2': { totalCash: '17860.00', frozenCash: '9500.00', availableCash: '8360.00' },
  ALL: {
    totalCash: '70123.05',
    frozenCash: '14500.00',
    availableCash: '55623.05',
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
        totalCash: '17860.00',
        frozenCash: '9500.00',
        availableCash: '8360.00',
      },
    ],
  },
}

const PNL_ALL: PnlSummary = {
  startDate: null,
  endDate: null,
  closedCount: 3,
  realizedPnlTotal: '140.00',
  option: { closedCount: 2, realizedPnl: '90.00' },
  stock: { closedCount: 1, realizedPnl: '50.00' },
  bySymbol: [
    {
      symbol: 'QRTV',
      closedCount: 3,
      realizedPnl: '140.00',
      option: { closedCount: 2, realizedPnl: '90.00' },
      stock: { closedCount: 1, realizedPnl: '50.00' },
    },
  ],
  byMonth: [
    {
      month: '2026-11',
      closedCount: 2,
      realizedPnl: '110.00',
      option: { closedCount: 1, realizedPnl: '60.00' },
      stock: { closedCount: 1, realizedPnl: '50.00' },
    },
    {
      month: '2026-10',
      closedCount: 1,
      realizedPnl: '30.00',
      option: { closedCount: 1, realizedPnl: '30.00' },
      stock: { closedCount: 0, realizedPnl: '0.00' },
    },
  ],
}

interface Recorded {
  method: string
  path: string
  search: URLSearchParams
  account: string | undefined
}

function page<T>(items: T[], size = 20) {
  return {
    items,
    page: 1,
    size,
    totalItems: items.length,
    totalPages: items.length === 0 ? 0 : Math.ceil(items.length / size),
  }
}

function notSupported(path: string): Response {
  const body: ApiErrorResponse = {
    code: 'ALL_ACCOUNTS_NOT_SUPPORTED',
    message: '此接口暂不支持全部账户，请选择一个账户',
    status: 400,
    path,
    timestamp: '2026-11-23T15:00:00Z',
    details: [],
  }
  return jsonResponse(body, 400)
}

function startServer({ empty = false }: { empty?: boolean } = {}) {
  const requests: Recorded[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    const { pathname: path, searchParams: search } = new URL(url, 'http://localhost')
    requests.push({ method, path, search, account })
    const scope = (account ?? '1') as Scope

    switch (path) {
      case '/api/accounts':
        return Promise.resolve(jsonResponse([JIA, YI]))
      case '/api/option-trades': {
        let rows = empty ? [] : inScope(TRADES, scope)
        const symbol = search.get('symbol')
        if (symbol) rows = rows.filter((row) => row.symbol === symbol.toUpperCase())
        const strike = search.get('strike')
        if (strike) rows = rows.filter((row) => row.strike === strike)
        const actions = search.getAll('action')
        if (actions.length > 0) rows = rows.filter((row) => actions.includes(row.action))
        const body: OptionTradePage = page(rows)
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/stock-trades': {
        let rows = empty ? [] : inScope(STOCKS, scope)
        const symbol = search.get('symbol')
        if (symbol) rows = rows.filter((row) => row.symbol === symbol.toUpperCase())
        const body: StockTradePage = page(rows)
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/closed-trades': {
        const monthOnly = search.get('startDate') === '2026-11-01'
        let rows = inScope(CLOSED, scope)
        if (monthOnly) rows = rows.filter((row) => row.tradedAt >= '2026-11-01')
        const [count, total] = closedTotals(scope, monthOnly)
        const body: ClosedTradePage = {
          ...page(rows),
          filteredCount: count,
          filteredRealizedPnlTotal: total,
        }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/cash/ledger': {
        const rows = inScope(LEDGER, scope)
        const body: CashLedgerPage = { ...page(rows), totalItems: scope === 'ALL' ? 12 : rows.length }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/cash/summary':
        return Promise.resolve(jsonResponse(CASH[scope]))
      case '/api/pnl-summary':
        return Promise.resolve(jsonResponse(PNL_ALL))
      case '/api/audit-log': {
        if (scope === 'ALL') return Promise.resolve(notSupported(path))
        const body: AuditLogPage = { items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }
        return Promise.resolve(jsonResponse(body))
      }
      default:
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

function rememberAll() {
  window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'ALL')
}

async function chooseInSwitcher(optionTestId: string) {
  await userEvent.click(byTestId('account-switcher'))
  await userEvent.click(await screen.findByTestId(optionTestId))
}

function texts(rows: HTMLElement[], testId: string): (string | null)[] {
  return rows.map((row) => within(row).getByTestId(testId).textContent)
}

function nth<T>(items: readonly T[], index: number): T {
  const item = items[index]
  if (item === undefined) throw new Error('missing item')
  return item
}

function firstHeader(): string | null | undefined {
  return screen
    .getAllByRole('columnheader')
    .map((header) => header.textContent)
    .filter((text) => text !== '')[0]
}

describe('FEAT-046 trade list in 全部账户 mode (acceptance 1, 2, 8, 9)', () => {
  it('shows the merged list with the 账户 first column and no write entry', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/trades')
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(8)
    })
    const rows = screen.getAllByTestId('trade-row')
    expect(texts(rows, 'trade-cell-account')).toEqual([
      '甲账户',
      '乙账户',
      '甲账户',
      '甲账户',
      '乙账户',
      '甲账户',
      '乙账户',
      '乙账户',
    ])
    expect(rows.map((row) => row.getAttribute('data-account-id'))).toEqual([
      '1',
      '2',
      '1',
      '1',
      '2',
      '1',
      '2',
      '2',
    ])
    expect(firstHeader()).toBe('账户')
    expect(within(nth(rows, 0)).getByTestId('trade-cell-contract')).toHaveTextContent(
      'IXQZ 2026-11-27 65 C',
    )
    expect(within(nth(rows, 0)).getByTestId('trade-cell-net-amount')).toHaveTextContent('+84.35')
    expect(within(nth(rows, 1)).getByTestId('trade-cell-net-amount')).toHaveTextContent('+120.00')
    expect(within(nth(rows, 1)).getByTestId('trade-cell-frozen')).toHaveTextContent('+5,000.00')
    expect(within(nth(rows, 3)).getByTestId('trade-cell-net-amount')).toHaveTextContent('−40.00')
    expect(within(nth(rows, 3)).getByTestId('trade-cell-frozen')).toHaveTextContent('−4,800.00')
    expect(within(nth(rows, 7)).getByTestId('trade-cell-net-amount')).toHaveTextContent('−50.00')
    expect(byTestId('trade-list-total')).toHaveTextContent('共 8 条')

    // Rule 8 (Q217): no 录入开仓 / 修改 / 删除; 历史 only on #10 (Q231).
    expect(screen.queryByTestId('trade-list-new')).not.toBeInTheDocument()
    expect(screen.queryAllByTestId('trade-edit')).toHaveLength(0)
    expect(screen.queryAllByTestId('trade-delete')).toHaveLength(0)
    const history = screen.getAllByTestId('trade-history')
    expect(history).toHaveLength(1)
    expect(within(nth(rows, 1)).getByTestId('trade-history')).toBeInTheDocument()

    expect(requests.find((r) => r.path === '/api/option-trades')?.account).toBe('ALL')
  })

  it('filters work on the merged list and switching to 乙账户 keeps the URL filters', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/trades?symbol=qrtv')
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(7)
    })
    expect(byTestId('trade-list-total')).toHaveTextContent('共 7 条')

    // Click #10's contract: filter by that contract, still 全部账户.
    const rows = screen.getAllByTestId('trade-row')
    await userEvent.click(within(nth(rows, 0)).getByTestId('trade-cell-contract'))
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(2)
    })
    expect(byTestId('probe-location')).toHaveTextContent('strike=50')
    expect(texts(screen.getAllByTestId('trade-row'), 'trade-cell-account')).toEqual([
      '乙账户',
      '甲账户',
    ])
    expect(byTestId('account-current')).toHaveTextContent('全部账户（只读）')

    const before = byTestId('probe-location').textContent
    await chooseInSwitcher('account-option-2')
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(1)
    })
    expect(byTestId('probe-location').textContent).toBe(before)
    expect(screen.queryByRole('columnheader', { name: '账户' })).not.toBeInTheDocument()
    expect(screen.queryAllByTestId('trade-cell-account')).toHaveLength(0)
    expect(byTestId('trade-edit')).toBeInTheDocument()
    expect(byTestId('trade-delete')).toBeInTheDocument()
    expect(byTestId('trade-list-new')).toBeInTheDocument()
    const last = requests.filter((r) => r.path === '/api/option-trades').at(-1)
    expect(last?.account).toBe('2')
    expect(last?.search.get('strike')).toBe('50')
  })

  it('历史 switches to the row account and opens the audit page for that record (Q231)', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/trades')
    await userEvent.click(await screen.findByTestId('trade-history'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('乙账户')
    })
    expect(byTestId('probe-location')).toHaveTextContent('/audit?objectType=OPTION_TRADE&objectId=7')
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('2')
    await waitFor(() => {
      expect(requests.some((r) => r.path === '/api/audit-log')).toBe(true)
    })
    const audits = requests.filter((r) => r.path === '/api/audit-log')
    expect(audits.every((r) => r.account === '2')).toBe(true)
    expect(nth(audits, 0).search.get('objectType')).toBe('OPTION_TRADE')
    expect(nth(audits, 0).search.get('objectId')).toBe('7')
    expect(screen.queryByTestId('all-accounts-placeholder')).not.toBeInTheDocument()
  })

  it('switching to 甲账户 restores the single-account list (acceptance 9)', async () => {
    rememberAll()
    startServer()
    renderApp('/trades')
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(8)
    })
    await chooseInSwitcher('account-option-1')
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(4)
    })
    expect(screen.queryByRole('columnheader', { name: '账户' })).not.toBeInTheDocument()
    expect(screen.getAllByTestId('trade-edit')).toHaveLength(4)
    expect(screen.getAllByTestId('trade-delete')).toHaveLength(4)
    expect(byTestId('trade-list-new')).toBeInTheDocument()
  })

  it('the empty state has no 录入开仓 link', async () => {
    rememberAll()
    startServer({ empty: true })
    renderApp('/trades')
    expect(await screen.findByTestId('trade-list-empty')).toHaveTextContent('暂无交易')
    expect(screen.queryByTestId('trade-list-empty-new')).not.toBeInTheDocument()
  })
})

describe('FEAT-046 stock trades in 全部账户 mode (acceptance 3)', () => {
  it('shows the merged records, the sold lots and no write entry', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/stocks')
    await waitFor(() => {
      expect(screen.getAllByTestId('stock-row')).toHaveLength(3)
    })
    const rows = screen.getAllByTestId('stock-row')
    expect(texts(rows, 'stock-cell-account')).toEqual(['乙账户', '乙账户', '甲账户'])
    expect(firstHeader()).toBe('账户')
    expect(within(nth(rows, 0)).getByTestId('stock-cell-amount')).toHaveTextContent('+2,450.00')
    expect(within(nth(rows, 1)).getByTestId('stock-cell-amount')).toHaveTextContent('−4,800.00')
    expect(within(nth(rows, 2)).getByTestId('stock-cell-amount')).toHaveTextContent(
      '总成本 5,880.00',
    )
    expect(byTestId('stock-list-total')).toHaveTextContent('共 3 条')

    await userEvent.click(within(nth(rows, 0)).getByTestId('stock-expand'))
    expect(await screen.findByTestId('stock-sold-cost')).toHaveTextContent('2,400.00')

    for (const id of ['stock-list-new', 'stock-edit', 'stock-delete', 'stock-history']) {
      expect(screen.queryByTestId(id)).not.toBeInTheDocument()
    }
    expect(requests.find((r) => r.path === '/api/stock-trades')?.account).toBe('ALL')
  })

  it('the empty state has no 录入股票交易 link', async () => {
    rememberAll()
    startServer({ empty: true })
    renderApp('/stocks')
    expect(await screen.findByTestId('stock-list-empty')).toHaveTextContent('暂无股票交易')
    expect(screen.queryByTestId('stock-list-empty-new')).not.toBeInTheDocument()
  })
})

describe('FEAT-046 closed records in 全部账户 mode (acceptance 4, 5, 6)', () => {
  it('shows the backend totals and the merged rows with the 账户 column', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/closed')
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(3)
    })
    const rows = screen.getAllByTestId('closed-row')
    expect(byTestId('closed-count')).toHaveTextContent('共 3 笔平仓')
    expect(byTestId('closed-pnl-total')).toHaveTextContent('+140.00')
    expect(texts(rows, 'closed-cell-account')).toEqual(['乙账户', '甲账户', '乙账户'])
    expect(texts(rows, 'closed-cell-pnl')).toEqual(['+50.00', '+60.00', '+30.00'])
    expect(firstHeader()).toBe('账户')
    expect(screen.queryByTestId('all-accounts-placeholder')).not.toBeInTheDocument()
    expect(requests.find((r) => r.path === '/api/closed-trades')?.account).toBe('ALL')
  })

  it('a pnl-summary month deep link lands on the merged closed list', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/pnl-summary')
    const monthLinks = await screen.findAllByTestId('pnl-month-link')
    await userEvent.click(nth(monthLinks, 0))
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(2)
    })
    expect(byTestId('closed-count')).toHaveTextContent('共 2 笔平仓')
    expect(byTestId('closed-pnl-total')).toHaveTextContent('+110.00')
    expect(byTestId('account-current')).toHaveTextContent('全部账户（只读）')
    const closed = requests.filter((r) => r.path === '/api/closed-trades').at(-1)
    expect(closed?.account).toBe('ALL')
    expect(closed?.search.get('startDate')).toBe('2026-11-01')
  })

  it('a contract link goes to the merged trade list filtered by that contract', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/closed')
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(3)
    })
    const rows = screen.getAllByTestId('closed-row')
    await userEvent.click(within(nth(rows, 1)).getByTestId('closed-cell-contract'))
    await waitFor(() => {
      expect(byTestId('probe-location')).toHaveTextContent('/trades?')
    })
    await screen.findAllByTestId('trade-row')
    expect(byTestId('account-current')).toHaveTextContent('全部账户（只读）')
    const last = requests.filter((r) => r.path === '/api/option-trades').at(-1)
    expect(last?.account).toBe('ALL')
    expect(last?.search.get('strike')).toBe('48')
  })

  it('switching to 甲账户 shows that account only (acceptance 9)', async () => {
    rememberAll()
    startServer()
    renderApp('/closed')
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(3)
    })
    await chooseInSwitcher('account-option-1')
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(1)
    })
    expect(byTestId('closed-count')).toHaveTextContent('共 1 笔平仓')
    expect(byTestId('closed-pnl-total')).toHaveTextContent('+60.00')
    expect(screen.queryAllByTestId('closed-cell-account')).toHaveLength(0)
  })
})

describe('FEAT-046 cash ledger in 全部账户 mode (acceptance 7, 9)', () => {
  it('shows the merged ledger with 账户 and 账户余额, read-only', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/cash')
    await waitFor(() => {
      expect(screen.getAllByTestId('cash-ledger-row')).toHaveLength(7)
    })
    const ledgerCard = byTestId('cash-ledger')
    expect(within(ledgerCard).getByRole('columnheader', { name: '账户余额' })).toBeInTheDocument()
    expect(within(ledgerCard).queryByRole('columnheader', { name: '余额' })).not.toBeInTheDocument()
    const rows = screen.getAllByTestId('cash-ledger-row')
    expect(texts(rows, 'cash-ledger-account')).toEqual([
      '乙账户',
      '乙账户',
      '甲账户',
      '乙账户',
      '甲账户',
      '乙账户',
      '甲账户',
    ])
    expect(within(nth(rows, 0)).getByTestId('cash-ledger-amount')).toHaveTextContent('+2,450.00')
    expect(texts(rows, 'cash-ledger-balance')).toEqual([
      '17,860.00',
      '15,410.00',
      '50,263.05',
      '20,210.00',
      '50,178.70',
      '20,000.00',
      '50,000.00',
    ])
    expect(byTestId('cash-ledger-total')).toHaveTextContent('共 12 条')
    // Summary stays FEAT-035's per-account table.
    expect(byTestId('cash-summary-total')).toHaveTextContent('70,123.05')

    for (const id of [
      'cash-ledger-edit',
      'cash-ledger-delete',
      'cash-ledger-trades-link',
      'cash-ledger-stocks-link',
      'cash-transfer-card',
    ]) {
      expect(screen.queryByTestId(id)).not.toBeInTheDocument()
    }
    // 历史 only on the deposit with audit records.
    expect(screen.getAllByTestId('cash-ledger-history')).toHaveLength(1)
    expect(requests.find((r) => r.path === '/api/cash/ledger')?.account).toBe('ALL')
  })

  it('a deposit 历史 switches to that account and opens its audit records', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/cash')
    await userEvent.click(await screen.findByTestId('cash-ledger-history'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('乙账户')
    })
    expect(byTestId('probe-location')).toHaveTextContent(
      '/audit?objectType=CASH_TRANSFER&objectId=2',
    )
    await waitFor(() => {
      expect(requests.some((r) => r.path === '/api/audit-log' && r.account === '2')).toBe(true)
    })
    expect(requests.some((r) => r.path === '/api/audit-log' && r.account === 'ALL')).toBe(false)
  })

  it('switching to 甲账户 restores 余额, the actions and the transfer form', async () => {
    rememberAll()
    startServer()
    renderApp('/cash')
    await waitFor(() => {
      expect(screen.getAllByTestId('cash-ledger-row')).toHaveLength(7)
    })
    await chooseInSwitcher('account-option-1')
    await waitFor(() => {
      expect(screen.getAllByTestId('cash-ledger-row')).toHaveLength(3)
    })
    const ledgerCard = byTestId('cash-ledger')
    expect(within(ledgerCard).getByRole('columnheader', { name: '余额' })).toBeInTheDocument()
    expect(screen.queryAllByTestId('cash-ledger-account')).toHaveLength(0)
    expect(nth(screen.getAllByTestId('cash-ledger-balance'), 0)).toHaveTextContent('50,263.05')
    expect(byTestId('cash-ledger-edit')).toBeInTheDocument()
    expect(screen.getAllByTestId('cash-ledger-trades-link').length).toBeGreaterThan(0)
    expect(byTestId('cash-transfer-card')).toBeInTheDocument()
  })
})
