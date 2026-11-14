import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  Account,
  OptionPosition,
  OptionPositionsResponse,
  StockPosition,
  StockPositionsResponse,
  UnrealizedSummaryGroup,
  UnrealizedSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { refreshQuotes } from '../components/quote/marketDataRefresh'
import { refreshAfterChange } from '../components/trade/tradeFormSupport'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-017 scenario (today 2026-11-23, 10:00 fetch OK; fictional tickers), exactly as the
// contract examples in docs/api/FEAT-017.md return it.

function option(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-12-18',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '0.00',
    averageNetPerContract: '0.00',
    frozenCash: '0.00',
    dte: 25,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 0,
    closeAction: partial.direction === 'LONG' ? 'STC' : 'BTC',
    allowedCloseContracts: [partial.contracts ?? 1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: partial.contracts ?? 1,
    expiryAt: '2026-12-18T21:00:00Z',
    expiryAtLocal: '2026-12-18T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    lots: [],
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const P1 = option({
  openingNetTotal: '118.70',
  marketValue: '-125.00',
  unrealizedPnl: '-6.30',
  unrealizedPnlPercent: '-5.31',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
})
const P2 = option({
  strike: '55',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 2,
  openingNetTotal: '-301.30',
  marketValue: '370.00',
  unrealizedPnl: '68.70',
  unrealizedPnlPercent: '22.80',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
})
const P3 = option({
  symbol: 'IXQZ',
  strike: '65',
  optionType: 'CALL',
  openingNetTotal: '84.35',
  marketValue: '-85.00',
  unrealizedPnl: '-0.65',
  unrealizedPnlPercent: '-0.77',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
})
const P4 = option({
  strike: '47.5',
  openingNetTotal: '45.00',
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'NO_QUOTE',
  unrealizedLabel: '无报价',
})
/** AC 8: expired, not processed (has a last mark, still not computed). */
const P_EXPIRED = option({
  expirationDate: '2026-11-20',
  strike: '45',
  openingNetTotal: '60.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  closable: false,
  expirable: true,
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'EXPIRED_PENDING',
  unrealizedLabel: '已到期，待处理',
})
/** AC 4: account B. */
const P5 = option({
  openingNetTotal: '120.00',
  marketValue: '-125.00',
  unrealizedPnl: '-5.00',
  unrealizedPnlPercent: '-4.17',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
})
/** Edge case: 0.00 denominator → percent null → "—". */
const P_ZERO = option({
  strike: '40',
  openingNetTotal: '0.00',
  marketValue: '-10.00',
  unrealizedPnl: '-10.00',
  unrealizedPnlPercent: null,
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
})

const IXQZ_STOCK: StockPosition = {
  symbol: 'IXQZ',
  shares: 100,
  coveredShares: 100,
  availableShares: 0,
  totalCost: '5880.00',
  averageCost: '58.8000',
  lotCount: 0,
  lots: [],
  marketValue: '6110.00',
  unrealizedPnl: '230.00',
  unrealizedPnlPercent: '3.91',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
}
const NO_PRICE_STOCK: StockPosition = {
  symbol: 'QRTV',
  shares: 50,
  coveredShares: 0,
  availableShares: 50,
  totalCost: '2000.00',
  averageCost: '40.0000',
  lotCount: 0,
  lots: [],
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'NO_QUOTE',
  unrealizedLabel: '无报价',
}

function optionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

function stocksResponse(positions: StockPosition[]): StockPositionsResponse {
  return { positionCount: positions.length, positions }
}

function group(
  marketValue: string,
  unrealizedPnl: string,
  counts: Partial<Pick<UnrealizedSummaryGroup, 'includedCount' | 'noQuoteCount' | 'expiredPendingCount'>> = {},
): UnrealizedSummaryGroup {
  return {
    marketValue,
    unrealizedPnl,
    includedCount: 0,
    noQuoteCount: 0,
    expiredPendingCount: 0,
    ...counts,
  }
}

const SUMMARY_A: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: group('160.00', '61.75', { includedCount: 3, noQuoteCount: 1 }),
  stock: group('6110.00', '230.00', { includedCount: 1 }),
  total: group('6270.00', '291.75', { includedCount: 4, noQuoteCount: 1 }),
  excludedLabel: '1 个持仓无报价，未计入',
}
const SUMMARY_B: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: group('-125.00', '-5.00', { includedCount: 1 }),
  stock: group('0.00', '0.00'),
  total: group('-125.00', '-5.00', { includedCount: 1 }),
  excludedLabel: null,
}
const SUMMARY_EMPTY: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: group('0.00', '0.00'),
  stock: group('0.00', '0.00'),
  total: group('0.00', '0.00'),
  excludedLabel: null,
}

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

interface AccountData {
  options: OptionPositionsResponse
  stocks: StockPositionsResponse
  /** A function so tests can fail it or change it between requests. */
  summary: () => Response
}

interface Recorded {
  url: string
  account: string | undefined
}

/** Fake backend keyed by `X-Account-Id`; account requests are answered here too. */
function startFakeServer(byAccount: Record<number, AccountData>): Recorded[] {
  const requests: Recorded[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    requests.push({ url, account })
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    const data = byAccount[Number(account)]
    if (data !== undefined) {
      if (url === '/api/option-positions') return Promise.resolve(jsonResponse(data.options))
      if (url === '/api/stock-positions') return Promise.resolve(jsonResponse(data.stocks))
      if (url === '/api/positions/unrealized-summary') return Promise.resolve(data.summary())
    }
    if (url === '/api/adhoc-closure-alerts') return Promise.resolve(jsonResponse({ alerts: [] }))
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return requests
}

const ACCOUNT_A: AccountData = {
  options: optionsResponse([P3, P4, P1, P2]),
  stocks: stocksResponse([IXQZ_STOCK]),
  summary: () => jsonResponse(SUMMARY_A),
}

const byTestId = (id: string) => screen.getByTestId(id)

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

function unrealizedTexts(row: HTMLElement) {
  const text = (id: string) => within(row).queryByTestId(id)?.textContent ?? null
  return {
    marketValue: text('position-market-value'),
    pnl: text('position-unrealized-pnl'),
    percent: text('position-unrealized-pnl-percent'),
  }
}

const SUMMARY_A_TEXT =
  '期权 市值 +160.00 未实现 +61.75 ｜ 股票 市值 +6,110.00 未实现 +230.00 ｜ 合计 市值 +6,270.00 未实现 +291.75'

describe('FEAT-017 unrealized P&L on the positions page', () => {
  it('shows market value and unrealized P&L with percent on option rows (AC 1)', async () => {
    startFakeServer({ 1: ACCOUNT_A })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    expect(unrealizedTexts(rowOf(P1.contractLabel))).toEqual({
      marketValue: '−125.00',
      pnl: '−6.30',
      percent: '−5.31%',
    })
    expect(unrealizedTexts(rowOf(P2.contractLabel))).toEqual({
      marketValue: '+370.00',
      pnl: '+68.70',
      percent: '+22.80%',
    })
    expect(unrealizedTexts(rowOf(P3.contractLabel))).toEqual({
      marketValue: '−85.00',
      pnl: '−0.65',
      percent: '−0.77%',
    })
    // 正绿负红 (project colors), the percent in the same color.
    const p2Pnl = within(rowOf(P2.contractLabel)).getByTestId('position-unrealized-pnl')
    expect(p2Pnl).toHaveStyle({ color: '#389e0d' })
    expect(p2Pnl).toHaveAttribute('data-status', 'OK')
    expect(
      within(rowOf(P1.contractLabel)).getByTestId('position-unrealized-pnl-percent'),
    ).toHaveStyle({ color: '#cf1322' })

    // Columns right after Mark.
    const headers = [...document.querySelectorAll('th')].map((th) => th.textContent)
    const mark = headers.indexOf('Mark')
    expect(mark).toBeGreaterThan(-1)
    expect(headers.slice(mark, mark + 3)).toEqual(['Mark', '市值', '未实现盈亏'])
  })

  it('shows the stock row values right after 现价 (AC 2)', async () => {
    startFakeServer({ 1: ACCOUNT_A })
    renderWithProviders(<PositionsPage />)
    const row = await screen.findByTestId('stock-position-row')
    // Every market value has an explicit sign (user decision).
    expect(within(row).getByTestId('stock-position-market-value')).toHaveTextContent(/^\+6,110\.00$/)
    expect(within(row).getByTestId('stock-position-unrealized-pnl')).toHaveTextContent(/^\+230\.00$/)
    expect(within(row).getByTestId('stock-position-unrealized-pnl-percent')).toHaveTextContent(
      /^\+3\.91%$/,
    )
    const table = row.closest('table')
    if (table === null) throw new Error('stock table missing')
    const headers = [...table.querySelectorAll('th')].map((th) => th.textContent)
    const price = headers.indexOf('现价')
    expect(headers.slice(price, price + 3)).toEqual(['现价', '市值', '未实现盈亏'])
  })

  it('shows 无报价 in both cells and the account totals with the excluded note (AC 3)', async () => {
    startFakeServer({ 1: ACCOUNT_A })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const p4 = rowOf(P4.contractLabel)
    expect(unrealizedTexts(p4)).toEqual({ marketValue: '无报价', pnl: '无报价', percent: null })
    expect(within(p4).getByTestId('position-market-value')).toHaveAttribute(
      'data-status',
      'NO_QUOTE',
    )

    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      SUMMARY_A_TEXT,
    )
    expect(byTestId('unrealized-summary-option-market-value')).toHaveTextContent(/^\+160\.00$/)
    expect(byTestId('unrealized-summary-option-unrealized-pnl')).toHaveTextContent(/^\+61\.75$/)
    expect(byTestId('unrealized-summary-total-unrealized-pnl')).toHaveStyle({ color: '#389e0d' })
    expect(byTestId('unrealized-summary-excluded')).toHaveTextContent('1 个持仓无报价，未计入')
    expect(byTestId('unrealized-summary-note')).toHaveTextContent(
      '未实现盈亏已含开仓手续费，未扣平仓手续费；按 mark 计算，仅供参考',
    )
  })

  it('switches to account B: its rows and totals, no excluded note (AC 4)', async () => {
    const requests = startFakeServer({
      1: ACCOUNT_A,
      2: {
        options: optionsResponse([P5]),
        stocks: stocksResponse([]),
        summary: () => jsonResponse(SUMMARY_B),
      },
    })
    renderWithProviders(<App />, { route: '/positions' })
    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      SUMMARY_A_TEXT,
    )

    await userEvent.click(byTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))

    await waitFor(() => {
      expect(byTestId('unrealized-summary-totals')).toHaveTextContent(
        '期权 市值 −125.00 未实现 −5.00 ｜ 股票 市值 0.00 未实现 0.00 ｜ 合计 市值 −125.00 未实现 −5.00',
      )
    })
    expect(unrealizedTexts(rowOf(P5.contractLabel))).toEqual({
      marketValue: '−125.00',
      pnl: '−5.00',
      percent: '−4.17%',
    })
    expect(screen.queryByTestId('unrealized-summary-excluded')).toBeNull()
    expect(
      requests.some((r) => r.url === '/api/positions/unrealized-summary' && r.account === '2'),
    ).toBe(true)
  })

  it('reloads the totals whenever the positions reload (refresh, override, trades — AC 5 / 9)', async () => {
    let summary = SUMMARY_A
    const requests = startFakeServer({
      1: { ...ACCOUNT_A, summary: () => jsonResponse(summary) },
    })
    const { queryClient } = renderWithProviders(<PositionsPage />)
    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      SUMMARY_A_TEXT,
    )
    const summaryGets = () =>
      requests.filter((r) => r.url === '/api/positions/unrealized-summary').length
    const before = summaryGets()

    // 立即刷新 / manual Mark (FEAT-016 refreshQuotes): P1 mark 1.40 → option total +46.75.
    summary = {
      ...SUMMARY_A,
      option: group('145.00', '46.75', { includedCount: 3, noQuoteCount: 1 }),
      total: group('6255.00', '276.75', { includedCount: 4, noQuoteCount: 1 }),
    }
    await refreshQuotes(queryClient)
    await waitFor(() => {
      expect(byTestId('unrealized-summary-option-unrealized-pnl')).toHaveTextContent('+46.75')
    })
    expect(summaryGets()).toBe(before + 1)

    // Any write (平仓 etc., refreshAfterChange): STC P2 (AC 9).
    summary = {
      ...SUMMARY_A,
      option: group('-210.00', '-6.95', { includedCount: 2, noQuoteCount: 1 }),
      total: group('5900.00', '223.05', { includedCount: 3, noQuoteCount: 1 }),
    }
    await refreshAfterChange(queryClient)
    await waitFor(() => {
      expect(byTestId('unrealized-summary-total-market-value')).toHaveTextContent(/^\+5,900\.00$/)
    })
    expect(byTestId('unrealized-summary-option-market-value')).toHaveTextContent(/^−210\.00$/)
    expect(byTestId('unrealized-summary-option-unrealized-pnl')).toHaveTextContent(/^−6\.95$/)
    expect(byTestId('unrealized-summary-option-unrealized-pnl')).toHaveStyle({ color: '#cf1322' })
    expect(byTestId('unrealized-summary-total-unrealized-pnl')).toHaveTextContent(/^\+223\.05$/)
  })

  it('shows 已到期，待处理 for an expired row and the backend sentence (AC 8)', async () => {
    startFakeServer({
      1: {
        ...ACCOUNT_A,
        options: optionsResponse([P_EXPIRED, P3, P4, P1, P2]),
        summary: () =>
          jsonResponse({
            ...SUMMARY_A,
            option: group('160.00', '61.75', {
              includedCount: 3,
              noQuoteCount: 1,
              expiredPendingCount: 1,
            }),
            total: group('6270.00', '291.75', {
              includedCount: 4,
              noQuoteCount: 1,
              expiredPendingCount: 1,
            }),
            excludedLabel: '1 个持仓无报价、1 个持仓已到期待处理，未计入',
          }),
      },
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    expect(unrealizedTexts(rowOf(P_EXPIRED.contractLabel))).toEqual({
      marketValue: '已到期，待处理',
      pnl: '已到期，待处理',
      percent: null,
    })
    expect(await screen.findByTestId('unrealized-summary-excluded')).toHaveTextContent(
      '1 个持仓无报价、1 个持仓已到期待处理，未计入',
    )
    expect(byTestId('unrealized-summary-totals')).toHaveTextContent(SUMMARY_A_TEXT)
  })

  it('keeps the totals for the whole account while the 到期 filter is active (Q158)', async () => {
    const requests = startFakeServer({
      1: { ...ACCOUNT_A, options: optionsResponse([P_EXPIRED, P3, P4, P1, P2]) },
    })
    renderWithProviders(<PositionsPage />, { route: '/positions?expiry=expired' })
    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      SUMMARY_A_TEXT,
    )
    expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    // The summary request takes no filter parameter.
    expect(
      requests.filter((r) => r.url.startsWith('/api/positions/unrealized-summary')).map((r) => r.url),
    ).toEqual(['/api/positions/unrealized-summary'])
  })

  it('shows all-zero totals and no note for an account without positions (AC 10)', async () => {
    startFakeServer({
      1: {
        options: optionsResponse([]),
        stocks: stocksResponse([]),
        summary: () => jsonResponse(SUMMARY_EMPTY),
      },
    })
    renderWithProviders(<PositionsPage />)
    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      '期权 市值 0.00 未实现 0.00 ｜ 股票 市值 0.00 未实现 0.00 ｜ 合计 市值 0.00 未实现 0.00',
    )
    expect(screen.queryByTestId('unrealized-summary-excluded')).toBeNull()
    expect(byTestId('positions-empty')).toBeInTheDocument()
  })

  it('shows — for a null percent and 无报价 on a stock without price (edge cases)', async () => {
    startFakeServer({
      1: {
        ...ACCOUNT_A,
        options: optionsResponse([P_ZERO]),
        stocks: stocksResponse([NO_PRICE_STOCK]),
      },
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    expect(unrealizedTexts(rowOf(P_ZERO.contractLabel))).toEqual({
      marketValue: '−10.00',
      pnl: '−10.00',
      percent: '—',
    })
    const stock = await screen.findByTestId('stock-position-row')
    expect(within(stock).getByTestId('stock-position-market-value')).toHaveTextContent('无报价')
    expect(within(stock).getByTestId('stock-position-unrealized-pnl')).toHaveTextContent('无报价')
    expect(within(stock).queryByTestId('stock-position-unrealized-pnl-percent')).toBeNull()
  })

  it('a failing summary does not hide the positions and can be retried', async () => {
    let fail = true
    startFakeServer({
      1: {
        ...ACCOUNT_A,
        summary: () =>
          fail
            ? jsonResponse(
                {
                  code: 'INTERNAL_ERROR',
                  message: '服务器内部错误',
                  status: 500,
                  details: [],
                  path: '/api/positions/unrealized-summary',
                },
                500,
              )
            : jsonResponse(SUMMARY_A),
      },
    })
    renderWithProviders(<PositionsPage />)
    expect(await screen.findByTestId('unrealized-summary-error')).toHaveTextContent('服务器内部错误')
    expect(screen.getAllByTestId('position-row')).toHaveLength(4)

    fail = false
    await userEvent.click(byTestId('unrealized-summary-retry'))
    expect(await screen.findByTestId('unrealized-summary-totals')).toHaveTextContent(
      SUMMARY_A_TEXT,
    )
  })
})
