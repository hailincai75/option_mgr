import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { ACCOUNT_STORAGE_KEY } from '../account/accountStorage'
import type {
  Account,
  AdhocClosureAlertsResponse,
  ClosedTradePage,
  GreeksSummaryGroup,
  GreeksSummaryResponse,
  OptionPosition,
  OptionPositionsResponse,
  StockPosition,
  StockPositionsResponse,
  UnderlyingSummaryGroup,
  UnderlyingSummaryResponse,
  UnrealizedSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'

// ---------------------------------------------------------------------------
// FEAT-047 scene G (fictional accounts 甲账户 / 乙账户, fictional tickers ZZA / ZZB), served per
// `X-Account-Id` (1, 2 or ALL) exactly like the contract examples in docs/api/FEAT-047.md.
// ---------------------------------------------------------------------------

const JIA: Account = { id: 1, name: '甲账户', brokerNote: null, createdAt: '2026-11-01T14:00:00Z' }
const YI: Account = { id: 2, name: '乙账户', brokerNote: null, createdAt: '2026-11-02T14:00:00Z' }

type Scope = '1' | '2' | 'ALL'

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'ZZA',
    expirationDate: '2026-12-18',
    strike: '55',
    optionType: 'CALL',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '85.35',
    averageNetPerContract: '85.35',
    frozenCash: '0.00',
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

const P1 = position({
  accountId: 1,
  accountName: '甲账户',
  marketValue: '-60.00',
  unrealizedPnl: '25.35',
  unrealizedPnlPercent: '29.70',
})
const P2 = position({
  accountId: 1,
  accountName: '甲账户',
  symbol: 'ZZB',
  strike: '38',
  optionType: 'PUT',
  openingNetTotal: '120.00',
  averageNetPerContract: '120.00',
  frozenCash: '3800.00',
  marketValue: '-110.00',
  unrealizedPnl: '10.00',
  unrealizedPnlPercent: '8.33',
})
const P3 = position({
  accountId: 2,
  accountName: '乙账户',
  strike: '45',
  optionType: 'PUT',
  contracts: 2,
  openingNetTotal: '230.00',
  averageNetPerContract: '115.00',
  frozenCash: '9000.00',
  marketValue: '-220.00',
  unrealizedPnl: '10.00',
  unrealizedPnlPercent: '4.35',
})
const P4 = position({
  accountId: 2,
  accountName: '乙账户',
  symbol: 'ZZB',
  expirationDate: '2026-11-20',
  strike: '36',
  optionType: 'PUT',
  openingNetTotal: '50.00',
  averageNetPerContract: '50.00',
  frozenCash: '3600.00',
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

const S1: StockPosition = {
  accountId: 1,
  accountName: '甲账户',
  symbol: 'ZZA',
  shares: 100,
  coveredShares: 100,
  availableShares: 0,
  totalCost: '4800.00',
  averageCost: '48.0000',
  lotCount: 1,
  lots: [],
  marketValue: '5000.00',
  unrealizedPnl: '200.00',
  unrealizedPnlPercent: '4.17',
  unrealizedStatus: 'OK',
  unrealizedLabel: null,
}

/** API order: expiration → symbol → strike → call/put → account. */
const OPTIONS_ALL = [P4, P2, P3, P1]

function realized(count: number, total: string): UnderlyingSummaryGroup['realized'] {
  return {
    closedCount: count,
    realizedPnl: total,
    option: { closedCount: count, realizedPnl: total },
    stock: { closedCount: 0, realizedPnl: '0.00' },
  }
}

function summaryGroup(partial: Partial<UnderlyingSummaryGroup>): UnderlyingSummaryGroup {
  return {
    accountId: 1,
    accountName: '甲账户',
    symbol: 'ZZA',
    realized: realized(0, '0.00'),
    marketValue: null,
    unrealizedPnl: null,
    totalPnl: '0.00',
    netPremium: null,
    optionPositionCount: 1,
    hasStockPosition: false,
    includedCount: 1,
    noQuoteCount: 0,
    expiredPendingCount: 0,
    excludedLabel: null,
    ...partial,
  }
}

const SUMMARY_GROUPS: UnderlyingSummaryGroup[] = [
  summaryGroup({
    realized: realized(1, '39.70'),
    marketValue: '4940.00',
    unrealizedPnl: '225.35',
    totalPnl: '265.05',
    netPremium: '85.35',
    hasStockPosition: true,
    includedCount: 2,
  }),
  summaryGroup({
    accountId: 2,
    accountName: '乙账户',
    realized: realized(1, '80.00'),
    marketValue: '-220.00',
    unrealizedPnl: '10.00',
    totalPnl: '90.00',
    netPremium: '230.00',
  }),
  summaryGroup({
    symbol: 'ZZB',
    marketValue: '-110.00',
    unrealizedPnl: '10.00',
    totalPnl: '10.00',
    netPremium: '120.00',
  }),
  summaryGroup({
    accountId: 2,
    accountName: '乙账户',
    symbol: 'ZZB',
    netPremium: '50.00',
    includedCount: 0,
    expiredPendingCount: 1,
    excludedLabel: '1 个持仓已到期待处理，未计入',
  }),
]

function greeksGroup(partial: Partial<GreeksSummaryGroup>): GreeksSummaryGroup {
  return {
    symbol: 'ZZA',
    optionDelta: '10.00',
    stockDelta: '100.00',
    netDelta: '110.00',
    gamma: '-10.00',
    theta: '8.00',
    vega: '-16.00',
    underlyingPrice: '50.00',
    underlyingPriceManual: false,
    underlyingPriceLabel: null,
    dollarExposure: '5500.00',
    exposureStatus: 'OK',
    exposureLabel: null,
    manualPriceNote: null,
    optionRowCount: 2,
    includedCount: 2,
    noQuoteCount: 0,
    expiredPendingCount: 0,
    deltaMissingCount: 0,
    gammaMissingCount: 0,
    thetaMissingCount: 0,
    vegaMissingCount: 0,
    excludedLabel: null,
    missingLabel: null,
    ...partial,
  }
}

const GREEKS_ALL: GreeksSummaryResponse = {
  asOfDate: '2026-11-23',
  groups: [
    greeksGroup({}),
    greeksGroup({
      symbol: 'ZZB',
      optionDelta: '30.00',
      stockDelta: '0.00',
      netDelta: '30.00',
      gamma: '-5.00',
      theta: '4.00',
      vega: '-4.50',
      underlyingPrice: '40.00',
      dollarExposure: '1200.00',
      includedCount: 1,
      expiredPendingCount: 1,
      excludedLabel: '1 个持仓已到期待处理，未计入',
    }),
  ],
  portfolio: {
    dollarExposure: '6700.00',
    theta: '12.00',
    vega: '-20.50',
    noQuoteCount: 0,
    expiredPendingCount: 1,
    noUnderlyingPriceCount: 0,
    deltaMissingCount: 0,
    thetaMissingCount: 0,
    vegaMissingCount: 0,
    excludedLabel: '1 个持仓已到期待处理，未计入',
    noUnderlyingPriceLabel: null,
    missingLabel: null,
  },
}

const GREEKS_JIA: GreeksSummaryResponse = {
  asOfDate: '2026-11-23',
  groups: [
    greeksGroup({
      optionDelta: '-20.00',
      netDelta: '80.00',
      gamma: '-4.00',
      theta: '3.00',
      vega: '-6.00',
      dollarExposure: '4000.00',
      optionRowCount: 1,
      includedCount: 1,
    }),
    greeksGroup({
      symbol: 'ZZB',
      optionDelta: '30.00',
      stockDelta: '0.00',
      netDelta: '30.00',
      gamma: '-5.00',
      theta: '4.00',
      vega: '-4.50',
      underlyingPrice: '40.00',
      dollarExposure: '1200.00',
      optionRowCount: 1,
      includedCount: 1,
    }),
  ],
  portfolio: {
    ...GREEKS_ALL.portfolio,
    dollarExposure: '5200.00',
    theta: '7.00',
    vega: '-10.50',
    expiredPendingCount: 0,
    excludedLabel: null,
  },
}

const UNREALIZED_ALL: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: {
    marketValue: '-390.00',
    unrealizedPnl: '45.35',
    includedCount: 3,
    noQuoteCount: 0,
    expiredPendingCount: 1,
  },
  stock: {
    marketValue: '5000.00',
    unrealizedPnl: '200.00',
    includedCount: 1,
    noQuoteCount: 0,
    expiredPendingCount: 0,
  },
  total: {
    marketValue: '4610.00',
    unrealizedPnl: '245.35',
    includedCount: 4,
    noQuoteCount: 0,
    expiredPendingCount: 1,
  },
  excludedLabel: '1 个持仓已到期待处理，未计入',
}

interface Recorded {
  method: string
  path: string
  search: string
  account: string | undefined
}

function inScope<T extends { accountId?: number }>(rows: readonly T[], scope: Scope): T[] {
  return scope === 'ALL' ? [...rows] : rows.filter((row) => String(row.accountId) === scope)
}

function startServer({ empty = false }: { empty?: boolean } = {}) {
  const requests: Recorded[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    const { pathname: path, search } = new URL(url, 'http://localhost')
    requests.push({ method, path, search, account })
    const scope = (account ?? '1') as Scope

    switch (path) {
      case '/api/accounts':
        return Promise.resolve(jsonResponse([JIA, YI]))
      case '/api/health':
        return Promise.resolve(
          jsonResponse({ status: 'UP', version: 'dev', time: '2026-11-23T15:00:00Z' }),
        )
      case '/api/option-positions': {
        const rows = empty ? [] : inScope(OPTIONS_ALL, scope)
        const body: OptionPositionsResponse = {
          asOfDate: '2026-11-23',
          positionCount: rows.length,
          frozenTotal: '0.00',
          positions: rows,
        }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/stock-positions': {
        const rows = empty ? [] : inScope([S1], scope)
        const body: StockPositionsResponse = { positionCount: rows.length, positions: rows }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/positions/underlying-summary': {
        const body: UnderlyingSummaryResponse = {
          asOfDate: '2026-11-23',
          groups: empty ? [] : inScope(SUMMARY_GROUPS, scope),
        }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/positions/greeks-summary':
        return Promise.resolve(jsonResponse(scope === 'ALL' ? GREEKS_ALL : GREEKS_JIA))
      case '/api/positions/unrealized-summary':
        return Promise.resolve(jsonResponse(UNREALIZED_ALL))
      case '/api/closed-trades': {
        const body: ClosedTradePage = {
          items: [],
          page: 1,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          filteredCount: 0,
          filteredRealizedPnlTotal: '0.00',
        }
        return Promise.resolve(jsonResponse(body))
      }
      case '/api/adhoc-closure-alerts': {
        const body: AdhocClosureAlertsResponse = { alerts: [] }
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

function rememberAll() {
  window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'ALL')
}

async function cards(count: number): Promise<HTMLElement[]> {
  await waitFor(() => {
    expect(screen.getAllByTestId('underlying-card')).toHaveLength(count)
  })
  // Summaries arrive with their own request.
  await waitFor(() => {
    expect(screen.queryByTestId('underlying-pnl-loading')).not.toBeInTheDocument()
  })
  return screen.getAllByTestId('underlying-card')
}

describe('FEAT-047 strategy page in 全部账户 mode', () => {
  it('shows one card per account + symbol with the account in the title (acceptance 1, 2, 9)', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/strategies')
    const shown = await cards(4)

    expect(screen.queryByTestId('all-accounts-placeholder')).not.toBeInTheDocument()
    expect(byTestId('underlying-hint')).toHaveTextContent(
      '按标的列出全部账户（每个账户的每个标的一张卡片）正在持有的期权和股票',
    )
    expect(
      shown.map((card) => [
        card.getAttribute('data-account-id'),
        within(card).getByTestId('underlying-card-heading').textContent,
        within(card).getByTestId('underlying-card-summary').textContent,
      ]),
    ).toEqual([
      ['1', '甲账户 · ZZA', '期权 1 个持仓 · 股票 100 股'],
      ['2', '乙账户 · ZZA', '期权 1 个持仓'],
      ['1', '甲账户 · ZZB', '期权 1 个持仓'],
      ['2', '乙账户 · ZZB', '期权 1 个持仓'],
    ])

    const summaryOf = (card: HTMLElement) =>
      [
        'underlying-pnl-realized',
        'underlying-pnl-unrealized',
        'underlying-pnl-total',
        'underlying-pnl-market-value',
        'underlying-pnl-net-premium',
      ].map((id) => within(card).getByTestId(id).textContent)
    expect(shown.map(summaryOf)).toEqual([
      ['+39.70', '+225.35', '+265.05', '+4,940.00', '+85.35'],
      ['+80.00', '+10.00', '+90.00', '−220.00', '+230.00'],
      ['0.00', '+10.00', '+10.00', '−110.00', '+120.00'],
      ['0.00', '—', '0.00', '—', '+50.00'],
    ])
    expect(within(nth(shown, 3)).getByTestId('underlying-pnl-excluded')).toHaveTextContent(
      '1 个持仓已到期待处理，未计入',
    )
    expect(within(nth(shown, 0)).queryByTestId('underlying-pnl-excluded')).not.toBeInTheDocument()

    // 乙 ZZA's 45P row.
    const row = within(nth(shown, 1)).getByTestId('underlying-option-row')
    expect(row).toHaveAttribute('data-contract', 'ZZA 2026-12-18 45 P')
    expect(within(row).getByTestId('underlying-option-cell-contracts')).toHaveTextContent('2')
    expect(within(row).getByTestId('underlying-option-cell-opening-net')).toHaveTextContent(
      '+230.00',
    )
    // The ZZA stock table is only in 甲's card.
    expect(within(nth(shown, 0)).getByTestId('underlying-stock')).toBeInTheDocument()
    expect(within(nth(shown, 1)).queryByTestId('underlying-stock')).not.toBeInTheDocument()

    const summaryRequests = requests.filter((r) => r.path === '/api/positions/underlying-summary')
    expect(summaryRequests.map((r) => r.account)).toEqual(['ALL'])
  })

  it('the 已实现 link switches to the card account, then opens 已平仓 for the symbol (Q235)', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/strategies')
    const shown = await cards(4)
    const link = within(nth(shown, 0)).getByTestId('underlying-pnl-realized')
    expect(link).toHaveAttribute('data-account-id', '1')
    expect(within(nth(shown, 1)).getByTestId('underlying-pnl-realized')).toHaveAttribute(
      'data-account-id',
      '2',
    )

    await userEvent.click(link)
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('甲账户')
    })
    expect(byTestId('probe-location')).toHaveTextContent('/closed?symbol=ZZA')
    await waitFor(() => {
      expect(
        requests.some(
          (r) => r.path === '/api/closed-trades' && r.account === '1' && r.search.includes('ZZA'),
        ),
      ).toBe(true)
    })
    expect(requests.some((r) => r.path === '/api/closed-trades' && r.account === 'ALL')).toBe(false)
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('1')
  })

  it('contract and symbol links stay in 全部账户 (rule 7)', async () => {
    rememberAll()
    startServer()
    renderApp('/strategies')
    const shown = await cards(4)
    const contract = within(nth(shown, 1)).getByTestId('underlying-option-cell-contract')
    expect(contract).toHaveTextContent('ZZA 2026-12-18 45 P')
    expect(contract).not.toHaveAttribute('data-account-id')
    expect(within(nth(shown, 1)).getByTestId('underlying-card-symbol')).toHaveAttribute(
      'href',
      '/stocks?symbol=ZZA',
    )

    await userEvent.click(contract)
    await waitFor(() => {
      expect(byTestId('probe-location')).toHaveTextContent('/trades?')
    })
    expect(byTestId('account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  })

  it('a single account shows only its cards, titled without the account name (acceptance 2)', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, '1')
    startServer()
    renderApp('/strategies')
    const shown = await cards(2)
    expect(shown.map((card) => within(card).getByTestId('underlying-card-heading').textContent)).toEqual(
      ['ZZA', 'ZZB'],
    )
    expect(screen.queryByTestId('underlying-card-account')).not.toBeInTheDocument()
    expect(byTestId('underlying-hint')).toHaveTextContent('按标的列出当前账户正在持有的期权和股票')
    expect(within(nth(shown, 0)).getByTestId('underlying-pnl-realized')).not.toHaveAttribute(
      'data-account-id',
    )
    expect(within(nth(shown, 0)).getByTestId('underlying-pnl-total')).toHaveTextContent('+265.05')
  })

  it('shows 暂无持仓 when no account has positions (acceptance 9)', async () => {
    rememberAll()
    startServer({ empty: true })
    renderApp('/strategies')
    expect(await screen.findByTestId('underlying-empty')).toHaveTextContent('暂无持仓')
    expect(screen.queryByTestId('all-accounts-placeholder')).not.toBeInTheDocument()
  })
})

describe('FEAT-047 positions page Greeks summary in 全部账户 mode', () => {
  it('shows the merged rows without an 账户 column (acceptance 5, 6)', async () => {
    rememberAll()
    const requests = startServer()
    renderApp('/positions')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(4)
    })
    expect(screen.queryByTestId('greeks-summary')).not.toBeInTheDocument()
    await userEvent.click(byTestId('positions-greeks-toggle'))

    await waitFor(() => {
      expect(screen.getAllByTestId('greeks-summary-row')).toHaveLength(2)
    })
    expect(screen.queryByTestId('greeks-summary-all-accounts')).not.toBeInTheDocument()
    expect(screen.queryByText('组合 Greeks 汇总暂只支持单个账户')).not.toBeInTheDocument()
    expect(byTestId('greeks-summary-note')).toHaveTextContent(
      '按标的汇总全部账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考',
    )
    const summary = byTestId('greeks-summary')
    expect(within(summary).queryByRole('columnheader', { name: '账户' })).not.toBeInTheDocument()

    const [zza, zzb] = screen.getAllByTestId('greeks-summary-row')
    if (zza === undefined || zzb === undefined) throw new Error('missing rows')
    expect(within(zza).getByTestId('greeks-summary-option-delta')).toHaveTextContent('+10.00')
    expect(within(zza).getByTestId('greeks-summary-stock-delta')).toHaveTextContent('+100.00')
    expect(within(zza).getByTestId('greeks-summary-net-delta')).toHaveTextContent('+110.00')
    expect(within(zza).getByTestId('greeks-summary-exposure')).toHaveTextContent('+5,500.00')
    expect(within(zzb).getByTestId('greeks-summary-exposure')).toHaveTextContent('+1,200.00')
    expect(zzb).toHaveTextContent('1 个持仓已到期待处理，未计入')
    expect(byTestId('greeks-summary-portfolio-exposure')).toHaveTextContent('+6,700.00')
    expect(byTestId('greeks-summary-portfolio-theta')).toHaveTextContent('+12.00')
    expect(byTestId('greeks-summary-portfolio-vega')).toHaveTextContent('−20.50')

    const greeksRequests = requests.filter((r) => r.path === '/api/positions/greeks-summary')
    expect(greeksRequests.map((r) => r.account)).toEqual(['ALL'])

    // Switched off: no block and no gray single-account note (acceptance 9).
    await userEvent.click(byTestId('positions-greeks-toggle'))
    await waitFor(() => {
      expect(screen.queryByTestId('greeks-summary')).not.toBeInTheDocument()
    })
    expect(screen.queryByTestId('greeks-summary-all-accounts')).not.toBeInTheDocument()
  })

  it('a single account keeps the 当前账户 note and its own numbers (acceptance 6)', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, '1')
    startServer()
    renderApp('/positions')
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(2)
    })
    await userEvent.click(byTestId('positions-greeks-toggle'))
    expect(await screen.findByTestId('greeks-summary-portfolio-exposure')).toHaveTextContent(
      '+5,200.00',
    )
    expect(byTestId('greeks-summary-note')).toHaveTextContent('按标的汇总当前账户的期权与股票')
  })
})
