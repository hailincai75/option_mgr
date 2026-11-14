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
} from '../api/types'
import { AuditTable } from '../components/audit/AuditTable'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { ClosedTradeTable } from '../components/closed/ClosedTradeTable'
import { CLOSE_ACTIONS } from '../components/closed/closedListFilters'
import { coverageTags } from '../components/position/coverageLabels'
import { StockFilterForm } from '../components/stock/StockFilterForm'
import {
  isStockTradeType,
  STOCK_RECORD_TYPES,
  STOCK_TRADE_TYPES,
  stockTradeSummary,
  stockTypeLabel,
} from '../components/stock/stockLabels'
import { EMPTY_STOCK_FILTERS, stocksUrlForAssignment } from '../components/stock/stockListFilters'
import { StockTradeTable } from '../components/stock/StockTradeTable'
import {
  exerciseRowText,
  isDeleteOnlyAction,
  tradeActionLabel,
} from '../components/trade/tradeLabels'
import { TRADE_ACTIONS } from '../components/trade/tradeListFilters'
import { TradeTable } from '../components/trade/TradeTable'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { stockTrade } from '../test/stockFixtures'
import { PositionsPage } from './PositionsPage'
import { TradeListPage } from './TradeListPage'

// FEAT-014 主动行权. Fictional tickers (QRTV, XYZ) from the requirement's scenario E; every value
// below is what the backend would return (the frontend calculates nothing).

const noop = () => undefined

/** L1 + L2: long QRTV 2026-11-20 30 C, 3 contracts (clock 2026-11-23 10:00: expired). */
const QRTV_30_C: OptionPosition = {
  symbol: 'QRTV',
  expirationDate: '2026-11-20',
  strike: '30',
  optionType: 'CALL',
  contractLabel: 'QRTV 2026-11-20 30 C',
  direction: 'LONG',
  contracts: 3,
  multiplier: 100,
  openingNetTotal: '-460.00',
  averageNetPerContract: '-153.33',
  frozenCash: '0.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  lotCount: 2,
  lots: [],
  closeAction: 'STC',
  allowedCloseContracts: [2, 3],
  closable: false,
  expiryPending: false,
  expirable: true,
  expiryContracts: 3,
  expiryAt: '2026-11-20T21:00:00Z',
  expiryAtLocal: '2026-11-20T16:00:00',
  assignable: false,
  assignChoices: [],
  assignmentDateMax: null,
  assignmentDateDefault: null,
  exercisable: true,
  exerciseChoices: [
    { contracts: 2, shares: 200 },
    { contracts: 3, shares: 300 },
  ],
  exerciseDateMax: '2026-11-20',
  exerciseDateDefault: '2026-11-20',
  availableCash: '11510.00',
  underlyingShares: null,
  availableShares: null,
  coveredShares: null,
}

/** L3: long XYZ 2026-11-20 45 P, 1 contract; XYZ 200 held, 100 covered by S1. */
const XYZ_45_P: OptionPosition = {
  ...QRTV_30_C,
  symbol: 'XYZ',
  strike: '45',
  optionType: 'PUT',
  contractLabel: 'XYZ 2026-11-20 45 P',
  contracts: 1,
  openingNetTotal: '-120.00',
  averageNetPerContract: '-120.00',
  lotCount: 1,
  allowedCloseContracts: [1],
  expiryContracts: 1,
  exerciseChoices: [{ contracts: 1, shares: 100 }],
  availableCash: null,
  underlyingShares: 200,
  availableShares: 100,
}

/** S1: short XYZ 2026-12-18 50 C (no 行权). */
const XYZ_50_C_SHORT: OptionPosition = {
  ...QRTV_30_C,
  symbol: 'XYZ',
  expirationDate: '2026-12-18',
  strike: '50',
  contractLabel: 'XYZ 2026-12-18 50 C',
  direction: 'SHORT',
  contracts: 1,
  openingNetTotal: '90.00',
  averageNetPerContract: '90.00',
  dte: 25,
  expirationStatus: 'ACTIVE',
  daysPastExpiration: 0,
  lotCount: 1,
  closeAction: 'BTC',
  allowedCloseContracts: [1],
  closable: true,
  expirable: false,
  assignable: true,
  assignChoices: [{ contracts: 1, shares: 100 }],
  assignmentDateMax: '2026-11-20',
  assignmentDateDefault: '2026-11-20',
  exercisable: false,
  exerciseChoices: [],
  exerciseDateMax: null,
  exerciseDateDefault: null,
  availableCash: null,
  underlyingShares: 200,
  availableShares: 100,
  coveredShares: 100,
  uncoveredContracts: 0,
}

function positionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

const L1_LOT: CoveredLot = {
  tradeId: 1,
  action: 'BTO',
  tradedAt: '2026-11-04T15:00:00Z',
  tradedAtLocal: '2026-11-04T10:00:00',
  contracts: 2,
  netAmount: '-300.00',
  frozen: '0.00',
}
const L2_LOT: CoveredLot = {
  tradeId: 2,
  action: 'BTO',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  contracts: 1,
  netAmount: '-160.00',
  frozen: '0.00',
}
const L3_LOT: CoveredLot = {
  tradeId: 3,
  action: 'BTO',
  tradedAt: '2026-11-05T16:00:00Z',
  tradedAtLocal: '2026-11-05T11:00:00',
  contracts: 1,
  netAmount: '-120.00',
  frozen: '0.00',
}

/** Contract call example (AC 2). */
const CALL_EXERCISE: OptionTrade = {
  id: 5,
  symbol: 'QRTV',
  expirationDate: '2026-11-20',
  strike: '30',
  optionType: 'CALL',
  action: 'EXR',
  contracts: 3,
  multiplier: 100,
  netAmount: '0.00',
  tradedAt: '2026-11-20T21:00:00Z',
  tradedAtLocal: '2026-11-20T16:00:00',
  cashChange: '-9000.00',
  frozenChange: '0.00',
  contractLabel: 'QRTV 2026-11-20 30 C',
  note: null,
  ledgerEntryId: 7,
  createdAt: '2026-11-23T15:00:00Z',
  coveredLots: [L1_LOT, L2_LOT],
  realizedPnl: '0.00',
  hasAuditLog: false,
  expiredDirection: null,
  assignment: null,
  exercise: {
    exerciseDate: '2026-11-20',
    exerciseFee: '0.00',
    premium: '460.00',
    shares: 300,
    purchaseAmount: '9000.00',
    stockCost: '9460.00',
    stockCostPerShare: '31.5333',
    saleAmount: null,
    proceeds: null,
    soldCost: null,
    stockRealizedPnl: null,
    soldLots: null,
    stockTradeId: 2,
  },
}

/** AC 4: early exercise of 2 contracts on 2026-11-18 with a 5.00 fee. */
const EARLY_CALL_EXERCISE: OptionTrade = {
  ...CALL_EXERCISE,
  id: 6,
  contracts: 2,
  tradedAt: '2026-11-18T21:00:00Z',
  tradedAtLocal: '2026-11-18T16:00:00',
  cashChange: '-6005.00',
  coveredLots: [L1_LOT],
  exercise: {
    ...(CALL_EXERCISE.exercise as NonNullable<OptionTrade['exercise']>),
    exerciseDate: '2026-11-18',
    exerciseFee: '5.00',
    premium: '300.00',
    shares: 200,
    purchaseAmount: '6000.00',
    stockCost: '6305.00',
    stockCostPerShare: '31.5250',
  },
}

/** Contract put example (AC 3). */
const PUT_EXERCISE: OptionTrade = {
  ...CALL_EXERCISE,
  symbol: 'XYZ',
  strike: '45',
  optionType: 'PUT',
  contracts: 1,
  cashChange: '4500.00',
  contractLabel: 'XYZ 2026-11-20 45 P',
  coveredLots: [L3_LOT],
  exercise: {
    exerciseDate: '2026-11-20',
    exerciseFee: '0.00',
    premium: '120.00',
    shares: 100,
    purchaseAmount: null,
    stockCost: null,
    stockCostPerShare: null,
    saleAmount: '4500.00',
    proceeds: '4380.00',
    soldCost: '4000.00',
    stockRealizedPnl: '380.00',
    soldLots: [
      {
        tradeId: 1,
        type: 'BUY',
        tradedAt: '2026-11-03T15:00:00Z',
        tradedAtLocal: '2026-11-03T10:00:00',
        tradeDate: null,
        shares: 100,
        cost: '4000.00',
      },
    ],
    stockTradeId: 2,
  },
}

function errorBody(
  code: string,
  status: number,
  message: string,
  path: string,
  details: ApiErrorResponse['details'] = [],
): ApiErrorResponse {
  return { code, message, status, path, timestamp: '2026-11-23T15:00:00Z', details }
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

const EXERCISE_URL = '/api/option-trades/exercises'

async function renderPositions(
  positions: OptionPosition[],
  ...handlers: Handler[]
): Promise<Recorded[]> {
  const requests = startFakeServer(({ method, url }) => {
    if (method === 'GET' && url === '/api/option-positions') {
      return jsonResponse(positionsResponse(positions))
    }
    return undefined
  }, ...handlers)
  renderWithProviders(<PositionsPage />)
  await screen.findAllByTestId('position-row')
  return requests
}

async function openExerciseDialog(contract: string, post: Handler): Promise<Recorded[]> {
  const requests = await renderPositions([QRTV_30_C, XYZ_45_P, XYZ_50_C_SHORT], post)
  await userEvent.click(within(rowOf(contract)).getByTestId('position-exercise'))
  await screen.findByTestId('exercise-modal')
  return requests
}

/** data-testids of the row's action buttons, in order. */
/** Action buttons of a row, after checking it starts with exactly the two FEAT-016 quote buttons. */
function buttonsOf(contract: string): string[] {
  const ids = Array.from(rowOf(contract).querySelectorAll('button[data-testid^="position-"]'))
    .map((button) => button.getAttribute('data-testid') ?? '')
    .filter((id) => id !== 'position-expand')
  expect(ids.slice(0, 2)).toEqual(['position-underlying-price-set', 'position-mark-set'])
  return ids.slice(2)
}

// ---------------------------------------------------------------------------

describe('FEAT-014 行权 button on the positions page (AC 1, Q81)', () => {
  it('shows 行权 next to 平仓 before expiry, alone while 到期待确认, next to 作废 afterwards', async () => {
    const before: OptionPosition = {
      ...QRTV_30_C,
      contractLabel: 'QRTV 2026-11-20 30 C',
      closable: true,
      expirable: false,
      dte: 3,
      expirationStatus: 'ACTIVE',
    }
    const pending: OptionPosition = {
      ...XYZ_45_P,
      closable: false,
      expiryPending: true,
      expirable: false,
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
    }
    const after: OptionPosition = {
      ...QRTV_30_C,
      contractLabel: 'QRTV 2026-11-20 31 C',
      strike: '31',
    }
    await renderPositions([before, pending, after, XYZ_50_C_SHORT])
    expect(buttonsOf('QRTV 2026-11-20 30 C')).toEqual(['position-close', 'position-exercise'])
    expect(buttonsOf('XYZ 2026-11-20 45 P')).toEqual(['position-exercise'])
    expect(buttonsOf('QRTV 2026-11-20 31 C')).toEqual(['position-expire', 'position-exercise'])
    // S1 (short call) has no 行权.
    expect(
      within(rowOf('XYZ 2026-12-18 50 C')).queryByTestId('position-exercise'),
    ).not.toBeInTheDocument()
    expect(within(rowOf('XYZ 2026-12-18 50 C')).getByTestId('position-assign')).toBeInTheDocument()
  })

  it('treats exercisable as false when the backend does not send it', async () => {
    const withoutFlag: OptionPosition = { ...QRTV_30_C }
    delete withoutFlag.exercisable
    await renderPositions([withoutFlag])
    expect(buttonsOf('QRTV 2026-11-20 30 C')).toEqual(['position-expire'])
  })

  it('does not take a long put row with availableShares for a short call (coverage cell)', async () => {
    expect(coverageTags(XYZ_45_P)).toEqual([])
    await renderPositions([XYZ_45_P])
    const row = rowOf('XYZ 2026-11-20 45 P')
    expect(within(row).getByTestId('position-cell-frozen')).toHaveTextContent('—')
    expect(within(row).queryByTestId('position-cell-coverage-checked')).not.toBeInTheDocument()
  })
})

describe('FEAT-014 记录行权 dialog', () => {
  it('shows the call position, available cash, choices and the call wording (AC 1)', async () => {
    await openExerciseDialog('QRTV 2026-11-20 30 C', () => undefined)
    const modal = screen.getByTestId('exercise-modal')
    const text = (id: string) => within(modal).getByTestId(id).textContent
    expect(text('exercise-contract')).toBe('QRTV 2026-11-20 30 C')
    expect(text('exercise-direction')).toBe('多头')
    expect(text('exercise-strike')).toBe('30')
    expect(text('exercise-multiplier')).toBe('100')
    expect(text('exercise-position-contracts')).toBe('3 张')
    expect(text('exercise-available-cash')).toBe('可用现金 11,510.00')
    expect(within(modal).queryByTestId('exercise-underlying-shares')).not.toBeInTheDocument()
    // Default = the last choice (all).
    expect(within(modal).getByTestId('exercise-contracts')).toHaveTextContent('3 张（300 股）')
    expect(within(modal).getByTestId('exercise-date')).toHaveValue('2026-11-20')
    expect(within(modal).getByTestId('exercise-date')).toHaveAttribute('max', '2026-11-20')
    expect(within(modal).getByTestId('exercise-fee')).toHaveValue('0.00')
    expect(within(modal).getByText('券商收取的行权手续费')).toBeInTheDocument()
    expect(text('exercise-explanation')).toContain(
      '股票成本 = 买股金额 + 被行权批次的权利金 + 行权费',
    )
    expect(text('exercise-explanation')).not.toContain('被 call 覆盖')

    // Both choices are offered as "N 张（M 股）".
    await userEvent.click(within(modal).getByRole('combobox'))
    expect(await screen.findByTestId('exercise-contracts-option-2')).toHaveTextContent(
      '2 张（200 股）',
    )
    expect(screen.getAllByTestId('exercise-contracts-option-3').at(-1)).toHaveTextContent(
      '3 张（300 股）',
    )
  })

  it('shows the put shares and the put wording (AC 1)', async () => {
    await openExerciseDialog('XYZ 2026-11-20 45 P', () => undefined)
    const modal = screen.getByTestId('exercise-modal')
    expect(within(modal).getByTestId('exercise-underlying-shares')).toHaveTextContent(
      '当前持股 200 股，可用 100 股',
    )
    expect(within(modal).queryByTestId('exercise-available-cash')).not.toBeInTheDocument()
    expect(within(modal).getByTestId('exercise-contracts')).toHaveTextContent('1 张（100 股）')
    const explanation = within(modal).getByTestId('exercise-explanation').textContent
    expect(explanation).toContain('卖出实收 = 行权金额 − 被行权批次的权利金 − 行权费')
    expect(explanation).toContain('被 call 覆盖的股票不能卖出')
  })

  it('submits a call and shows the backend result (AC 2)', async () => {
    const requests = await openExerciseDialog('QRTV 2026-11-20 30 C', ({ method, url }) =>
      method === 'POST' && url === EXERCISE_URL ? jsonResponse(CALL_EXERCISE, 201) : undefined,
    )
    const modal = screen.getByTestId('exercise-modal')
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    await within(modal).findByTestId('exercise-result')

    expect(requests.find((r) => r.method === 'POST' && r.url === EXERCISE_URL)?.body).toEqual({
      symbol: 'QRTV',
      expirationDate: '2026-11-20',
      strike: '30',
      optionType: 'CALL',
      contracts: 3,
      exerciseDate: '2026-11-20',
      exerciseFee: '0.00',
      note: null,
    })
    const text = (id: string) => within(modal).getByTestId(id).textContent
    expect(text('exercise-result-time')).toBe('2026-11-20 16:00（美东）')
    expect(text('exercise-result-premium')).toBe('460.00')
    expect(text('exercise-result-shares')).toBe('300 股 QRTV')
    expect(text('exercise-result-purchase')).toBe('9,000.00')
    expect(text('exercise-result-fee')).toBe('0.00')
    expect(text('exercise-result-stock-cost')).toBe('9,460.00')
    expect(text('exercise-result-cost-per-share')).toBe('31.5333')
    expect(text('exercise-result-cash-change')).toBe('−9,000.00')
    expect(within(modal).getByTestId('exercise-result-cash-change').style.color).not.toBe('')
    expect(text('exercise-result-pnl')).toBe('0.00')
    expect(text('exercise-result-stock-trade')).toBe('行权买入 #2')
    expect(within(modal).queryByTestId('exercise-result-proceeds')).not.toBeInTheDocument()
    expect(within(modal).queryByTestId('exercise-result-sold-lots')).not.toBeInTheDocument()
    const lots = within(modal).getByTestId('exercise-result-lots')
    expect(lots).toHaveTextContent('被行权的开仓批次（FIFO）')
    expect(lots).toHaveTextContent('-300.00'.replace('-', '−'))
  })

  it('submits a put with fee and note and shows the delivered lots (AC 3)', async () => {
    const requests = await openExerciseDialog('XYZ 2026-11-20 45 P', ({ method, url }) =>
      method === 'POST' && url === EXERCISE_URL
        ? jsonResponse({ ...PUT_EXERCISE, note: '提前行权' }, 201)
        : undefined,
    )
    const modal = screen.getByTestId('exercise-modal')
    const fee = within(modal).getByTestId('exercise-fee')
    await userEvent.clear(fee)
    await userEvent.type(fee, '5')
    await userEvent.type(within(modal).getByTestId('exercise-note'), '提前行权')
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    await within(modal).findByTestId('exercise-result')

    expect(requests.find((r) => r.method === 'POST' && r.url === EXERCISE_URL)?.body).toEqual({
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      strike: '45',
      optionType: 'PUT',
      contracts: 1,
      exerciseDate: '2026-11-20',
      exerciseFee: '5',
      note: '提前行权',
    })
    const text = (id: string) => within(modal).getByTestId(id).textContent
    expect(text('exercise-result-shares')).toBe('100 股 XYZ')
    expect(text('exercise-result-sale-amount')).toBe('4,500.00')
    expect(text('exercise-result-proceeds')).toBe('4,380.00')
    expect(text('exercise-result-sold-cost')).toBe('4,000.00')
    expect(text('exercise-result-stock-pnl')).toBe('+380.00')
    expect(text('exercise-result-cash-change')).toBe('+4,500.00')
    expect(text('exercise-result-stock-trade')).toBe('行权卖出 #2')
    expect(text('exercise-result-note')).toBe('提前行权')
    expect(within(modal).queryByTestId('exercise-result-stock-cost')).not.toBeInTheDocument()
    const soldLots = within(modal).getByTestId('exercise-result-sold-lots')
    const rows = within(soldLots).getAllByTestId('sold-lot-row')
    expect(rows).toHaveLength(1)
    expect(within(rows[0] as HTMLElement).getByTestId('sold-lot-source')).toHaveTextContent(
      '买入 #1',
    )

    await userEvent.click(within(modal).getByTestId('exercise-done'))
    expect(screen.queryByTestId('exercise-modal')).not.toBeInTheDocument()
  })

  it.each([
    ['EXERCISE_SHORT_POSITION', '空头持仓不能行权（被行权请用"被指派"）'],
    ['CLOSE_NOT_WHOLE_LOTS', '必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张'],
    ['EXERCISE_DATE_INVALID', '行权日期为今天时，美东 16:00 之后才能记录'],
    ['EXERCISE_DATE_INVALID', '行权日期不能晚于到期日（2026-11-20）'],
    [
      'NO_POSITION_TO_EXERCISE',
      '2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）',
    ],
    ['CASH_INSUFFICIENT', '可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00'],
    [
      'SHARES_INSUFFICIENT',
      '持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）',
    ],
    [
      'COVERAGE_INSUFFICIENT',
      '行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call',
    ],
    ['EXERCISE_PROCEEDS_NOT_POSITIVE', '行权后股票卖出实收将为 −5.00，请检查行权费和被行权批次的开仓净额'],
  ])('shows a %s rejection verbatim and keeps the dialog (AC 4–6)', async (code, message) => {
    await openExerciseDialog('QRTV 2026-11-20 30 C', ({ method, url }) =>
      method === 'POST' && url === EXERCISE_URL
        ? jsonResponse(errorBody(code, 422, message, url), 422)
        : undefined,
    )
    const modal = screen.getByTestId('exercise-modal')
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    const error = await within(modal).findByTestId('exercise-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(within(modal).queryByTestId('exercise-result')).not.toBeInTheDocument()
    expect(within(modal).getByTestId('exercise-date')).toHaveValue('2026-11-20')
  })

  it('shows backend field errors under the fields (AC 10)', async () => {
    await openExerciseDialog('QRTV 2026-11-20 30 C', ({ method, url }) =>
      method === 'POST' && url === EXERCISE_URL
        ? jsonResponse(
            errorBody('VALIDATION_ERROR', 400, 'Request validation failed', url, [
              { field: 'contracts', message: '张数必须是正整数' },
              { field: 'exerciseFee', message: '行权费不能为负数' },
            ]),
            400,
          )
        : undefined,
    )
    const modal = screen.getByTestId('exercise-modal')
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    expect(await within(modal).findByText('张数必须是正整数')).toBeInTheDocument()
    expect(within(modal).getByText('行权费不能为负数')).toBeInTheDocument()
  })

  it.each([
    ['-1.00', '行权费不能为负数'],
    ['1.234', '行权费最多 2 位小数'],
  ])('checks the fee %s before sending (AC 10)', async (value, message) => {
    const requests = await openExerciseDialog('QRTV 2026-11-20 30 C', () => undefined)
    const modal = screen.getByTestId('exercise-modal')
    const fee = within(modal).getByTestId('exercise-fee')
    await userEvent.clear(fee)
    await userEvent.type(fee, value)
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    expect(await within(modal).findByText(message)).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('requires the date and a note of at most 500 characters (AC 10)', async () => {
    const requests = await openExerciseDialog('QRTV 2026-11-20 30 C', () => undefined)
    const modal = screen.getByTestId('exercise-modal')
    fireEvent.change(within(modal).getByTestId('exercise-date'), { target: { value: '' } })
    fireEvent.change(within(modal).getByTestId('exercise-note'), {
      target: { value: 'x'.repeat(501) },
    })
    await userEvent.click(within(modal).getByTestId('exercise-submit'))
    expect(await within(modal).findByText('行权日期不能为空')).toBeInTheDocument()
    expect(within(modal).getByText('备注最多 500 个字符')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })
})

describe('FEAT-014 trade list', () => {
  it('shows EXR rows: 行权 tag, net 0.00 uncolored, small text, frozen "—", delete only', () => {
    renderWithProviders(
      <TradeTable
        trades={[CALL_EXERCISE, PUT_EXERCISE, EARLY_CALL_EXERCISE]}
        loading={false}
        empty={null}
        onContractClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const [call, put, early] = screen.getAllByTestId('trade-row') as [
      HTMLElement,
      HTMLElement,
      HTMLElement,
    ]
    expect(within(call).getByTestId('trade-cell-action')).toHaveTextContent('行权')
    const net = within(call).getByTestId('trade-cell-net-amount')
    expect(net).toHaveTextContent(/^0\.00$/)
    expect(net.style.color).toBe('')
    expect(within(call).getByTestId('trade-cell-exercise')).toHaveTextContent(
      '买入 300 股 QRTV，现金 −9,000.00',
    )
    expect(within(call).queryByTestId('trade-cell-assignment')).not.toBeInTheDocument()
    expect(within(call).getByTestId('trade-cell-frozen')).toHaveTextContent('—')
    expect(within(call).queryByTestId('trade-edit')).not.toBeInTheDocument()
    expect(within(call).getByTestId('trade-delete')).toBeInTheDocument()
    expect(within(put).getByTestId('trade-cell-exercise')).toHaveTextContent(
      '卖出 100 股 XYZ，现金 +4,500.00',
    )
    expect(within(early).getByTestId('trade-cell-exercise')).toHaveTextContent(
      '买入 200 股 QRTV，现金 −6,005.00，行权费 5.00',
    )
  })

  it('offers the 行权 filter and explains what deleting an EXR removes (AC 7)', async () => {
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url.startsWith('/api/option-trades?')) {
        const page: OptionTradePage = {
          items: [CALL_EXERCISE],
          page: 1,
          size: 20,
          totalItems: 1,
          totalPages: 1,
        }
        return jsonResponse(page)
      }
      return undefined
    })
    renderWithProviders(<TradeListPage />, { route: '/trades?action=EXR' })
    const row = await screen.findByTestId('trade-row')
    expect(screen.getByTestId('trade-filter-action-exr').closest('label')).toHaveTextContent('行权')
    expect(screen.getByTestId('trade-filter-action-exr')).toBeChecked()
    expect(requests.some((r) => r.method === 'GET' && r.url.includes('action=EXR'))).toBe(true)

    await userEvent.click(within(row).getByTestId('trade-delete'))
    const modal = await screen.findByTestId('trade-delete-modal')
    expect(modal).toHaveTextContent('删除行权 #5')
    expect(within(modal).getByTestId('trade-delete-summary')).toHaveTextContent(
      '行权 3 张 QRTV 2026-11-20 30 C，2026-11-20 16:00，买入 300 股 QRTV',
    )
    expect(within(modal).getByTestId('trade-delete-warning')).toHaveTextContent(
      '行权买入的股票一起删除',
    )
  })

  it('labels EXR and keeps it out of editing; exerciseRowText is null for other records', () => {
    expect(tradeActionLabel('EXR')).toBe('行权')
    expect(isDeleteOnlyAction('EXR')).toBe(true)
    expect(TRADE_ACTIONS).toContain('EXR')
    expect(CLOSE_ACTIONS).toEqual(['BTC', 'STC', 'EXP', 'ASG', 'EXR'])
    expect(exerciseRowText({ ...CALL_EXERCISE, exercise: null })).toBeNull()
    expect(exerciseRowText({ ...CALL_EXERCISE, exercise: undefined })).toBeNull()
  })
})

describe('FEAT-014 closed trades (Q114)', () => {
  const CLOSED_CALL_EXR: ClosedOptionRecord = {
    type: 'OPTION',
    id: 5,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'QRTV',
    expirationDate: '2026-11-20',
    strike: '30',
    optionType: 'CALL',
    contractLabel: 'QRTV 2026-11-20 30 C',
    action: 'EXR',
    closedDirection: 'LONG',
    contracts: 3,
    multiplier: 100,
    shares: null,
    openingNetTotal: '-460.00',
    openingNetPerContract: '-153.33',
    costPerShare: null,
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    pricePerShare: null,
    realizedPnl: '0.00',
    note: null,
    coveredLots: [L1_LOT, L2_LOT],
    soldLots: null,
    assignment: null,
    exercise: { shares: 300, stockCost: '9460.00', proceeds: null, stockTradeId: 2 },
    exerciseTradeId: null,
    hasAuditLog: false,
  }
  const CLOSED_PUT_EXR: ClosedOptionRecord = {
    ...CLOSED_CALL_EXR,
    id: 6,
    symbol: 'XYZ',
    strike: '45',
    optionType: 'PUT',
    contractLabel: 'XYZ 2026-11-20 45 P',
    contracts: 1,
    openingNetTotal: '-120.00',
    openingNetPerContract: '-120.00',
    coveredLots: [L3_LOT],
    exercise: { shares: 100, stockCost: null, proceeds: '4380.00', stockTradeId: 3 },
  }
  const CLOSED_EXERCISE_SELL: ClosedStockRecord = {
    type: 'STOCK',
    id: 3,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'XYZ',
    expirationDate: null,
    strike: null,
    optionType: null,
    contractLabel: null,
    action: 'EXERCISE_SELL',
    closedDirection: null,
    contracts: null,
    multiplier: null,
    shares: 100,
    openingNetTotal: '-4000.00',
    openingNetPerContract: null,
    costPerShare: '40.0000',
    closeNetAmount: '4380.00',
    closeNetPerContract: null,
    pricePerShare: '43.8000',
    realizedPnl: '380.00',
    note: null,
    coveredLots: null,
    soldLots: [
      {
        tradeId: 1,
        type: 'BUY',
        tradedAt: '2026-11-03T15:00:00Z',
        tradedAtLocal: '2026-11-03T10:00:00',
        tradeDate: null,
        shares: 100,
        netAmount: '-4000.00',
      },
    ],
    assignment: null,
    assignmentTradeId: null,
    exercise: null,
    exerciseTradeId: 6,
    hasAuditLog: false,
  }

  it('shows the 行权卖出 stock row above the put 行权 row, and a call 行权 row, with links (AC 2, 3)', () => {
    renderWithProviders(
      <ClosedTradeTable
        trades={[CLOSED_EXERCISE_SELL, CLOSED_PUT_EXR, CLOSED_CALL_EXR]}
        loading={false}
        empty={null}
      />,
    )
    const [stock, put, call] = screen.getAllByTestId('closed-row') as [
      HTMLElement,
      HTMLElement,
      HTMLElement,
    ]

    expect(within(stock).getByTestId('closed-cell-type')).toHaveTextContent('股票')
    expect(within(stock).getByTestId('closed-cell-action')).toHaveTextContent('行权卖出')
    expect(within(stock).getByTestId('closed-cell-pnl')).toHaveTextContent('+380.00')
    const back = within(stock).getByTestId('closed-stock-exercise-link')
    expect(back).toHaveTextContent('来自行权 #6')
    expect(back).toHaveAttribute(
      'href',
      '/trades?symbol=XYZ&startDate=2026-11-20&endDate=2026-11-20&action=EXR',
    )
    expect(within(stock).queryByTestId('closed-stock-assignment-link')).not.toBeInTheDocument()

    expect(within(put).getByTestId('closed-cell-action')).toHaveTextContent('行权')
    expect(within(put).getByTestId('closed-cell-direction')).toHaveTextContent('多头')
    expect(within(put).getByTestId('closed-cell-pnl')).toHaveTextContent(/^0\.00$/)
    expect(within(put).getByTestId('closed-cell-exercise')).toHaveTextContent(
      '权利金已从股票卖出实收中扣除（100 股 XYZ，实收 4,380.00）',
    )
    expect(within(put).getByTestId('closed-exercise-stock-link')).toHaveAttribute(
      'href',
      '/stocks?symbol=XYZ&type=EXERCISE_SELL&startDate=2026-11-20&endDate=2026-11-20',
    )

    expect(within(call).getByTestId('closed-cell-exercise')).toHaveTextContent(
      '权利金已计入股票成本（300 股 QRTV，成本 9,460.00）',
    )
    const stockLink = within(call).getByTestId('closed-exercise-stock-link')
    expect(stockLink).toHaveTextContent('股票记录 #2')
    expect(stockLink).toHaveAttribute(
      'href',
      '/stocks?symbol=QRTV&type=EXERCISE_BUY&startDate=2026-11-20&endDate=2026-11-20',
    )
    expect(within(call).queryByTestId('closed-cell-assignment')).not.toBeInTheDocument()
  })
})

describe('FEAT-014 股票 page', () => {
  const EXERCISE_BUY = stockTrade({
    id: 2,
    type: 'EXERCISE_BUY',
    symbol: 'QRTV',
    shares: 300,
    amount: '9460.00',
    cashChange: '0.00',
    pricePerShare: '31.5333',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    ledgerEntryId: null,
    exerciseTradeId: 5,
    editable: false,
    deletable: false,
  })
  const EXERCISE_SELL = stockTrade({
    id: 3,
    type: 'EXERCISE_SELL',
    symbol: 'XYZ',
    shares: 100,
    amount: '4380.00',
    cashChange: '0.00',
    pricePerShare: '43.8000',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    ledgerEntryId: null,
    soldCost: '4000.00',
    realizedPnl: '380.00',
    soldLots: PUT_EXERCISE.exercise?.soldLots ?? null,
    exerciseTradeId: 6,
    editable: false,
    deletable: false,
  })

  it('shows 行权买入 / 行权卖出 with cost / proceeds, links back, a hint and no 修改 / 删除', async () => {
    renderWithProviders(
      <StockTradeTable
        trades={[EXERCISE_SELL, EXERCISE_BUY]}
        loading={false}
        empty={null}
        onSymbolClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const [sell, buy] = screen.getAllByTestId('stock-row') as [HTMLElement, HTMLElement]
    const cell = (row: HTMLElement, id: string) => within(row).getByTestId(id)

    expect(buy).toHaveAttribute('data-stock-type', 'EXERCISE_BUY')
    expect(cell(buy, 'stock-cell-type')).toHaveTextContent('行权买入')
    expect(cell(buy, 'stock-cell-amount')).toHaveTextContent('成本 9,460.00')
    expect(cell(buy, 'stock-cell-amount').style.color).toBe('')
    expect(cell(buy, 'stock-cell-price')).toHaveTextContent('每股 31.5333')
    expect(cell(buy, 'stock-cell-pnl')).toHaveTextContent('—')
    expect(cell(buy, 'stock-exercise-link')).toHaveTextContent('来自行权 #5')
    expect(cell(buy, 'stock-exercise-link')).toHaveAttribute(
      'href',
      '/trades?symbol=QRTV&startDate=2026-11-20&endDate=2026-11-20&action=EXR',
    )
    expect(cell(buy, 'stock-exercise-hint')).toHaveTextContent('由行权生成，在交易列表删除')
    expect(within(buy).queryByTestId('stock-assignment-link')).not.toBeInTheDocument()
    expect(within(buy).queryByTestId('stock-assign-buy-hint')).not.toBeInTheDocument()
    expect(within(buy).queryByTestId('stock-edit')).not.toBeInTheDocument()
    expect(within(buy).queryByTestId('stock-delete')).not.toBeInTheDocument()
    expect(within(buy).queryByTestId('stock-expand')).not.toBeInTheDocument()

    expect(cell(sell, 'stock-cell-type')).toHaveTextContent('行权卖出')
    expect(cell(sell, 'stock-cell-amount')).toHaveTextContent('实收 4,380.00')
    expect(cell(sell, 'stock-cell-pnl')).toHaveTextContent('+380.00')
    expect(cell(sell, 'stock-exercise-link')).toHaveTextContent('来自行权 #6')
    expect(within(sell).queryByTestId('stock-edit')).not.toBeInTheDocument()
    expect(within(sell).queryByTestId('stock-delete')).not.toBeInTheDocument()

    await userEvent.click(cell(sell, 'stock-expand'))
    const detail = await screen.findByTestId('stock-sold-detail')
    expect(within(detail).getAllByTestId('sold-lot-row')).toHaveLength(1)
    expect(within(detail).getByTestId('stock-sold-cost')).toHaveTextContent('4,000.00')
  })

  it('offers 行权买入 / 行权卖出 in the type filter but never in the entry form', () => {
    renderWithProviders(
      <StockFilterForm
        initialValues={EMPTY_STOCK_FILTERS}
        errors={{}}
        onApply={noop}
        onClear={noop}
      />,
    )
    expect(
      screen.getByTestId('stock-filter-type-exercise-buy').closest('label'),
    ).toHaveTextContent('行权买入')
    expect(
      screen.getByTestId('stock-filter-type-exercise-sell').closest('label'),
    ).toHaveTextContent('行权卖出')
    expect(STOCK_RECORD_TYPES).toEqual([
      'BUY',
      'SELL',
      'TRANSFER_IN',
      'ASSIGN_BUY',
      'ASSIGN_SELL',
      'EXERCISE_BUY',
      'EXERCISE_SELL',
    ])
    expect(STOCK_TRADE_TYPES).not.toContain('EXERCISE_BUY')
    expect(isStockTradeType('EXERCISE_SELL')).toBe(false)
  })

  it('labels and summarizes the exercise records and builds their 股票 page URL', () => {
    expect(stockTypeLabel('EXERCISE_BUY')).toBe('行权买入')
    expect(stockTypeLabel('EXERCISE_SELL')).toBe('行权卖出')
    expect(stockTradeSummary(EXERCISE_BUY)).toBe(
      '行权买入 300 股 QRTV，2026-11-20 16:00，成本 9,460.00',
    )
    expect(stockTradeSummary(EXERCISE_SELL)).toBe(
      '行权卖出 100 股 XYZ，2026-11-20 16:00，实收 4,380.00',
    )
    expect(stocksUrlForAssignment('QRTV', '2026-11-20T16:00:00', 'EXERCISE_BUY')).toBe(
      '/stocks?symbol=QRTV&type=EXERCISE_BUY&startDate=2026-11-20&endDate=2026-11-20',
    )
  })
})

describe('FEAT-014 cash ledger and audit', () => {
  it('shows an OPTION_EXERCISE row as 行权 with the backend description and a trade list link', async () => {
    const entry: CashLedgerEntry = {
      id: 7,
      occurredAt: '2026-11-20T21:00:00Z',
      sourceType: 'OPTION_EXERCISE',
      sourceId: 5,
      amount: '-9000.00',
      balanceAfter: '2510.00',
      frozenChange: '0.00',
      description: '行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV',
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
    expect(within(row).getByTestId('cash-ledger-type')).toHaveTextContent(/^行权$/)
    expect(within(row).getByTestId('cash-ledger-amount')).toHaveTextContent('−9,000.00')
    expect(row).toHaveTextContent('行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV')
    expect(within(row).getByTestId('cash-ledger-trades-link')).toHaveAttribute('href', '/trades')
    expect(within(row).queryByTestId('cash-ledger-edit')).not.toBeInTheDocument()
  })

  it('shows the exercise fields of a deleted EXR and side effects (AC 7, 8)', async () => {
    const base = {
      symbol: 'QRTV',
      contractLabel: 'QRTV 2026-11-20 30 C',
      action: 'EXR',
      multiplier: 100,
      netAmount: '0.00',
      tradedAtLocal: '2026-11-20T16:00:00',
      note: null,
      frozenChange: '0.00',
      exerciseDate: '2026-11-20',
      exerciseFee: '0.00',
    }
    const deletedCall: AuditRecord = {
      id: 9,
      operatedAt: '2026-11-23T15:10:00Z',
      operation: 'DELETE',
      objectType: 'OPTION_TRADE',
      objectId: 5,
      summary: '行权 QRTV 2026-11-20 30 C 3 张（买入 300 股 QRTV）',
      before: {
        ...base,
        id: 5,
        optionType: 'CALL',
        contracts: 3,
        cashChange: '-9000.00',
        shares: 300,
        premium: '460.00',
        purchaseAmount: '9000.00',
        stockCost: '9460.00',
        stockTradeId: 2,
      },
      after: null,
      changes: [],
      sideEffects: [],
    }
    const deletedPut: AuditRecord = {
      ...deletedCall,
      id: 10,
      objectId: 6,
      summary: '行权 XYZ 2026-11-20 45 P 1 张（卖出 100 股 XYZ）',
      before: {
        ...base,
        id: 6,
        symbol: 'XYZ',
        contractLabel: 'XYZ 2026-11-20 45 P',
        optionType: 'PUT',
        contracts: 1,
        cashChange: '4500.00',
        shares: 100,
        premium: '120.00',
        saleAmount: '4500.00',
        proceeds: '4380.00',
        soldCost: '4000.00',
        stockRealizedPnl: '380.00',
        stockTradeId: 3,
      },
    }
    const edited: AuditRecord = {
      id: 11,
      operatedAt: '2026-11-23T15:20:00Z',
      operation: 'UPDATE',
      objectType: 'OPTION_TRADE',
      objectId: 2,
      summary: '修改 BTO QRTV 2026-11-20 30 C',
      before: null,
      after: null,
      changes: [],
      sideEffects: [
        {
          tradeId: 5,
          action: 'EXR',
          contractLabel: 'QRTV 2026-11-20 30 C',
          field: 'stockCost',
          before: '9460.00',
          after: '9470.00',
          text: '行权 #5 股票成本 9,460.00 → 9,470.00',
        },
      ],
    }
    renderWithProviders(
      <AuditTable records={[deletedCall, deletedPut, edited]} loading={false} empty={null} />,
    )
    expect(screen.getAllByTestId('audit-cell-side-effects')[2]).toHaveTextContent(
      '行权 #5 股票成本 9,460.00 → 9,470.00',
    )

    await userEvent.click(screen.getAllByTestId('audit-expand')[0] as HTMLElement)
    const callDetail = await screen.findByTestId('audit-detail')
    const callValue = (key: string) =>
      within(callDetail).getByTestId(`audit-before-${key}`).textContent
    expect(callValue('action')).toContain('行权')
    expect(callValue('exerciseDate')).toBe('2026-11-20')
    expect(callValue('exerciseFee')).toBe('0.00')
    expect(callValue('premium')).toBe('460.00')
    expect(callValue('purchaseAmount')).toBe('9,000.00')
    expect(callValue('stockCost')).toBe('9,460.00')
    expect(callValue('stockTradeId')).toBe('2')
    expect(within(callDetail).queryByTestId('audit-before-proceeds')).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByTestId('audit-expand')[1] as HTMLElement)
    const details = await screen.findAllByTestId('audit-detail')
    expect(details).toHaveLength(2)
    const putDetail = details[1] as HTMLElement
    const putValue = (key: string) =>
      within(putDetail).getByTestId(`audit-before-${key}`).textContent
    expect(putValue('saleAmount')).toBe('4,500.00')
    expect(putValue('proceeds')).toBe('4,380.00')
    expect(putValue('soldCost')).toBe('4,000.00')
    expect(putValue('stockRealizedPnl')).toBe('+380.00')
    expect(within(putDetail).queryByTestId('audit-before-stockCost')).not.toBeInTheDocument()
  })
})
