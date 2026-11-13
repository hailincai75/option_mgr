import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  AuditRecord,
  CashLedgerEntry,
  ClosedOptionRecord,
  ClosedStockRecord,
  CoveredLot,
  OptionPosition,
  OptionPositionsResponse,
  OptionTrade,
  OptionTradePage,
  StockSoldLot,
} from '../api/types'
import { AuditTable } from '../components/audit/AuditTable'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { ClosedTradeTable } from '../components/closed/ClosedTradeTable'
import { StockFilterForm } from '../components/stock/StockFilterForm'
import {
  isStockTradeType,
  STOCK_TRADE_TYPES,
  stockTradeSummary,
  stockTypeLabel,
} from '../components/stock/stockLabels'
import { EMPTY_STOCK_FILTERS, stocksUrlForAssignment } from '../components/stock/stockListFilters'
import { StockTradeTable } from '../components/stock/StockTradeTable'
import { assignmentRowText, isCallAssignment } from '../components/trade/tradeLabels'
import { TradeTable } from '../components/trade/TradeTable'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { stockTrade } from '../test/stockFixtures'
import { PositionsPage } from './PositionsPage'
import { TradeListPage } from './TradeListPage'

// FEAT-041 卖出 call 被指派. Fictional tickers (IXQZ) from the requirement's scenario C; every
// value below is what the backend would return (the frontend calculates nothing).

const noop = () => undefined

const IXQZ_9_C: OptionPosition = {
  symbol: 'IXQZ',
  expirationDate: '2026-11-20',
  strike: '9',
  optionType: 'CALL',
  contractLabel: 'IXQZ 2026-11-20 9 C',
  direction: 'SHORT',
  contracts: 3,
  multiplier: 100,
  openingNetTotal: '85.00',
  averageNetPerContract: '28.33',
  frozenCash: '0.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  lotCount: 2,
  lots: [],
  closeAction: 'BTC',
  allowedCloseContracts: [2, 3],
  closable: false,
  expiryPending: false,
  expirable: true,
  expiryContracts: 3,
  expiryAt: '2026-11-20T21:00:00Z',
  expiryAtLocal: '2026-11-20T16:00:00',
  assignable: true,
  assignChoices: [
    { contracts: 2, shares: 200 },
    { contracts: 3, shares: 300 },
  ],
  assignmentDateMax: '2026-11-20',
  assignmentDateDefault: '2026-11-20',
  underlyingShares: 300,
}

const IXQZ_10_C_LONG: OptionPosition = {
  ...IXQZ_9_C,
  strike: '10',
  contractLabel: 'IXQZ 2026-11-20 10 C',
  direction: 'LONG',
  contracts: 1,
  openingNetTotal: '-15.00',
  closeAction: 'STC',
  allowedCloseContracts: [1],
  assignable: false,
  assignChoices: [],
  assignmentDateMax: null,
  assignmentDateDefault: null,
  underlyingShares: null,
}

function positionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

const C1_LOT: CoveredLot = {
  tradeId: 1,
  action: 'STO',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  contracts: 2,
  netAmount: '60.00',
  frozen: '0.00',
}
const C2_LOT: CoveredLot = {
  tradeId: 2,
  action: 'STO',
  tradedAt: '2026-11-06T15:00:00Z',
  tradedAtLocal: '2026-11-06T10:00:00',
  contracts: 1,
  netAmount: '25.00',
  frozen: '0.00',
}

const SOLD_LOTS: StockSoldLot[] = [
  {
    tradeId: 1,
    type: 'BUY',
    tradedAt: '2026-11-03T15:00:00Z',
    tradedAtLocal: '2026-11-03T10:00:00',
    tradeDate: null,
    shares: 150,
    cost: '1200.00',
  },
  {
    tradeId: 2,
    type: 'BUY',
    tradedAt: '2026-11-04T15:00:00Z',
    tradedAtLocal: '2026-11-04T10:00:00',
    tradeDate: null,
    shares: 150,
    cost: '1350.00',
  },
]

/** Contract example: AC 2 (3 contracts assigned on 2026-11-20, fee 0.00). */
const CALL_ASSIGNMENT: OptionTrade = {
  id: 4,
  symbol: 'IXQZ',
  expirationDate: '2026-11-20',
  strike: '9',
  optionType: 'CALL',
  action: 'ASG',
  contracts: 3,
  multiplier: 100,
  netAmount: '0.00',
  tradedAt: '2026-11-20T21:00:00Z',
  tradedAtLocal: '2026-11-20T16:00:00',
  cashChange: '2700.00',
  frozenChange: '0.00',
  contractLabel: 'IXQZ 2026-11-20 9 C',
  note: null,
  ledgerEntryId: 7,
  createdAt: '2026-11-23T15:00:00Z',
  coveredLots: [C1_LOT, C2_LOT],
  realizedPnl: '0.00',
  hasAuditLog: false,
  expiredDirection: null,
  assignment: {
    assignmentDate: '2026-11-20',
    assignmentFee: '0.00',
    premium: '85.00',
    shares: 300,
    purchaseAmount: null,
    stockCost: null,
    stockCostPerShare: null,
    saleAmount: '2700.00',
    proceeds: '2785.00',
    soldCost: '2550.00',
    stockRealizedPnl: '235.00',
    soldLots: SOLD_LOTS,
    stockTradeId: 3,
  },
}

/** AC 3: early assignment of 2 contracts with a 5.00 fee. */
const EARLY_CALL_ASSIGNMENT: OptionTrade = {
  ...CALL_ASSIGNMENT,
  id: 5,
  contracts: 2,
  cashChange: '1795.00',
  coveredLots: [C1_LOT],
  assignment: {
    ...(CALL_ASSIGNMENT.assignment as NonNullable<OptionTrade['assignment']>),
    assignmentDate: '2026-11-18',
    assignmentFee: '5.00',
    premium: '60.00',
    shares: 200,
    saleAmount: '1800.00',
    proceeds: '1855.00',
    soldCost: '1650.00',
    stockRealizedPnl: '205.00',
  },
}

function errorBody(code: string, status: number, message: string, path: string): ApiErrorResponse {
  return { code, message, status, path, timestamp: '2026-11-23T15:00:00Z', details: [] }
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

type Handler = (request: Recorded) => Response | undefined

function startFakeServer(...handlers: Handler[]): Recorded[] {
  const requests: Recorded[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    const request = { method, url, body }
    requests.push(request)
    for (const handler of handlers) {
      const response = handler(request)
      if (response) return Promise.resolve(response)
    }
    if (method === 'GET' && url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

async function openCallAssignDialog(post: Handler): Promise<Recorded[]> {
  const requests = startFakeServer(({ method, url }) => {
    if (method === 'GET' && url === '/api/option-positions') {
      return jsonResponse(positionsResponse([IXQZ_9_C, IXQZ_10_C_LONG]))
    }
    return undefined
  }, post)
  renderWithProviders(<PositionsPage />)
  await screen.findAllByTestId('position-row')
  await userEvent.click(within(rowOf('IXQZ 2026-11-20 9 C')).getByTestId('position-assign'))
  await screen.findByTestId('assign-modal')
  return requests
}

const ASSIGN_URL = '/api/option-trades/assignments'

// ---------------------------------------------------------------------------

describe('FEAT-041 记录被指派 dialog for a short call', () => {
  it('shows 被指派 only on the short call, the current shares and the call wording (AC 1)', async () => {
    await openCallAssignDialog(() => undefined)
    expect(
      within(rowOf('IXQZ 2026-11-20 10 C')).queryByTestId('position-assign'),
    ).not.toBeInTheDocument()

    const modal = screen.getByTestId('assign-modal')
    expect(within(modal).getByTestId('assign-underlying-shares')).toHaveTextContent('300 股 IXQZ')
    expect(within(modal).queryByTestId('assign-frozen')).not.toBeInTheDocument()
    expect(within(modal).getByTestId('assign-contracts')).toHaveTextContent('3 张（300 股）')
    expect(within(modal).getByTestId('assign-date')).toHaveValue('2026-11-20')
    expect(within(modal).getByText('券商收取的指派手续费，从卖出实收中扣')).toBeInTheDocument()
    expect(within(modal).getByTestId('assign-explanation')).toHaveTextContent(
      '按行权价按 FIFO 交出股票；卖出实收 = 行权价 × 股数 + 权利金 − 指派费，期权已实现盈亏记 0.00（暂定口径）。持股不足时请先录入股票。',
    )
  })

  it('submits the call and shows the backend result with the delivered lots (AC 2)', async () => {
    const requests = await openCallAssignDialog(({ method, url }) =>
      method === 'POST' && url === ASSIGN_URL ? jsonResponse(CALL_ASSIGNMENT, 201) : undefined,
    )
    const modal = screen.getByTestId('assign-modal')
    await userEvent.click(within(modal).getByTestId('assign-submit'))
    await within(modal).findByTestId('assign-result')

    expect(requests.find((r) => r.method === 'POST' && r.url === ASSIGN_URL)?.body).toEqual({
      symbol: 'IXQZ',
      expirationDate: '2026-11-20',
      strike: '9',
      optionType: 'CALL',
      contracts: 3,
      assignmentDate: '2026-11-20',
      assignmentFee: '0.00',
      note: null,
    })
    const text = (id: string) => within(modal).getByTestId(id).textContent
    expect(text('assign-result-time')).toBe('2026-11-20 16:00（美东）')
    expect(text('assign-result-premium')).toBe('85.00')
    expect(text('assign-result-shares')).toBe('300 股 IXQZ')
    expect(text('assign-result-sale-amount')).toBe('2,700.00')
    expect(text('assign-result-fee')).toBe('0.00')
    expect(text('assign-result-proceeds')).toBe('2,785.00')
    expect(text('assign-result-sold-cost')).toBe('2,550.00')
    expect(text('assign-result-stock-pnl')).toBe('+235.00')
    expect(within(modal).getByTestId('assign-result-stock-pnl').style.color).not.toBe('')
    expect(text('assign-result-cash-change')).toBe('+2,700.00')
    expect(text('assign-result-frozen-change')).toBe('—')
    expect(text('assign-result-pnl')).toBe('0.00')
    // Put-only fields are not shown for a call.
    expect(within(modal).queryByTestId('assign-result-purchase')).not.toBeInTheDocument()
    expect(within(modal).queryByTestId('assign-result-stock-cost')).not.toBeInTheDocument()

    const soldLots = within(modal).getByTestId('assign-result-sold-lots')
    const lotRows = within(soldLots).getAllByTestId('sold-lot-row')
    expect(lotRows).toHaveLength(2)
    expect(within(lotRows[0] as HTMLElement).getByTestId('sold-lot-source')).toHaveTextContent(
      '买入 #1',
    )
    expect(within(lotRows[1] as HTMLElement).getByTestId('sold-lot-shares')).toHaveTextContent(
      '150 股',
    )
  })

  it.each([
    [
      'SHARES_INSUFFICIENT',
      '持股不足：被指派需要 300 股 IXQZ，2026-11-20 16:00 时持有 150 股。请先录入股票（转入或更早的买入）',
    ],
    [
      'SHARES_INSUFFICIENT',
      '补录后 2026-11-24 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股',
    ],
    ['ASSIGNMENT_PROCEEDS_NOT_POSITIVE', '被指派后股票卖出实收将为 −5.00，请检查指派费'],
  ])('shows a %s rejection verbatim and keeps the dialog (AC 4, 5)', async (code, message) => {
    await openCallAssignDialog(({ method, url }) =>
      method === 'POST' && url === ASSIGN_URL
        ? jsonResponse(errorBody(code, 422, message, url), 422)
        : undefined,
    )
    const modal = screen.getByTestId('assign-modal')
    await userEvent.click(within(modal).getByTestId('assign-submit'))
    const error = await within(modal).findByTestId('assign-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(within(modal).queryByTestId('assign-result')).not.toBeInTheDocument()
    expect(within(modal).getByTestId('assign-date')).toHaveValue('2026-11-20')
  })

  it('shows "—" for the current shares when the backend sends none', async () => {
    startFakeServer(({ method, url }) =>
      method === 'GET' && url === '/api/option-positions'
        ? jsonResponse(positionsResponse([{ ...IXQZ_9_C, underlyingShares: 0 }]))
        : undefined,
    )
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    fireEvent.click(within(rowOf('IXQZ 2026-11-20 9 C')).getByTestId('position-assign'))
    const modal = await screen.findByTestId('assign-modal')
    expect(within(modal).getByTestId('assign-underlying-shares')).toHaveTextContent('0 股 IXQZ')
  })
})

describe('FEAT-041 trade list', () => {
  it('shows call ASG rows with 卖出 small text, positive cash and frozen "—"', () => {
    renderWithProviders(
      <TradeTable
        trades={[CALL_ASSIGNMENT, EARLY_CALL_ASSIGNMENT]}
        loading={false}
        empty={null}
        onContractClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const [row, early] = screen.getAllByTestId('trade-row') as [HTMLElement, HTMLElement]
    expect(within(row).getByTestId('trade-cell-action')).toHaveTextContent('被指派')
    expect(within(row).getByTestId('trade-cell-assignment')).toHaveTextContent(
      '卖出 300 股 IXQZ，现金 +2,700.00',
    )
    expect(within(row).getByTestId('trade-cell-frozen')).toHaveTextContent('—')
    expect(within(row).queryByTestId('trade-edit')).not.toBeInTheDocument()
    expect(within(row).getByTestId('trade-delete')).toBeInTheDocument()
    expect(within(early).getByTestId('trade-cell-assignment')).toHaveTextContent(
      '卖出 200 股 IXQZ，现金 +1,795.00，指派费 5.00',
    )
  })

  it('explains deleting a call ASG (AC 6)', async () => {
    startFakeServer(({ method, url }) => {
      if (method === 'GET' && url.startsWith('/api/option-trades?')) {
        const page: OptionTradePage = {
          items: [CALL_ASSIGNMENT],
          page: 1,
          size: 20,
          totalItems: 1,
          totalPages: 1,
        }
        return jsonResponse(page)
      }
      return undefined
    })
    renderWithProviders(<TradeListPage />, { route: '/trades?action=ASG' })
    const row = await screen.findByTestId('trade-row')
    await userEvent.click(within(row).getByTestId('trade-delete'))
    const modal = await screen.findByTestId('trade-delete-modal')
    expect(within(modal).getByTestId('trade-delete-summary')).toHaveTextContent(
      '被指派 3 张 IXQZ 2026-11-20 9 C，2026-11-20 16:00，卖出 300 股 IXQZ',
    )
    expect(within(modal).getByTestId('trade-delete-warning')).toHaveTextContent(
      '交出的股票恢复为持仓',
    )
  })

  it('assignmentRowText / isCallAssignment tell put and call apart by optionType', () => {
    expect(isCallAssignment(CALL_ASSIGNMENT)).toBe(true)
    expect(isCallAssignment({ optionType: 'PUT' })).toBe(false)
    expect(assignmentRowText({ ...CALL_ASSIGNMENT, assignment: null })).toBeNull()
  })
})

describe('FEAT-041 closed trades (Q100: 2 rows)', () => {
  const CLOSED_CALL_ASG: ClosedOptionRecord = {
    type: 'OPTION',
    id: 4,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'IXQZ',
    expirationDate: '2026-11-20',
    strike: '9',
    optionType: 'CALL',
    contractLabel: 'IXQZ 2026-11-20 9 C',
    action: 'ASG',
    closedDirection: 'SHORT',
    contracts: 3,
    multiplier: 100,
    shares: null,
    openingNetTotal: '85.00',
    openingNetPerContract: '28.33',
    costPerShare: null,
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    pricePerShare: null,
    realizedPnl: '0.00',
    note: null,
    coveredLots: [C1_LOT, C2_LOT],
    soldLots: null,
    assignment: { shares: 300, stockCost: null, proceeds: '2785.00', stockTradeId: 3 },
    hasAuditLog: false,
  }
  const CLOSED_ASSIGN_SELL: ClosedStockRecord = {
    type: 'STOCK',
    id: 3,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'IXQZ',
    expirationDate: null,
    strike: null,
    optionType: null,
    contractLabel: null,
    action: 'ASSIGN_SELL',
    closedDirection: null,
    contracts: null,
    multiplier: null,
    shares: 300,
    openingNetTotal: '-2550.00',
    openingNetPerContract: null,
    costPerShare: '8.5000',
    closeNetAmount: '2785.00',
    closeNetPerContract: null,
    pricePerShare: '9.2833',
    realizedPnl: '235.00',
    note: null,
    coveredLots: null,
    soldLots: SOLD_LOTS.map((lot) => ({
      tradeId: lot.tradeId,
      type: lot.type,
      tradedAt: lot.tradedAt,
      tradedAtLocal: lot.tradedAtLocal,
      tradeDate: lot.tradeDate,
      shares: lot.shares,
      netAmount: `-${lot.cost}`,
    })),
    assignment: null,
    assignmentTradeId: 4,
    hasAuditLog: false,
  }

  it('shows the 指派卖出 stock row above the 被指派 option row with links (AC 2)', async () => {
    renderWithProviders(
      <ClosedTradeTable
        trades={[CLOSED_ASSIGN_SELL, CLOSED_CALL_ASG]}
        loading={false}
        empty={null}
      />,
    )
    const [stock, option] = screen.getAllByTestId('closed-row') as [HTMLElement, HTMLElement]

    expect(within(stock).getByTestId('closed-cell-type')).toHaveTextContent('股票')
    expect(within(stock).getByTestId('closed-cell-action')).toHaveTextContent('指派卖出')
    expect(within(stock).getByTestId('closed-cell-pnl')).toHaveTextContent('+235.00')
    const back = within(stock).getByTestId('closed-stock-assignment-link')
    expect(back).toHaveTextContent('来自被指派 #4')
    expect(back).toHaveAttribute(
      'href',
      '/trades?symbol=IXQZ&startDate=2026-11-20&endDate=2026-11-20&action=ASG',
    )

    expect(within(option).getByTestId('closed-cell-action')).toHaveTextContent('被指派')
    expect(within(option).getByTestId('closed-cell-pnl')).toHaveTextContent(/^0\.00$/)
    expect(within(option).getByTestId('closed-cell-assignment')).toHaveTextContent(
      '权利金已计入股票卖出（300 股 IXQZ，实收 2,785.00）',
    )
    const stockLink = within(option).getByTestId('closed-assignment-stock-link')
    expect(stockLink).toHaveTextContent('股票记录 #3')
    expect(stockLink).toHaveAttribute(
      'href',
      '/stocks?symbol=IXQZ&type=ASSIGN_SELL&startDate=2026-11-20&endDate=2026-11-20',
    )
    expect(within(option).queryByTestId('closed-stock-assignment-link')).not.toBeInTheDocument()

    // The stock row expands to the delivered lots.
    await userEvent.click(within(stock).getByTestId('closed-expand'))
    const lots = await screen.findAllByTestId('closed-sold-lot-row')
    expect(lots).toHaveLength(2)
  })
})

describe('FEAT-041 股票 page', () => {
  const ASSIGN_SELL = stockTrade({
    id: 3,
    type: 'ASSIGN_SELL',
    shares: 300,
    amount: '2785.00',
    cashChange: '0.00',
    pricePerShare: '9.2833',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    ledgerEntryId: null,
    soldCost: '2550.00',
    realizedPnl: '235.00',
    soldLots: SOLD_LOTS,
    assignmentTradeId: 4,
    editable: false,
    deletable: false,
  })

  it('shows 指派卖出 with 实收, P&L, a link back and no 修改 / 删除; expands the sold lots', async () => {
    renderWithProviders(
      <StockTradeTable
        trades={[ASSIGN_SELL]}
        loading={false}
        empty={null}
        onSymbolClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const row = screen.getByTestId('stock-row')
    const cell = (id: string) => within(row).getByTestId(id)
    expect(row).toHaveAttribute('data-stock-type', 'ASSIGN_SELL')
    expect(cell('stock-cell-type')).toHaveTextContent('指派卖出')
    expect(cell('stock-cell-amount')).toHaveTextContent('实收 2,785.00')
    expect(cell('stock-cell-amount').style.color).toBe('')
    expect(cell('stock-cell-pnl')).toHaveTextContent('+235.00')
    expect(cell('stock-assignment-link')).toHaveTextContent('来自被指派 #4')
    expect(cell('stock-assignment-link')).toHaveAttribute(
      'href',
      '/trades?symbol=IXQZ&startDate=2026-11-20&endDate=2026-11-20&action=ASG',
    )
    expect(within(row).queryByTestId('stock-edit')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('stock-delete')).not.toBeInTheDocument()
    expect(cell('stock-assign-buy-hint')).toHaveTextContent('由被指派生成，在交易列表删除')

    await userEvent.click(cell('stock-expand'))
    const detail = await screen.findByTestId('stock-sold-detail')
    expect(within(detail).getAllByTestId('sold-lot-row')).toHaveLength(2)
    expect(within(detail).getByTestId('stock-sold-cost')).toHaveTextContent('2,550.00')
  })

  it('offers 指派卖出 in the type filter but never in the entry form', () => {
    renderWithProviders(
      <StockFilterForm
        initialValues={EMPTY_STOCK_FILTERS}
        errors={{}}
        onApply={noop}
        onClear={noop}
      />,
    )
    expect(screen.getByTestId('stock-filter-type-assign-sell').closest('label')).toHaveTextContent(
      '指派卖出',
    )
    expect(STOCK_TRADE_TYPES).not.toContain('ASSIGN_SELL')
    expect(isStockTradeType('ASSIGN_SELL')).toBe(false)
  })

  it('labels and summarizes 指派卖出 and builds its 股票 page URL', () => {
    expect(stockTypeLabel('ASSIGN_SELL')).toBe('指派卖出')
    expect(stockTradeSummary(ASSIGN_SELL)).toBe('指派卖出 300 股 IXQZ，2026-11-20 16:00，实收 2,785.00')
    expect(stocksUrlForAssignment('IXQZ', '2026-11-20T16:00:00', 'ASSIGN_SELL')).toBe(
      '/stocks?symbol=IXQZ&type=ASSIGN_SELL&startDate=2026-11-20&endDate=2026-11-20',
    )
    expect(stocksUrlForAssignment('XYZ', '2026-11-20T16:00:00')).toContain('type=ASSIGN_BUY')
  })
})

describe('FEAT-041 cash ledger and audit', () => {
  it('shows a positive OPTION_ASSIGN row with the backend description', async () => {
    const entry: CashLedgerEntry = {
      id: 7,
      occurredAt: '2026-11-20T21:00:00Z',
      sourceType: 'OPTION_ASSIGN',
      sourceId: 4,
      amount: '2700.00',
      balanceAfter: '10220.00',
      frozenChange: '0.00',
      description: '被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ',
      remark: null,
      hasAuditLog: false,
    }
    startFakeServer(({ url }) =>
      url.startsWith('/api/cash/ledger')
        ? jsonResponse({ items: [entry], page: 1, size: 20, totalItems: 1, totalPages: 1 })
        : undefined,
    )
    renderWithProviders(<CashLedgerTable page={1} onPageChange={noop} />)
    const row = await screen.findByTestId('cash-ledger-row')
    expect(within(row).getByTestId('cash-ledger-type')).toHaveTextContent('被指派')
    expect(within(row).getByTestId('cash-ledger-amount')).toHaveTextContent('+2,700.00')
    expect(row).toHaveTextContent('被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ')
  })

  it('shows the call assignment fields of a deleted ASG and side effects (AC 6, 7)', async () => {
    const deleted: AuditRecord = {
      id: 9,
      operatedAt: '2026-11-23T15:10:00Z',
      operation: 'DELETE',
      objectType: 'OPTION_TRADE',
      objectId: 4,
      summary: '被指派 IXQZ 2026-11-20 9 C 3 张（卖出 300 股 IXQZ）',
      before: {
        id: 4,
        symbol: 'IXQZ',
        optionType: 'CALL',
        contractLabel: 'IXQZ 2026-11-20 9 C',
        action: 'ASG',
        contracts: 3,
        multiplier: 100,
        netAmount: '0.00',
        tradedAtLocal: '2026-11-20T16:00:00',
        note: null,
        cashChange: '2700.00',
        frozenChange: '0.00',
        assignmentDate: '2026-11-20',
        assignmentFee: '0.00',
        shares: 300,
        premium: '85.00',
        saleAmount: '2700.00',
        proceeds: '2785.00',
        soldCost: '2550.00',
        stockRealizedPnl: '235.00',
        stockTradeId: 3,
      },
      after: null,
      changes: [],
      sideEffects: [],
    }
    const edited: AuditRecord = {
      id: 10,
      operatedAt: '2026-11-23T15:20:00Z',
      operation: 'UPDATE',
      objectType: 'OPTION_TRADE',
      objectId: 2,
      summary: '修改 STO IXQZ 2026-11-20 9 C',
      before: null,
      after: null,
      changes: [],
      sideEffects: [
        {
          tradeId: 4,
          action: 'ASG',
          contractLabel: 'IXQZ 2026-11-20 9 C',
          field: 'proceeds',
          before: '2785.00',
          after: '2790.00',
          text: '被指派 #4 股票卖出实收 2,785.00 → 2,790.00',
        },
      ],
    }
    renderWithProviders(<AuditTable records={[deleted, edited]} loading={false} empty={null} />)
    expect(screen.getAllByTestId('audit-cell-side-effects')[1]).toHaveTextContent(
      '被指派 #4 股票卖出实收 2,785.00 → 2,790.00',
    )
    await userEvent.click(screen.getAllByTestId('audit-expand')[0] as HTMLElement)
    const detail = await screen.findByTestId('audit-detail')
    const value = (key: string) => within(detail).getByTestId(`audit-before-${key}`).textContent
    expect(value('premium')).toBe('85.00')
    expect(value('saleAmount')).toBe('2,700.00')
    expect(value('proceeds')).toBe('2,785.00')
    expect(value('soldCost')).toBe('2,550.00')
    expect(value('stockRealizedPnl')).toBe('+235.00')
    expect(value('stockTradeId')).toBe('3')
    expect(value('cashChange')).toBe('+2,700.00')
    expect(within(detail).queryByTestId('audit-before-purchaseAmount')).not.toBeInTheDocument()
    expect(within(detail).queryByTestId('audit-before-stockCost')).not.toBeInTheDocument()
  })
})

