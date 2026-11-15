import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  Account,
  OptionPosition,
  OptionPositionsResponse,
  StockPosition,
  StockPositionsResponse,
} from '../api/types'
import { App } from '../App'
import { optionPositionQueryKeys } from '../api/optionPositions'
import { stockPositionQueryKeys } from '../api/stockTrades'
import { SHOW_GREEKS_STORAGE_KEY } from '../components/greeks/greeksLabels'
import { groupByUnderlying, underlyingSummary } from '../components/underlying/groupByUnderlying'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { UnderlyingGroupsPage } from './UnderlyingGroupsPage'

// FEAT-021 scenario (today 2026-11-23; fictional tickers QRTV / IXQZ / ZWLK), as the two
// existing endpoints return it (docs/api/FEAT-021.md "验收场景对照").

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
    allowedCloseContracts: [partial.contracts ?? 1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: partial.contracts ?? 1,
    expiryAt: `${partial.expirationDate}T21:00:00Z`,
    expiryAtLocal: `${partial.expirationDate}T16:00:00`,
    assignable: true,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    marketValue: '-120.00',
    unrealizedPnl: '35.00',
    unrealizedPnlPercent: '22.80',
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
    marketValue: '5000.00',
    unrealizedPnl: '200.00',
    unrealizedPnlPercent: '4.17',
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
})
const IXQZ_60P = option({
  symbol: 'IXQZ',
  expirationDate: '2026-12-18',
  strike: '60',
  optionType: 'PUT',
  openingNetTotal: '200.00',
})
const QRTV_40P = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '40',
  optionType: 'PUT',
  direction: 'LONG',
  openingNetTotal: '-40.65',
})
const QRTV_45P = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '45',
  optionType: 'PUT',
  contracts: 2,
  openingNetTotal: '230.35',
  lotCount: 2,
})
const QRTV_55C = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '55',
  optionType: 'CALL',
  openingNetTotal: '85.35',
})
const QRTV_JAN_45P = option({
  symbol: 'QRTV',
  expirationDate: '2027-01-15',
  strike: '45',
  optionType: 'PUT',
  openingNetTotal: '150.35',
  dte: 53,
})
const QRTV_B_45P = option({
  symbol: 'QRTV',
  expirationDate: '2026-12-18',
  strike: '45',
  optionType: 'PUT',
  openingNetTotal: '115.00',
})

const QRTV_STOCK = stock({
  symbol: 'QRTV',
  coveredShares: 100,
  availableShares: 0,
  totalCost: '4800.00',
  averageCost: '48.0000',
})
const ZWLK_STOCK = stock({
  symbol: 'ZWLK',
  shares: 50,
  coveredShares: 0,
  availableShares: 50,
  totalCost: '1500.00',
  averageCost: '30.0000',
})

/** Backend order: expiration → symbol → strike (number) → call before put. */
const SCENE_A_OPTIONS = [IXQZ_58P, IXQZ_60P, QRTV_40P, QRTV_45P, QRTV_55C, QRTV_JAN_45P]
const SCENE_A_STOCKS = [QRTV_STOCK, ZWLK_STOCK]

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 3, name: '账户C', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

interface AccountData {
  options: OptionPosition[]
  stocks: StockPosition[]
}

function optionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

function stocksResponse(positions: StockPosition[]): StockPositionsResponse {
  return { positionCount: positions.length, positions }
}

/** Fake backend keyed by `X-Account-Id`; data objects are mutable so tests can change them. */
function startFakeServer(byAccount: Record<number, AccountData>) {
  const fetchMock = mockFetch({ accounts: false })
  fetchMock.mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    const data = byAccount[Number(headers['X-Account-Id'])] ?? { options: [], stocks: [] }
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    if (url === '/api/option-positions') {
      return Promise.resolve(jsonResponse(optionsResponse(data.options)))
    }
    if (url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse(stocksResponse(data.stocks)))
    }
    // FEAT-023: card summaries are covered by UnderlyingPnl.test.tsx; none here (cards show loading).
    if (url === '/api/positions/underlying-summary') {
      return Promise.resolve(jsonResponse({ asOfDate: '2026-11-23', groups: [] }))
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

function cardTitle(element: HTMLElement) {
  return [
    within(element).getByTestId('underlying-card-symbol').textContent,
    within(element).getByTestId('underlying-card-summary').textContent,
  ]
}

function optionRows(element: HTMLElement) {
  return within(element)
    .queryAllByTestId('underlying-option-row')
    .map((row) => {
      const text = (id: string) => within(row).getByTestId(`underlying-option-cell-${id}`).textContent
      return [
        text('contract'),
        text('direction'),
        text('contracts'),
        text('opening-net'),
        text('expiration'),
        text('dte'),
      ]
    })
}

function stockRow(element: HTMLElement) {
  const row = within(element).queryByTestId('underlying-stock-row')
  if (row === null) return null
  const text = (id: string) => within(row).getByTestId(`underlying-stock-cell-${id}`).textContent
  return [
    text('shares'),
    text('covered-shares'),
    text('available-shares'),
    text('total-cost'),
    text('average-cost'),
  ]
}

function sceneA(): Record<number, AccountData> {
  return {
    1: { options: [...SCENE_A_OPTIONS], stocks: [...SCENE_A_STOCKS] },
    2: { options: [QRTV_B_45P], stocks: [] },
  }
}

describe('groupByUnderlying', () => {
  it('builds the union of symbols, sorted, keeping the API order of option rows', () => {
    const groups = groupByUnderlying(SCENE_A_OPTIONS, SCENE_A_STOCKS)
    expect(groups.map((group) => group.symbol)).toEqual(['IXQZ', 'QRTV', 'ZWLK'])
    expect(groups.map(underlyingSummary)).toEqual([
      '期权 2 个持仓',
      '期权 4 个持仓 · 股票 100 股',
      '股票 50 股',
    ])
    expect(groups[1]?.options).toEqual([QRTV_40P, QRTV_45P, QRTV_55C, QRTV_JAN_45P])
    expect(groups[1]?.stock).toBe(QRTV_STOCK)
    expect(groups[0]?.stock).toBeNull()
  })

  it('does not re-sort strikes as strings', () => {
    const p45 = option({ symbol: 'QRTV', expirationDate: '2026-12-18', strike: '45', optionType: 'PUT' })
    const p100 = option({ symbol: 'QRTV', expirationDate: '2026-12-18', strike: '100', optionType: 'PUT' })
    const [group] = groupByUnderlying([p45, p100], [])
    expect(group?.options.map((p) => p.strike)).toEqual(['45', '100'])
  })

  it('formats large share counts with thousands separators', () => {
    const [group] = groupByUnderlying([], [stock({ symbol: 'ZWLK', shares: 12000 })])
    if (group === undefined) throw new Error('no group')
    expect(underlyingSummary(group)).toBe('股票 12,000 股')
  })

  it('returns no groups when nothing is held', () => {
    expect(groupByUnderlying([], [])).toEqual([])
  })
})

describe('UnderlyingGroupsPage (FEAT-021)', () => {
  it('shows one card per symbol in alphabetical order with the backend values (AC 1–3)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })

    expect(screen.getByTestId('underlying-title')).toHaveTextContent('按标的')
    expect(screen.getByTestId('underlying-hint')).toHaveTextContent(
      '按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页。已实现盈亏为该标的全部历史（含手续费）；市值和未实现盈亏按 mark / 现价计算，未扣平仓手续费，仅供参考',
    )
    expect(screen.getByTestId('underlying-loading')).toBeInTheDocument()

    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    expect(cards().map(cardTitle)).toEqual([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 4 个持仓 · 股票 100 股'],
      ['ZWLK', '股票 50 股'],
    ])

    expect(optionRows(card('QRTV'))).toEqual([
      ['QRTV 2026-12-18 40 P', '多头', '1', '−40.65', '2026-12-18', '25'],
      ['QRTV 2026-12-18 45 P', '空头', '2', '+230.35', '2026-12-18', '25'],
      ['QRTV 2026-12-18 55 C', '空头', '1', '+85.35', '2026-12-18', '25'],
      ['QRTV 2027-01-15 45 P', '空头', '1', '+150.35', '2027-01-15', '53'],
    ])
    expect(stockRow(card('QRTV'))).toEqual(['100', '100', '0', '4,800.00', '48.0000'])

    expect(optionRows(card('IXQZ'))).toEqual([
      ['IXQZ 2026-11-20 58 P', '空头', '1', '+90.00', '2026-11-20', '已过期 3 天'],
      ['IXQZ 2026-12-18 60 P', '空头', '1', '+200.00', '2026-12-18', '25'],
    ])
    const expired = within(card('IXQZ')).getAllByTestId('underlying-option-cell-dte')[0]
    expect(expired?.className).toMatch(/danger/)
    expect(within(card('IXQZ')).queryByTestId('underlying-stock')).toBeNull()

    expect(within(card('ZWLK')).queryByTestId('underlying-options')).toBeNull()
    expect(stockRow(card('ZWLK'))).toEqual(['50', '0', '50', '1,500.00', '30.0000'])
  })

  it('colors opening net totals by sign', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    const [long, short] = within(card('QRTV')).getAllByTestId('underlying-option-cell-opening-net')
    expect(long?.style.color).not.toBe('')
    expect(short?.style.color).not.toBe('')
    expect(long?.style.color).not.toBe(short?.style.color)
  })

  it('shows 今天到期 for DTE 0 like the positions page', async () => {
    startFakeServer({
      1: {
        options: [
          option({
            symbol: 'IXQZ',
            expirationDate: '2026-11-23',
            strike: '58',
            optionType: 'PUT',
            dte: 0,
            expirationStatus: 'EXPIRES_TODAY',
          }),
        ],
        stocks: [],
      },
    })
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    expect(await screen.findByTestId('underlying-option-cell-dte-today')).toHaveTextContent(
      '今天到期',
    )
    expect(screen.getByTestId('underlying-option-cell-dte')).toHaveTextContent('0 今天到期')
  })

  it('follows writes through the shared query keys without a page refresh (AC 4–7)', async () => {
    const data = sceneA()
    startFakeServer(data)
    const { queryClient } = renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    const account = data[1]
    if (account === undefined) throw new Error('no account')

    // BTC 1 QRTV 45P (FIFO removes the 120.35 lot), STC the 40P, sell all ZWLK.
    account.options = [
      IXQZ_58P,
      IXQZ_60P,
      { ...QRTV_45P, contracts: 1, openingNetTotal: '110.00' },
      QRTV_55C,
      QRTV_JAN_45P,
    ]
    account.stocks = [QRTV_STOCK]
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all })
      await queryClient.invalidateQueries({ queryKey: stockPositionQueryKeys.all })
    })

    await waitFor(() => {
      expect(cards()).toHaveLength(2)
    })
    expect(cardTitle(card('QRTV'))).toEqual(['QRTV', '期权 3 个持仓 · 股票 100 股'])
    expect(optionRows(card('QRTV'))[0]).toEqual([
      'QRTV 2026-12-18 45 P',
      '空头',
      '1',
      '+110.00',
      '2026-12-18',
      '25',
    ])

    // IXQZ 60P assigned: 100 shares appear in the same card.
    account.options = [IXQZ_58P, QRTV_45P, QRTV_55C, QRTV_JAN_45P]
    account.stocks = [
      stock({ symbol: 'IXQZ', totalCost: '5800.00', averageCost: '58.0000' }),
      QRTV_STOCK,
    ]
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all })
      await queryClient.invalidateQueries({ queryKey: stockPositionQueryKeys.all })
    })
    await waitFor(() => {
      expect(cardTitle(card('IXQZ'))).toEqual(['IXQZ', '期权 1 个持仓 · 股票 100 股'])
    })
    expect(stockRow(card('IXQZ'))).toEqual(['100', '0', '100', '5,800.00', '58.0000'])
  })

  it('shows only the current account and 暂无持仓 for an empty account (AC 8)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<App />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))
    await waitFor(() => {
      expect(cards()).toHaveLength(1)
    })
    expect(cardTitle(card('QRTV'))).toEqual(['QRTV', '期权 1 个持仓'])
    expect(optionRows(card('QRTV'))).toEqual([
      ['QRTV 2026-12-18 45 P', '空头', '1', '+115.00', '2026-12-18', '25'],
    ])
    expect(within(card('QRTV')).queryByTestId('underlying-stock')).toBeNull()

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-3'))
    expect(await screen.findByTestId('underlying-empty')).toHaveTextContent('暂无持仓')
    expect(screen.queryByTestId('underlying-card')).toBeNull()
  })

  // FEAT-023 (Q198) added 市值 / 未实现盈亏; Greeks stay hidden (Q202).
  it('is read-only with no Greeks even when 显示 Greeks is on (AC 9)', async () => {
    window.localStorage.setItem(SHOW_GREEKS_STORAGE_KEY, 'true')
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    const page = screen.getByTestId('underlying-page')
    expect(within(page).queryAllByRole('button')).toEqual([])
    for (const text of ['Delta', 'Gamma', 'Theta', 'Vega', 'IV']) {
      expect(within(page).queryByText(text)).toBeNull()
    }
    for (const text of ['平仓', '作废', '被指派', '行权', '卖出']) {
      expect(within(page).queryByText(text)).toBeNull()
    }
    window.localStorage.removeItem(SHOW_GREEKS_STORAGE_KEY)
  })

  it('links a contract to the filtered trade list and a symbol to the filtered stocks page (AC 9)', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
    const contract = within(card('QRTV'))
      .getAllByTestId('underlying-option-cell-contract')
      .find((link) => link.textContent === 'QRTV 2026-12-18 55 C')
    expect(contract).toHaveAttribute(
      'href',
      '/trades?symbol=QRTV&expirationDate=2026-12-18&strike=55&optionType=CALL',
    )
    expect(within(card('ZWLK')).getByTestId('underlying-card-symbol')).toHaveAttribute(
      'href',
      '/stocks?symbol=ZWLK',
    )
  })

  it('navigates from the 策略 menu entry', async () => {
    startFakeServer(sceneA())
    renderWithProviders(<App />, { route: '/' })
    const nav = await screen.findByTestId('nav-strategies')
    expect(nav).toHaveTextContent('策略')
    await userEvent.click(nav)
    expect(await screen.findByTestId('underlying-title')).toHaveTextContent('按标的')
  })

  it('shows the error with a retry that reloads the page data', async () => {
    const fetchMock = startFakeServer(sceneA())
    const ok = fetchMock.getMockImplementation()
    let fail = true
    fetchMock.mockImplementation((input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (fail && url === '/api/stock-positions') {
        return Promise.resolve(jsonResponse({}, 500))
      }
      if (ok === undefined) throw new Error('no implementation')
      return ok(input, init)
    })
    renderWithProviders(<UnderlyingGroupsPage />, { route: '/strategies' })
    expect(await screen.findByTestId('underlying-error')).toHaveTextContent('500')
    expect(screen.queryByTestId('underlying-card')).toBeNull()

    fail = false
    await userEvent.click(screen.getByTestId('underlying-retry'))
    await waitFor(() => {
      expect(cards()).toHaveLength(3)
    })
  })
})
