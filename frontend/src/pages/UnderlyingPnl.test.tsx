import { QueryClient } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import { vi } from 'vitest'
import userEvent from '../test/userEvent'
import type {
  Account,
  OptionPosition,
  OptionPositionsResponse,
  StockPosition,
  StockPositionsResponse,
  UnderlyingSummaryGroup,
  UnderlyingSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { underlyingSummaryQueryKeys } from '../api/underlyingSummary'
import { SHOW_GREEKS_STORAGE_KEY } from '../components/greeks/greeksLabels'
import { refreshQuotes } from '../components/quote/marketDataRefresh'
import { refreshAfterChange } from '../components/trade/tradeFormSupport'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { UNDERLYING_HINT, UnderlyingGroupsPage } from './UnderlyingGroupsPage'

// FEAT-023 scenario (today 2026-11-23; fictional tickers QRTV / IXQZ / ZWLK / QFZ), as the
// contract docs/api/FEAT-023.md returns it, plus the FEAT-017 row fields of the two position APIs.

const GREEN = 'rgb(56, 158, 13)'
const RED = 'rgb(207, 19, 34)'

function option(
  partial: Partial<OptionPosition> &
    Pick<OptionPosition, 'symbol' | 'expirationDate' | 'strike' | 'optionType'>,
): OptionPosition {
  const merged: OptionPosition = {
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
    lotCount: 1,
    lots: [],
    closeAction: partial.direction === 'LONG' ? 'STC' : 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: 1,
    expiryAt: `${partial.expirationDate}T21:00:00Z`,
    expiryAtLocal: `${partial.expirationDate}T16:00:00`,
    assignable: true,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    marketValue: null,
    unrealizedPnl: null,
    unrealizedPnlPercent: null,
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

function stock(partial: Partial<StockPosition> & Pick<StockPosition, 'symbol'>): StockPosition {
  return {
    shares: 100,
    coveredShares: 0,
    availableShares: 100,
    totalCost: '0.00',
    averageCost: '0.0000',
    lotCount: 1,
    lots: [],
    marketValue: null,
    unrealizedPnl: null,
    unrealizedPnlPercent: null,
    unrealizedStatus: 'OK',
    unrealizedLabel: null,
    ...partial,
  }
}

const IXQZ_58P = option({
  symbol: 'IXQZ',
  expirationDate: '2026-11-20',
  strike: '58',
  optionType: 'PUT',
  openingNetTotal: '90.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  unrealizedStatus: 'EXPIRED_PENDING',
  unrealizedLabel: '已到期，待处理',
})
const IXQZ_60P = option({
  symbol: 'IXQZ',
  expirationDate: '2026-12-18',
  strike: '60',
  optionType: 'PUT',
  openingNetTotal: '200.00',
  marketValue: '-250.00',
  unrealizedPnl: '-50.00',
  unrealizedPnlPercent: '-25.00',
})
const QRTV_40P = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '40',
  optionType: 'PUT',
  direction: 'LONG',
  openingNetTotal: '-40.65',
  marketValue: '30.00',
  unrealizedPnl: '-10.65',
  unrealizedPnlPercent: '-26.20',
})
const QRTV_45P = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '45',
  optionType: 'PUT',
  openingNetTotal: '120.35',
  marketValue: '-110.00',
  unrealizedPnl: '10.35',
  unrealizedPnlPercent: '8.60',
})
const QRTV_55C = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '55',
  optionType: 'CALL',
  openingNetTotal: '85.35',
  marketValue: '-60.00',
  unrealizedPnl: '25.35',
  unrealizedPnlPercent: '29.70',
})
const QRTV_B_45P = { ...QRTV_45P, openingNetTotal: '115.00', unrealizedPnl: '5.00' }

const QRTV_STOCK = stock({
  symbol: 'QRTV',
  coveredShares: 100,
  availableShares: 0,
  totalCost: '4800.00',
  averageCost: '48.0000',
  marketValue: '5000.00',
  unrealizedPnl: '200.00',
  unrealizedPnlPercent: '4.17',
})
const ZWLK_STOCK = stock({
  symbol: 'ZWLK',
  shares: 30,
  availableShares: 30,
  totalCost: '900.00',
  averageCost: '30.0000',
  unrealizedStatus: 'NO_QUOTE',
  unrealizedLabel: '无报价',
})

const NONE = { closedCount: 0, realizedPnl: '0.00' }

const IXQZ_GROUP: UnderlyingSummaryGroup = {
  symbol: 'IXQZ',
  realized: {
    closedCount: 1,
    realizedPnl: '80.00',
    option: { closedCount: 1, realizedPnl: '80.00' },
    stock: NONE,
  },
  marketValue: '-250.00',
  unrealizedPnl: '-50.00',
  totalPnl: '30.00',
  netPremium: '290.00',
  optionPositionCount: 2,
  hasStockPosition: false,
  includedCount: 1,
  noQuoteCount: 0,
  expiredPendingCount: 1,
  excludedLabel: '1 个持仓已到期待处理，未计入',
}
const QRTV_GROUP: UnderlyingSummaryGroup = {
  symbol: 'QRTV',
  realized: {
    closedCount: 1,
    realizedPnl: '39.70',
    option: { closedCount: 1, realizedPnl: '39.70' },
    stock: NONE,
  },
  marketValue: '4860.00',
  unrealizedPnl: '225.05',
  totalPnl: '264.75',
  netPremium: '165.05',
  optionPositionCount: 3,
  hasStockPosition: true,
  includedCount: 4,
  noQuoteCount: 0,
  expiredPendingCount: 0,
  excludedLabel: null,
}
const ZWLK_GROUP: UnderlyingSummaryGroup = {
  symbol: 'ZWLK',
  realized: {
    closedCount: 1,
    realizedPnl: '100.00',
    option: NONE,
    stock: { closedCount: 1, realizedPnl: '100.00' },
  },
  marketValue: null,
  unrealizedPnl: null,
  totalPnl: '100.00',
  netPremium: null,
  optionPositionCount: 0,
  hasStockPosition: true,
  includedCount: 0,
  noQuoteCount: 1,
  expiredPendingCount: 0,
  excludedLabel: '1 个持仓无报价，未计入',
}
/** AC 5: after BTC 1 QRTV 45P at 60.65. */
const QRTV_GROUP_AFTER_BTC: UnderlyingSummaryGroup = {
  ...QRTV_GROUP,
  realized: {
    closedCount: 2,
    realizedPnl: '99.40',
    option: { closedCount: 2, realizedPnl: '99.40' },
    stock: NONE,
  },
  marketValue: '4970.00',
  unrealizedPnl: '214.70',
  totalPnl: '314.10',
  netPremium: '44.70',
  optionPositionCount: 2,
  includedCount: 3,
}
/** AC 8: account B. */
const QRTV_B_GROUP: UnderlyingSummaryGroup = {
  symbol: 'QRTV',
  realized: { closedCount: 0, realizedPnl: '0.00', option: NONE, stock: NONE },
  marketValue: '-110.00',
  unrealizedPnl: '5.00',
  totalPnl: '5.00',
  netPremium: '115.00',
  optionPositionCount: 1,
  hasStockPosition: false,
  includedCount: 1,
  noQuoteCount: 0,
  expiredPendingCount: 0,
  excludedLabel: null,
}

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 3, name: '账户C', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

interface AccountData {
  options: OptionPosition[]
  stocks: StockPosition[]
  groups: UnderlyingSummaryGroup[]
}

function sceneA(): Record<number, AccountData> {
  return {
    1: {
      options: [IXQZ_58P, IXQZ_60P, QRTV_40P, QRTV_45P, QRTV_55C],
      stocks: [QRTV_STOCK, ZWLK_STOCK],
      groups: [IXQZ_GROUP, QRTV_GROUP, ZWLK_GROUP],
    },
    2: { options: [QRTV_B_45P], stocks: [], groups: [QRTV_B_GROUP] },
  }
}

type Responder = (url: string) => Promise<Response> | undefined

/** Fake backend keyed by `X-Account-Id`; data objects are mutable so tests can change them. */
function startFakeServer(byAccount: Record<number, AccountData>, override?: Responder) {
  const fetchMock = mockFetch({ accounts: false })
  fetchMock.mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const overridden = override?.(url)
    if (overridden !== undefined) return overridden
    const headers = (init?.headers ?? {}) as Record<string, string>
    const data = byAccount[Number(headers['X-Account-Id'])] ?? { options: [], stocks: [], groups: [] }
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    if (url === '/api/option-positions') {
      const body: OptionPositionsResponse = {
        asOfDate: '2026-11-23',
        positionCount: data.options.length,
        frozenTotal: '0.00',
        positions: data.options,
      }
      return Promise.resolve(jsonResponse(body))
    }
    if (url === '/api/stock-positions') {
      const body: StockPositionsResponse = {
        positionCount: data.stocks.length,
        positions: data.stocks,
      }
      return Promise.resolve(jsonResponse(body))
    }
    if (url === '/api/positions/underlying-summary') {
      const body: UnderlyingSummaryResponse = { asOfDate: '2026-11-23', groups: data.groups }
      return Promise.resolve(jsonResponse(body))
    }
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return fetchMock
}

function cards() {
  return screen.getAllByTestId('underlying-card')
}

function card(symbol: string): HTMLElement {
  const found = screen
    .queryAllByTestId('underlying-card')
    .find((element) => element.getAttribute('data-symbol') === symbol)
  if (!found) throw new Error(`card ${symbol} not found`)
  return found
}

/** [已实现, 期权, 股票, 未实现, 合计, 市值, 净权利金, 提示 | null] as shown. */
function summaryOf(element: HTMLElement) {
  const text = (id: string) => within(element).getByTestId(`underlying-pnl-${id}`).textContent
  return [
    text('realized'),
    text('realized-option'),
    text('realized-stock'),
    text('unrealized'),
    text('total'),
    text('market-value'),
    text('net-premium'),
    within(element).queryByTestId('underlying-pnl-excluded')?.textContent ?? null,
  ]
}

async function waitForSummaries(count: number) {
  await waitFor(() => {
    expect(screen.getAllByTestId('underlying-pnl-total')).toHaveLength(count)
  })
}

describe('UnderlyingGroupsPage card summaries (FEAT-023)', () => {
  it('shows each card summary from the backend, `—` for nulls and 0-record parts (AC 1)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)

    expect(cards().map((element) => element.getAttribute('data-symbol'))).toEqual([
      'IXQZ',
      'QRTV',
      'ZWLK',
    ])
    expect(summaryOf(card('IXQZ'))).toEqual([
      '+80.00',
      '+80.00',
      '—',
      '−50.00',
      '+30.00',
      '−250.00',
      '+290.00',
      '1 个持仓已到期待处理，未计入',
    ])
    expect(summaryOf(card('QRTV'))).toEqual([
      '+39.70',
      '+39.70',
      '—',
      '+225.05',
      '+264.75',
      '+4,860.00',
      '+165.05',
      null,
    ])
    expect(summaryOf(card('ZWLK'))).toEqual([
      '+100.00',
      '—',
      '+100.00',
      '—',
      '+100.00',
      '—',
      '—',
      '1 个持仓无报价，未计入',
    ])
    expect(within(card('QRTV')).getByTestId('underlying-pnl')).toHaveTextContent(
      '已实现 +39.70 期权 +39.70 · 股票 —未实现 +225.05合计 +264.75市值 +4,860.00净权利金 +165.05',
    )
  })

  it('colors the three P&L figures by sign but not market value or net premium', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)

    const ixqz = within(card('IXQZ'))
    expect(ixqz.getByTestId('underlying-pnl-realized').style.color).toBe(GREEN)
    expect(ixqz.getByTestId('underlying-pnl-realized-option').style.color).toBe(GREEN)
    expect(ixqz.getByTestId('underlying-pnl-unrealized').style.color).toBe(RED)
    expect(ixqz.getByTestId('underlying-pnl-total').style.color).toBe(GREEN)
    expect(ixqz.getByTestId('underlying-pnl-market-value').style.color).toBe('')
    expect(ixqz.getByTestId('underlying-pnl-net-premium').style.color).toBe('')
    // `—` is gray (antd secondary text).
    const zwlk = within(card('ZWLK'))
    expect(zwlk.getByTestId('underlying-pnl-unrealized').className).toMatch(/secondary/)
    expect(zwlk.getByTestId('underlying-pnl-realized-option').className).toMatch(/secondary/)
  })

  it('adds 市值 / 未实现盈亏 to option and stock rows like the positions page (AC 2)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)

    const headers = (section: string, symbol: string) =>
      within(within(card(symbol)).getByTestId(section))
        .getAllByRole('columnheader')
        .map((header) => header.textContent)
    expect(headers('underlying-options', 'QRTV')).toEqual([
      '合约',
      '方向',
      '张数',
      '开仓净额',
      '市值',
      '未实现盈亏',
      '到期日',
      'DTE',
    ])
    expect(headers('underlying-stock', 'QRTV')).toEqual([
      '持股',
      '被覆盖',
      '可用',
      '总成本',
      '每股平均成本',
      '市值',
      '未实现盈亏',
    ])

    const rowValues = (row: HTMLElement, prefix: string) => {
      const percent = within(row).queryByTestId(`${prefix}-unrealized-pnl-percent`)
      return [
        within(row).getByTestId(`${prefix}-market-value`).textContent,
        within(row).getByTestId(`${prefix}-unrealized-pnl`).textContent,
        percent?.textContent ?? null,
      ]
    }
    const qrtvRows = within(card('QRTV')).getAllByTestId('underlying-option-row')
    expect(qrtvRows.map((row) => rowValues(row, 'underlying-option-cell'))).toEqual([
      ['+30.00', '−10.65', '−26.20%'],
      ['−110.00', '+10.35', '+8.60%'],
      ['−60.00', '+25.35', '+29.70%'],
    ])
    expect(rowValues(within(card('QRTV')).getByTestId('underlying-stock-row'), 'underlying-stock-cell')).toEqual(
      ['+5,000.00', '+200.00', '+4.17%'],
    )
    const [expired] = within(card('IXQZ')).getAllByTestId('underlying-option-row')
    if (expired === undefined) throw new Error('no row')
    expect(rowValues(expired, 'underlying-option-cell')).toEqual([
      '已到期，待处理',
      '已到期，待处理',
      null,
    ])
    expect(within(expired).getByTestId('underlying-option-cell-market-value')).toHaveAttribute(
      'data-status',
      'EXPIRED_PENDING',
    )
    expect(
      rowValues(within(card('ZWLK')).getByTestId('underlying-stock-row'), 'underlying-stock-cell'),
    ).toEqual(['无报价', '无报价', null])
  })

  it('links 已实现 to the 已平仓 page filtered by symbol only (AC 4)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)
    expect(within(card('QRTV')).getByTestId('underlying-pnl-realized')).toHaveAttribute(
      'href',
      '/closed?symbol=QRTV',
    )
    expect(within(card('ZWLK')).getByTestId('underlying-pnl-realized')).toHaveAttribute(
      'href',
      '/closed?symbol=ZWLK',
    )
  })

  it('updates without a page refresh after a write (refreshAfterChange, AC 5)', async () => {
    const data = sceneA()
    startFakeServer(data)
    const { queryClient } = renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)
    const account = data[1]
    if (account === undefined) throw new Error('no account')

    account.options = [IXQZ_58P, IXQZ_60P, QRTV_40P, QRTV_55C]
    account.groups = [IXQZ_GROUP, QRTV_GROUP_AFTER_BTC, ZWLK_GROUP]
    await act(async () => {
      await refreshAfterChange(queryClient)
    })

    await waitFor(() => {
      expect(within(card('QRTV')).getByTestId('underlying-pnl-realized')).toHaveTextContent(
        '+99.40',
      )
    })
    expect(summaryOf(card('QRTV'))).toEqual([
      '+99.40',
      '+99.40',
      '—',
      '+214.70',
      '+314.10',
      '+4,970.00',
      '+44.70',
      null,
    ])
    expect(within(card('QRTV')).getAllByTestId('underlying-option-row')).toHaveLength(2)
  })

  it('updates after a quote refresh or manual price change (refreshQuotes, AC 7)', async () => {
    const data = sceneA()
    startFakeServer(data)
    const { queryClient } = renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)
    const account = data[1]
    if (account === undefined) throw new Error('no account')

    account.stocks = [
      QRTV_STOCK,
      {
        ...ZWLK_STOCK,
        marketValue: '960.00',
        unrealizedPnl: '60.00',
        unrealizedPnlPercent: '6.67',
        unrealizedStatus: 'OK',
        unrealizedLabel: null,
      },
    ]
    account.groups = [
      IXQZ_GROUP,
      QRTV_GROUP,
      {
        ...ZWLK_GROUP,
        marketValue: '960.00',
        unrealizedPnl: '60.00',
        totalPnl: '160.00',
        includedCount: 1,
        noQuoteCount: 0,
        excludedLabel: null,
      },
    ]
    await act(async () => {
      await refreshQuotes(queryClient)
    })

    await waitFor(() => {
      expect(within(card('ZWLK')).getByTestId('underlying-pnl-total')).toHaveTextContent('+160.00')
    })
    expect(summaryOf(card('ZWLK'))).toEqual([
      '+100.00',
      '—',
      '+100.00',
      '+60.00',
      '+160.00',
      '+960.00',
      '—',
      null,
    ])
    expect(
      within(card('ZWLK')).getByTestId('underlying-stock-cell-unrealized-pnl-percent'),
    ).toHaveTextContent('+6.67%')
  })

  it('invalidates the summary wherever positions and the P&L summary are invalidated', async () => {
    const queryClient = new QueryClient()
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await refreshAfterChange(queryClient)
    expect(spy).toHaveBeenCalledWith({ queryKey: underlyingSummaryQueryKeys.all })
    spy.mockClear()
    await refreshQuotes(queryClient)
    expect(spy).toHaveBeenCalledWith({ queryKey: underlyingSummaryQueryKeys.all })
  })

  it('shows only the current account, 0.00 uncolored with `— · —` (AC 8)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<App />, { route: '/strategies' })
    await waitForSummaries(3)

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))
    await waitFor(() => {
      expect(cards()).toHaveLength(1)
    })
    await waitForSummaries(1)
    expect(summaryOf(card('QRTV'))).toEqual([
      '0.00',
      '—',
      '—',
      '+5.00',
      '+5.00',
      '−110.00',
      '+115.00',
      null,
    ])
    expect(within(card('QRTV')).getByTestId('underlying-pnl-realized').style.color).toBe('')
    expect(screen.queryByText('+39.70')).toBeNull()

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-3'))
    expect(await screen.findByTestId('underlying-empty')).toHaveTextContent('暂无持仓')
  })

  it('has no Greeks, max P&L or buttons and shows the new hint even with 显示 Greeks on (AC 9)', async () => {
    window.localStorage.setItem(SHOW_GREEKS_STORAGE_KEY, 'true')
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitForSummaries(3)

    expect(screen.getByTestId('underlying-hint').textContent).toBe(UNDERLYING_HINT)
    expect(UNDERLYING_HINT).toBe(
      '按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页。已实现盈亏为该标的全部历史（含手续费）；市值和未实现盈亏按 mark / 现价计算，未扣平仓手续费，仅供参考',
    )
    const page = screen.getByTestId('underlying-page')
    expect(within(page).queryAllByRole('button')).toEqual([])
    for (const text of ['Delta', 'Gamma', 'Theta', 'Vega', 'IV', '最大盈利', '最大亏损', '盈亏平衡点']) {
      expect(within(page).queryByText(new RegExp(text))).toBeNull()
    }
    window.localStorage.removeItem(SHOW_GREEKS_STORAGE_KEY)
  })

  it('treats a card as loading until its summary arrives', async () => {
    let release: (() => void) | undefined
    const data = sceneA()
    startFakeServer(data, (url) => {
      if (url !== '/api/positions/underlying-summary') return undefined
      return new Promise<Response>((resolve) => {
        release = () => {
          resolve(jsonResponse({ asOfDate: '2026-11-23', groups: [IXQZ_GROUP, QRTV_GROUP] }))
        }
      })
    })
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    // Positions arrived first: every card shows its summary as loading.
    expect(screen.getAllByTestId('underlying-pnl-loading')).toHaveLength(3)
    expect(within(card('QRTV')).getByTestId('underlying-options')).toBeInTheDocument()

    await act(async () => {
      release?.()
      await Promise.resolve()
    })
    await waitForSummaries(2)
    // ZWLK has a card but (briefly) no group: still loading, not an error.
    expect(within(card('ZWLK')).getByTestId('underlying-pnl-loading')).toHaveTextContent(
      '盈亏加载中…',
    )
  })

  it('keeps the cards when the summary fails and retries it', async () => {
    let fail = true
    startFakeServer(sceneA(), (url) => {
      if (fail && url === '/api/positions/underlying-summary') {
        return Promise.resolve(
          jsonResponse({ code: 'INTERNAL_ERROR', message: '服务器错误', details: [] }, 500),
        )
      }
      return undefined
    })
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    expect(await screen.findByTestId('underlying-pnl-error')).toHaveTextContent(
      '无法加载按标的盈亏',
    )
    expect(cards()).toHaveLength(3)
    expect(screen.getAllByTestId('underlying-pnl-unavailable')).toHaveLength(3)
    expect(within(card('QRTV')).getAllByTestId('underlying-option-row')).toHaveLength(3)

    fail = false
    await userEvent.click(screen.getByTestId('underlying-pnl-retry'))
    await waitForSummaries(3)
    expect(screen.queryByTestId('underlying-pnl-error')).toBeNull()
  })
})
