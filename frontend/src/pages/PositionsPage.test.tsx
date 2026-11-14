import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  OptionPosition,
  OptionPositionLot,
  OptionPositionsResponse,
  OptionTrade,
  OptionTradePage,
  StockPositionsResponse,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// ---------------------------------------------------------------------------
// Fixtures: FEAT-003 baseline (today 2026-11-11), exactly as the backend returns it
// ---------------------------------------------------------------------------

function lot(
  partial: Partial<OptionPositionLot> & Pick<OptionPositionLot, 'tradeId'>,
): OptionPositionLot {
  return {
    action: 'STO',
    tradedAt: '2026-11-03T15:30:00Z',
    tradedAtLocal: '2026-11-03T10:30:00',
    originalContracts: 1,
    remainingContracts: 1,
    originalNetAmount: '0.00',
    remainingNetAmount: '0.00',
    originalFrozen: '0.00',
    remainingFrozen: '0.00',
    note: null,
    ...partial,
  }
}

function position(partial: Partial<OptionPosition> & Pick<OptionPosition, 'lots'>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'AAPL',
    expirationDate: '2026-12-18',
    strike: '200',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '0.00',
    averageNetPerContract: '0.00',
    frozenCash: '0.00',
    dte: 37,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: partial.lots.length,
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
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const AAPL_200_P = position({
  contracts: 2,
  openingNetTotal: '709.35',
  averageNetPerContract: '354.68',
  frozenCash: '40000.00',
  lots: [
    lot({
      tradeId: 1,
      originalNetAmount: '349.35',
      remainingNetAmount: '349.35',
      originalFrozen: '20000.00',
      remainingFrozen: '20000.00',
      note: '第一笔 CSP',
    }),
    lot({
      tradeId: 4,
      tradedAt: '2026-11-04T16:00:00Z',
      tradedAtLocal: '2026-11-04T11:00:00',
      originalNetAmount: '360.00',
      remainingNetAmount: '360.00',
      originalFrozen: '20000.00',
      remainingFrozen: '20000.00',
      note: '加仓',
    }),
  ],
})
const AAPL_210_C = position({
  strike: '210',
  optionType: 'CALL',
  direction: 'LONG',
  openingNetTotal: '-512.65',
  averageNetPerContract: '-512.65',
  lots: [
    lot({ tradeId: 2, action: 'BTO', originalNetAmount: '-512.65', remainingNetAmount: '-512.65' }),
  ],
})
const F_12_5_P = position({
  symbol: 'F',
  strike: '12.5',
  contracts: 2,
  openingNetTotal: '48.70',
  averageNetPerContract: '24.35',
  frozenCash: '2500.00',
  lots: [
    lot({
      tradeId: 3,
      tradedAt: '2026-11-02T20:59:00Z',
      tradedAtLocal: '2026-11-02T15:59:00',
      originalContracts: 2,
      remainingContracts: 2,
      originalNetAmount: '48.70',
      remainingNetAmount: '48.70',
      originalFrozen: '2500.00',
      remainingFrozen: '2500.00',
    }),
  ],
})
const XYZ_200_P = position({
  symbol: 'XYZ',
  multiplier: 10,
  openingNetTotal: '34.35',
  averageNetPerContract: '34.35',
  frozenCash: '2000.00',
  lots: [lot({ tradeId: 6, remainingNetAmount: '34.35', remainingFrozen: '2000.00' })],
})
const MSFT_450_C = position({
  symbol: 'MSFT',
  expirationDate: '2027-01-15',
  strike: '450',
  optionType: 'CALL',
  direction: 'LONG',
  openingNetTotal: '-900.00',
  averageNetPerContract: '-900.00',
  dte: 65,
  lots: [lot({ tradeId: 5, action: 'BTO', remainingNetAmount: '-900.00' })],
})

const BASELINE: OptionPositionsResponse = {
  asOfDate: '2026-11-11',
  positionCount: 5,
  frozenTotal: '44500.00',
  positions: [AAPL_200_P, AAPL_210_C, F_12_5_P, XYZ_200_P, MSFT_450_C],
}

const EMPTY: OptionPositionsResponse = {
  asOfDate: '2026-11-11',
  positionCount: 0,
  frozenTotal: '0.00',
  positions: [],
}

// ---------------------------------------------------------------------------

interface FakeServer {
  positions: () => Response
  trades?: OptionTrade[]
  onPost?: () => Response
  /** FEAT-039: stock positions response; defaults to no stock. */
  stocks?: StockPositionsResponse
}

function startFakeServer(server: FakeServer) {
  const urls: string[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    urls.push(`${method} ${url}`)
    if (method === 'GET' && url === '/api/option-positions')
      return Promise.resolve(server.positions())
    if (method === 'GET' && url.startsWith('/api/option-trades')) {
      const items = server.trades ?? []
      const page: OptionTradePage = {
        items,
        page: 1,
        size: 20,
        totalItems: items.length,
        totalPages: items.length > 0 ? 1 : 0,
      }
      return Promise.resolve(jsonResponse(page))
    }
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(
        jsonResponse({ totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' }),
      )
    }
    if (method === 'GET' && url === '/api/stock-positions') {
      // FEAT-011 stock section (covered by StockPositions.test.tsx).
      return Promise.resolve(jsonResponse(server.stocks ?? { positionCount: 0, positions: [] }))
    }
    if (method === 'POST' && url === '/api/option-trades' && server.onPost) {
      return Promise.resolve(server.onPost())
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return urls
}

const byTestId = (id: string) => screen.getByTestId(id)

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

function cellTexts(row: HTMLElement): string[] {
  const cell = (id: string) => within(row).getByTestId(id).textContent
  return [
    cell('position-cell-contract'),
    cell('position-cell-direction'),
    cell('position-cell-contracts'),
    cell('position-cell-opening-net'),
    cell('position-cell-average-net'),
    cell('position-cell-frozen'),
    cell('position-cell-expiration'),
    cell('position-cell-dte'),
    cell('position-cell-lot-count'),
  ]
}

function lotTexts(lotRow: HTMLElement): string[] {
  const cell = (id: string) => within(lotRow).getByTestId(id).textContent
  return [
    cell('lot-cell-time'),
    cell('lot-cell-action'),
    cell('lot-cell-contracts'),
    cell('lot-cell-net-amount'),
    cell('lot-cell-frozen'),
    cell('lot-cell-note'),
  ]
}

async function expand(contract: string): Promise<HTMLElement[]> {
  await userEvent.click(within(rowOf(contract)).getByTestId('position-expand'))
  // The expanded row is the next sibling <tr> of the position row.
  const expandedRow = rowOf(contract).nextElementSibling
  if (!(expandedRow instanceof HTMLElement)) throw new Error('expanded row missing')
  return within(expandedRow).findAllByTestId('lot-row')
}

// ---------------------------------------------------------------------------

describe('PositionsPage', () => {
  it('shows header values and every position in backend order (AC 1)', async () => {
    startFakeServer({ positions: () => jsonResponse(BASELINE) })
    renderWithProviders(<PositionsPage />)

    expect(await screen.findByTestId('positions-as-of')).toHaveTextContent(
      '截至 2026-11-11（美东）',
    )
    expect(byTestId('positions-count')).toHaveTextContent('共 5 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 44,500.00')

    const rows = screen.getAllByTestId('position-row')
    expect(rows.map((row) => row.getAttribute('data-contract'))).toEqual([
      'AAPL 2026-12-18 200 P',
      'AAPL 2026-12-18 210 C',
      'F 2026-12-18 12.5 P',
      'XYZ 2026-12-18 200 P',
      'MSFT 2027-01-15 450 C',
    ])
    expect(rows.map(cellTexts)).toEqual([
      [
        'AAPL 2026-12-18 200 P',
        '空头',
        '2',
        '+709.35',
        '+354.68',
        '40,000.00',
        '2026-12-18',
        '37',
        '2',
      ],
      ['AAPL 2026-12-18 210 C', '多头', '1', '−512.65', '−512.65', '—', '2026-12-18', '37', '1'],
      ['F 2026-12-18 12.5 P', '空头', '2', '+48.70', '+24.35', '2,500.00', '2026-12-18', '37', '1'],
      [
        'XYZ 2026-12-18 200 P',
        '空头',
        '1（multiplier 10）',
        '+34.35',
        '+34.35',
        '2,000.00',
        '2026-12-18',
        '37',
        '1',
      ],
      ['MSFT 2027-01-15 450 C', '多头', '1', '−900.00', '−900.00', '—', '2027-01-15', '65', '1'],
    ])
    expect(
      within(rowOf('AAPL 2026-12-18 200 P')).getByTestId('position-cell-opening-net'),
    ).toHaveStyle({
      color: '#389e0d',
    })
    expect(
      within(rowOf('AAPL 2026-12-18 210 C')).getByTestId('position-cell-opening-net'),
    ).toHaveStyle({
      color: '#cf1322',
    })
    expect(screen.getAllByText('每张平均净额（仅供参考）').length).toBeGreaterThan(0)
    expect(screen.getByText(/空头为实收、多头为实付，均已含手续费/)).toBeInTheDocument()
    // Only expand toggles, the two FEAT-016 手动设置 quote buttons (标的价, Mark) and (FEAT-004)
    // one 平仓 button per closable row; nothing else (no edit / delete).
    // querySelectorAll instead of getAllByRole: role queries over the whole table are very slow.
    const others = [...byTestId('positions-page').querySelectorAll('button')].filter(
      (b) => b.getAttribute('data-testid') !== 'position-expand',
    )
    expect(others.map((b) => b.getAttribute('data-testid'))).toEqual(
      Array.from({ length: 5 }, () => [
        'position-underlying-price-set',
        'position-mark-set',
        'position-close',
      ]).flat(),
    )
  })

  it('expands rows into FIFO lots and collapses them again (AC 2)', async () => {
    startFakeServer({ positions: () => jsonResponse(BASELINE) })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    expect(screen.queryByTestId('lot-row')).not.toBeInTheDocument()

    const aaplLots = await expand('AAPL 2026-12-18 200 P')
    expect(aaplLots.map(lotTexts)).toEqual([
      ['2026-11-03 10:30', 'STO（卖出开仓）', '1 / 1', '+349.35', '20,000.00', '第一笔 CSP'],
      ['2026-11-04 11:00', 'STO（卖出开仓）', '1 / 1', '+360.00', '20,000.00', '加仓'],
    ])
    expect(aaplLots.map((row) => row.getAttribute('data-trade-id'))).toEqual(['1', '4'])

    const fLots = await expand('F 2026-12-18 12.5 P')
    expect(fLots.map(lotTexts)).toEqual([
      ['2026-11-02 15:59', 'STO（卖出开仓）', '2 / 2', '+48.70', '2,500.00', '—'],
    ])

    const callLots = await expand('AAPL 2026-12-18 210 C')
    expect(callLots.map(lotTexts)).toEqual([
      ['2026-11-03 10:30', 'BTO（买入开仓）', '1 / 1', '−512.65', '—', '—'],
    ])

    const toggle = within(rowOf('AAPL 2026-12-18 200 P')).getByTestId('position-expand')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(toggle)
    await waitFor(() => {
      expect(within(rowOf('AAPL 2026-12-18 200 P')).getByTestId('position-expand')).toHaveAttribute(
        'aria-expanded',
        'false',
      )
    })
  })

  it('shows backend averages and summed frozen cash as-is (AC 3, 4)', async () => {
    const f = position({
      symbol: 'F',
      strike: '12.345',
      contracts: 2,
      multiplier: 1,
      openingNetTotal: '1.00',
      averageNetPerContract: '0.50',
      frozenCash: '24.70',
      lots: [
        lot({ tradeId: 1, remainingNetAmount: '0.50', remainingFrozen: '12.35' }),
        lot({ tradeId: 2, remainingNetAmount: '0.50', remainingFrozen: '12.35' }),
      ],
    })
    const ko = position({
      symbol: 'KO',
      strike: '60',
      optionType: 'CALL',
      direction: 'LONG',
      contracts: 3,
      openingNetTotal: '-200.00',
      averageNetPerContract: '-66.67',
      lots: [lot({ tradeId: 3, action: 'BTO', originalContracts: 3, remainingContracts: 3 })],
    })
    startFakeServer({
      positions: () =>
        jsonResponse({
          asOfDate: '2026-11-11',
          positionCount: 2,
          frozenTotal: '24.70',
          positions: [f, ko],
        }),
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    expect(cellTexts(rowOf('F 2026-12-18 12.345 P')).slice(1, 6)).toEqual([
      '空头',
      '2（multiplier 1）',
      '+1.00',
      '+0.50',
      '24.70',
    ])
    expect(cellTexts(rowOf('KO 2026-12-18 60 C')).slice(3, 5)).toEqual(['−200.00', '−66.67'])
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 24.70')
    const lots = await expand('F 2026-12-18 12.345 P')
    expect(lots.map((row) => within(row).getByTestId('lot-cell-frozen').textContent)).toEqual([
      '12.35',
      '12.35',
    ])
  })

  it('marks 0DTE as 今天到期 and expired positions in red (AC 5, 6)', async () => {
    const tsla = position({
      symbol: 'TSLA',
      expirationDate: '2026-11-06',
      strike: '250',
      openingNetTotal: '300.00',
      averageNetPerContract: '300.00',
      frozenCash: '25000.00',
      dte: -5,
      expirationStatus: 'EXPIRED',
      daysPastExpiration: 5,
      lots: [lot({ tradeId: 1 })],
    })
    const spy = position({
      symbol: 'SPY',
      expirationDate: '2026-11-11',
      strike: '600',
      optionType: 'CALL',
      direction: 'LONG',
      openingNetTotal: '-50.00',
      averageNetPerContract: '-50.00',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      lots: [lot({ tradeId: 2, action: 'BTO' })],
    })
    startFakeServer({
      positions: () =>
        jsonResponse({
          asOfDate: '2026-11-11',
          positionCount: 2,
          frozenTotal: '25000.00',
          positions: [tsla, spy],
        }),
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const tslaDte = within(rowOf('TSLA 2026-11-06 250 P')).getByTestId('position-cell-dte')
    expect(tslaDte).toHaveTextContent('已过期 5 天')
    expect(tslaDte.closest('.ant-typography-danger')).not.toBeNull()
    expect(
      within(rowOf('TSLA 2026-11-06 250 P')).getByTestId('position-cell-frozen'),
    ).toHaveTextContent('25,000.00')

    const spyRow = rowOf('SPY 2026-11-11 600 C')
    expect(within(spyRow).getByTestId('position-cell-dte')).toHaveTextContent(/^0\s*今天到期$/)
    expect(within(spyRow).getByTestId('position-cell-dte-today')).toHaveTextContent('今天到期')
    expect(byTestId('positions-count')).toHaveTextContent('共 2 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 25,000.00')
  })

  it('shows 暂无持仓 with a link to 录入开仓 and zero totals (AC 9)', async () => {
    startFakeServer({ positions: () => jsonResponse(EMPTY) })
    renderWithProviders(<PositionsPage />)

    expect(await screen.findByTestId('positions-empty')).toHaveTextContent('暂无持仓')
    expect(byTestId('positions-empty-new')).toHaveAttribute('href', '/trades/new')
    expect(byTestId('positions-count')).toHaveTextContent('共 0 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 0.00')
    expect(screen.queryByTestId('position-row')).not.toBeInTheDocument()
  })

  it('shows a load error with retry', async () => {
    let calls = 0
    startFakeServer({
      positions: () => {
        calls += 1
        return calls === 1
          ? jsonResponse(
              {
                code: 'INTERNAL_ERROR',
                message: 'Internal server error',
                status: 500,
                path: '/api/option-positions',
                timestamp: '2026-11-11T15:00:00Z',
                details: [],
              },
              500,
            )
          : jsonResponse(BASELINE)
      },
    })
    renderWithProviders(<PositionsPage />)

    expect(await screen.findByTestId('positions-error')).toHaveTextContent('INTERNAL_ERROR')
    await userEvent.click(byTestId('positions-retry'))
    expect(await screen.findAllByTestId('position-row')).toHaveLength(5)
  })

  it('links a contract to the trade list filtered by that contract (AC 8)', async () => {
    const urls = startFakeServer({ positions: () => jsonResponse(BASELINE) })
    renderWithProviders(<App />, { route: '/positions' })
    await screen.findAllByTestId('position-row')

    const link = within(rowOf('AAPL 2026-12-18 200 P')).getByTestId('position-cell-contract')
    expect(link).toHaveAttribute(
      'href',
      '/trades?symbol=AAPL&expirationDate=2026-12-18&strike=200&optionType=PUT',
    )
    await userEvent.click(link)

    expect(await screen.findByTestId('trade-list-page')).toBeInTheDocument()
    expect(byTestId('trade-filter-symbol')).toHaveValue('AAPL')
    expect(byTestId('trade-filter-expiration')).toHaveValue('2026-12-18')
    expect(byTestId('trade-filter-strike')).toHaveValue('200')
    expect(byTestId('trade-filter-type-put')).toBeChecked()
    await waitFor(() => {
      expect(urls).toContain(
        'GET /api/option-trades?symbol=AAPL&expirationDate=2026-12-18&strike=200&optionType=PUT&page=1&size=20',
      )
    })
  })

  it('refreshes after a trade is recorded, without reload (AC 7)', async () => {
    let current = BASELINE
    const after: OptionPositionsResponse = {
      ...BASELINE,
      frozenTotal: '64500.00',
      positions: [
        {
          ...AAPL_200_P,
          contracts: 3,
          openingNetTotal: '1049.35',
          averageNetPerContract: '349.78',
          frozenCash: '60000.00',
          lotCount: 3,
          lots: [
            ...AAPL_200_P.lots,
            lot({
              tradeId: 7,
              tradedAt: '2026-11-05T15:00:00Z',
              tradedAtLocal: '2026-11-05T10:00:00',
              remainingNetAmount: '340.00',
              remainingFrozen: '20000.00',
            }),
          ],
        },
        ...BASELINE.positions.slice(1),
      ],
    }
    const created: OptionTrade = {
      id: 7,
      symbol: 'AAPL',
      expirationDate: '2026-12-18',
      strike: '200',
      optionType: 'PUT',
      action: 'STO',
      contracts: 1,
      multiplier: 100,
      netAmount: '340.00',
      tradedAt: '2026-11-05T15:00:00Z',
      tradedAtLocal: '2026-11-05T10:00:00',
      cashChange: '340.00',
      frozenChange: '20000.00',
      contractLabel: 'AAPL 2026-12-18 200 P',
      note: null,
      ledgerEntryId: 8,
      createdAt: '2026-11-11T15:00:00Z',
      coveredLots: null,
      realizedPnl: null,
      hasAuditLog: false,
      expiredDirection: null,
      assignment: null,
    }
    startFakeServer({
      positions: () => jsonResponse(current),
      onPost: () => {
        current = after
        return jsonResponse(created, 201)
      },
    })
    renderWithProviders(<App />, { route: '/positions' })
    await screen.findAllByTestId('position-row')

    const user = userEvent.setup()
    await user.click(byTestId('nav-open-trade'))
    await user.click(byTestId('open-trade-action-sto'))
    await user.click(byTestId('open-trade-type-put'))
    const set = (id: string, value: string) => {
      fireEvent.change(byTestId(id), { target: { value } })
    }
    set('open-trade-symbol', 'AAPL')
    set('open-trade-expiration', '2026-12-18')
    set('open-trade-strike', '200')
    set('open-trade-contracts', '1')
    set('open-trade-net-amount', '340.00')
    set('open-trade-time', '2026-11-05T10:00')
    await user.click(byTestId('open-trade-submit'))
    expect(await screen.findByTestId('open-trade-confirmation')).toBeInTheDocument()

    await user.click(byTestId('nav-positions'))
    await waitFor(() => {
      expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 64,500.00')
    })
    expect(cellTexts(rowOf('AAPL 2026-12-18 200 P')).slice(2, 6)).toEqual([
      '3',
      '+1,049.35',
      '+349.78',
      '60,000.00',
    ])
    const lots = await expand('AAPL 2026-12-18 200 P')
    expect(lots.map((row) => row.getAttribute('data-trade-id'))).toEqual(['1', '4', '7'])
  })
})

describe('short calls (FEAT-038 / FEAT-039, Q62)', () => {
  // 受检: 2 CHECKED lots covering 200 shares.
  const IXQZ_9_C = position({
    symbol: 'IXQZ',
    strike: '9',
    optionType: 'CALL',
    direction: 'SHORT',
    contracts: 2,
    openingNetTotal: '55.68',
    averageNetPerContract: '27.84',
    frozenCash: '0.00',
    dte: 36,
    underlyingShares: 300,
    coveredShares: 200,
    uncoveredContracts: 0,
    uncoveredShares: 0,
    availableShares: 100,
    uncoveredShortfallAt: null,
    uncoveredShortfallAtLocal: null,
    uncoveredShortfallHeldShares: null,
    lots: [
      lot({
        tradeId: 2,
        tradedAt: '2026-11-10T17:43:00Z',
        tradedAtLocal: '2026-11-10T12:43:00',
        remainingNetAmount: '18.34',
        coverage: 'CHECKED',
        requiredShares: 100,
      }),
      lot({
        tradeId: 5,
        tradedAt: '2026-11-11T17:21:00Z',
        tradedAtLocal: '2026-11-11T12:21:00',
        remainingNetAmount: '37.34',
        coverage: 'CHECKED',
        requiredShares: 100,
      }),
    ],
  })
  // 旧批次 only (scenario L).
  const IXQZ_10_C = position({
    symbol: 'IXQZ',
    strike: '10',
    optionType: 'CALL',
    direction: 'SHORT',
    contracts: 2,
    openingNetTotal: '90.00',
    averageNetPerContract: '45.00',
    frozenCash: '0.00',
    dte: 36,
    underlyingShares: 300,
    coveredShares: 0,
    uncoveredContracts: 2,
    uncoveredShares: 200,
    availableShares: 100,
    uncoveredShortfallAt: '2026-11-03T15:00:00Z',
    uncoveredShortfallAtLocal: '2026-11-03T10:00:00',
    uncoveredShortfallHeldShares: 0,
    lots: [lot({ tradeId: 1, remainingContracts: 2, coverage: 'LEGACY', requiredShares: 200 })],
  })
  // Both kinds of lot in one row.
  const IXQZ_11_C = position({
    symbol: 'IXQZ',
    strike: '11',
    optionType: 'CALL',
    direction: 'SHORT',
    contracts: 3,
    openingNetTotal: '60.00',
    averageNetPerContract: '20.00',
    frozenCash: '0.00',
    dte: 36,
    underlyingShares: 300,
    coveredShares: 100,
    uncoveredContracts: 2,
    uncoveredShares: 200,
    availableShares: 100,
    uncoveredShortfallAt: '2026-11-03T15:00:00Z',
    uncoveredShortfallAtLocal: '2026-11-03T10:00:00',
    uncoveredShortfallHeldShares: 0,
    lots: [
      lot({ tradeId: 4, remainingContracts: 2, coverage: 'LEGACY', requiredShares: 200 }),
      lot({ tradeId: 6, coverage: 'CHECKED', requiredShares: 100 }),
    ],
  })

  it('shows 已覆盖 / 未覆盖 on short call rows, never 未冻结 (FEAT-039 AC 1, 6, 8)', async () => {
    startFakeServer({
      positions: () =>
        jsonResponse({
          asOfDate: '2026-11-12',
          positionCount: 5,
          frozenTotal: '40000.00',
          positions: [AAPL_200_P, AAPL_210_C, IXQZ_9_C, IXQZ_10_C, IXQZ_11_C],
        }),
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const frozenOf = (contract: string) =>
      within(rowOf(contract)).getByTestId('position-cell-frozen')
    const nine = frozenOf('IXQZ 2026-12-18 9 C')
    expect(within(nine).getByTestId('position-cell-coverage-checked')).toHaveTextContent(
      '已覆盖 200 股',
    )
    expect(within(nine).queryByTestId('position-cell-coverage-legacy')).toBeNull()

    const ten = frozenOf('IXQZ 2026-12-18 10 C')
    expect(within(ten).getByTestId('position-cell-coverage-legacy')).toHaveTextContent(/^未覆盖$/)
    expect(within(ten).queryByTestId('position-cell-coverage-checked')).toBeNull()

    const eleven = frozenOf('IXQZ 2026-12-18 11 C')
    expect(within(eleven).getByTestId('position-cell-coverage-checked')).toHaveTextContent(
      '已覆盖 100 股',
    )
    expect(within(eleven).getByTestId('position-cell-coverage-legacy')).toHaveTextContent(
      '未覆盖 2 张',
    )

    // Long call: still "—"; short put: still its amount.
    expect(frozenOf('AAPL 2026-12-18 210 C')).toHaveTextContent('—')
    expect(frozenOf('AAPL 2026-12-18 200 P')).toHaveTextContent('40,000.00')
    expect(screen.queryByText('未冻结')).toBeNull()

    // Other columns behave like a short put.
    expect(cellTexts(rowOf('IXQZ 2026-12-18 9 C')).slice(1, 5)).toEqual([
      '空头',
      '2',
      '+55.68',
      '+27.84',
    ])
    expect(within(rowOf('IXQZ 2026-12-18 9 C')).getByTestId('position-close')).toBeInTheDocument()

    await userEvent.hover(within(nine).getByTestId('position-cell-coverage-checked'))
    expect(
      await screen.findByText('占用 IXQZ 200 股，平仓、作废或被指派前这些股票不能卖出'),
    ).toBeInTheDocument()

    await userEvent.hover(within(ten).getByTestId('position-cell-coverage-legacy'))
    expect(
      await screen.findByText(
        '上线前录入，未纳入覆盖检查：需要 200 股，当前可用 100 股；2026-11-03 10:00 时持有 0 股。补录该时间之前的股票后自动变为已覆盖',
      ),
    ).toBeInTheDocument()
  })

  it('shows 被覆盖 and 可用 columns in the stock section (FEAT-039)', async () => {
    startFakeServer({
      positions: () => jsonResponse(EMPTY),
      stocks: {
        positionCount: 1,
        positions: [
          {
            symbol: 'IXQZ',
            shares: 1200,
            coveredShares: 1000,
            availableShares: 200,
            totalCost: '9600.00',
            averageCost: '8.0000',
            lotCount: 0,
            lots: [],
          },
        ],
      },
    })
    renderWithProviders(<PositionsPage />)
    const row = await screen.findByTestId('stock-position-row')
    expect(within(row).getByTestId('stock-position-shares')).toHaveTextContent('1,200')
    expect(within(row).getByTestId('stock-position-covered-shares')).toHaveTextContent('1,000')
    expect(within(row).getByTestId('stock-position-available-shares')).toHaveTextContent('200')
    expect(screen.getByRole('columnheader', { name: '被覆盖' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '可用' })).toBeInTheDocument()
  })
})

describe('stock section anchor (FEAT-040, Q89)', () => {
  it('scrolls the 股票持仓 section into view for /positions#stock-positions', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    })
    try {
      startFakeServer({ positions: () => jsonResponse(BASELINE) })
      renderWithProviders(<PositionsPage />, { route: '/positions#stock-positions' })

      const section = await screen.findByTestId('stock-positions')
      expect(section).toHaveAttribute('id', 'stock-positions')
      await waitFor(() => {
        expect(scrollIntoView).toHaveBeenCalledTimes(1)
      })
      expect(scrollIntoView.mock.contexts[0]).toBe(section)
    } finally {
      Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })

  it('does not scroll without the hash', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    })
    try {
      startFakeServer({ positions: () => jsonResponse(BASELINE) })
      renderWithProviders(<PositionsPage />, { route: '/positions' })
      await screen.findByTestId('positions-count')
      await screen.findByTestId('stock-positions-count')
      expect(scrollIntoView).not.toHaveBeenCalled()
    } finally {
      Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })
})
