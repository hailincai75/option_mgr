import { screen, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import { ACCOUNT_STORAGE_KEY } from '../account/accountStorage'
import type {
  Account,
  OptionPosition,
  OptionPositionLot,
  StockPosition,
  StockPositionLot,
  UnrealizedFields,
  UnrealizedSummaryResponse,
} from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-048 scenario (today 2026-11-16, 10:00 fetch OK; fictional ticker ZZQ, multiplier 100),
// with the numbers of the contract examples in docs/api/FEAT-048.md. The page only shows them.

const OK = { unrealizedStatus: 'OK', unrealizedLabel: null } as const

function values(marketValue: string, unrealizedPnl: string, percent: string | null) {
  return { marketValue, unrealizedPnl, unrealizedPnlPercent: percent, ...OK }
}

const NO_QUOTE: UnrealizedFields = {
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'NO_QUOTE',
  unrealizedLabel: '无报价',
}
const EXPIRED_PENDING: UnrealizedFields = {
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'EXPIRED_PENDING',
  unrealizedLabel: '已到期，待处理',
}

function lot(
  tradeId: number,
  action: string,
  contracts: number,
  net: string,
  unrealized: UnrealizedFields,
): OptionPositionLot {
  return {
    tradeId,
    action,
    tradedAt: '2026-11-10T15:00:00Z',
    tradedAtLocal: '2026-11-10T10:00:00',
    originalContracts: contracts,
    remainingContracts: contracts,
    originalNetAmount: net,
    remainingNetAmount: net,
    originalFrozen: '0.00',
    remainingFrozen: '0.00',
    note: null,
    ...unrealized,
  }
}

function option(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    accountId: 1,
    accountName: '甲账户',
    symbol: 'ZZQ',
    expirationDate: '2026-12-18',
    strike: '9',
    optionType: 'CALL',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 2,
    multiplier: 100,
    openingNetTotal: '0.00',
    averageNetPerContract: '0.00',
    frozenCash: '0.00',
    dte: 32,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: partial.lots?.length ?? 0,
    closeAction: partial.direction === 'LONG' ? 'STC' : 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: partial.contracts ?? 2,
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

/** C: short 9C, lots A / B (AC 1). */
const C = option({
  openingNetTotal: '55.68',
  ...values('-40.00', '15.68', '28.16'),
  lots: [
    lot(1, 'STO', 1, '18.34', values('-20.00', '-1.66', '-9.05')),
    lot(2, 'STO', 1, '37.34', values('-20.00', '17.34', '46.44')),
  ],
})
/** P: long 8P, lots L1 / L2 (AC 2). */
const P = option({
  strike: '8',
  optionType: 'PUT',
  direction: 'LONG',
  contracts: 3,
  openingNetTotal: '-66.95',
  ...values('51.00', '-15.95', '-23.82'),
  lots: [
    lot(3, 'BTO', 1, '-25.65', values('17.00', '-8.65', '-33.72')),
    lot(4, 'BTO', 2, '-41.30', values('34.00', '-7.30', '-17.68')),
  ],
})
/** AC 6: never quoted / expired, not processed. */
const P7 = option({
  strike: '7',
  optionType: 'PUT',
  contracts: 1,
  openingNetTotal: '12.00',
  ...NO_QUOTE,
  lots: [lot(5, 'STO', 1, '12.00', NO_QUOTE)],
})
const P8_EXPIRED = option({
  expirationDate: '2026-11-13',
  strike: '8',
  optionType: 'PUT',
  contracts: 1,
  openingNetTotal: '15.00',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  closable: false,
  expirable: true,
  ...EXPIRED_PENDING,
  lots: [lot(6, 'STO', 1, '15.00', EXPIRED_PENDING)],
})

function stockLot(
  tradeId: number,
  shares: number,
  cost: string,
  unrealized: UnrealizedFields,
): StockPositionLot {
  return {
    tradeId,
    type: 'BUY',
    tradedAt: '2026-11-03T15:00:00Z',
    tradedAtLocal: '2026-11-03T10:00:00',
    tradeDate: null,
    originalShares: shares,
    remainingShares: shares,
    originalCost: cost,
    remainingCost: cost,
    note: null,
    ...unrealized,
  }
}

function stock(partial: Partial<StockPosition>): StockPosition {
  return {
    accountId: 1,
    accountName: '甲账户',
    symbol: 'ZZQ',
    shares: 200,
    coveredShares: 0,
    availableShares: 200,
    totalCost: '0.00',
    averageCost: '0.0000',
    lotCount: partial.lots?.length ?? 0,
    lots: [],
    ...partial,
  }
}

/** AC 3: 甲账户 at 8.65. */
const STOCK_A = stock({
  totalCost: '1730.65',
  averageCost: '8.6533',
  ...values('1730.00', '-0.65', '-0.04'),
  lots: [
    stockLot(11, 100, '950.00', values('865.00', '-85.00', '-8.95')),
    stockLot(12, 100, '780.65', values('865.00', '84.35', '10.81')),
  ],
})
/** AC 9: 乙账户 at 8.65 (in 全部账户), plus a 0-cost lot (percent null → —). */
const STOCK_B = stock({
  accountId: 2,
  accountName: '乙账户',
  totalCost: '1740.00',
  averageCost: '8.7000',
  ...values('1730.00', '-10.00', '-0.57'),
  lots: [
    stockLot(13, 101, '880.00', values('873.65', '-6.35', '-0.72')),
    stockLot(14, 99, '860.00', values('856.35', '-3.65', '-0.42')),
  ],
})
const STOCK_NO_PRICE = stock({
  symbol: 'ZZR',
  shares: 10,
  totalCost: '0.00',
  ...NO_QUOTE,
  lots: [stockLot(15, 10, '0.00', NO_QUOTE)],
})

const ACCOUNTS: Account[] = [
  { id: 1, name: '甲账户', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '乙账户', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

const ZERO_GROUP = {
  marketValue: '0.00',
  unrealizedPnl: '0.00',
  includedCount: 0,
  noQuoteCount: 0,
  expiredPendingCount: 0,
}
const SUMMARY: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-16',
  option: ZERO_GROUP,
  stock: ZERO_GROUP,
  total: ZERO_GROUP,
  excludedLabel: null,
}

interface Data {
  options: OptionPosition[]
  stocks: StockPosition[]
}

/** Fake backend keyed by `X-Account-Id` (`1` or `ALL`). */
function startFakeServer(byScope: Record<string, Data>) {
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    const data = byScope[headers['X-Account-Id'] ?? '']
    if (data !== undefined) {
      if (url === '/api/option-positions') {
        return Promise.resolve(
          jsonResponse({
            asOfDate: '2026-11-16',
            positionCount: data.options.length,
            frozenTotal: '0.00',
            positions: data.options,
          }),
        )
      }
      if (url === '/api/stock-positions') {
        return Promise.resolve(
          jsonResponse({ positionCount: data.stocks.length, positions: data.stocks }),
        )
      }
      if (url === '/api/positions/unrealized-summary') return Promise.resolve(jsonResponse(SUMMARY))
    }
    if (url === '/api/adhoc-closure-alerts') return Promise.resolve(jsonResponse({ alerts: [] }))
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
}

function optionRow(contract: string, accountId = 1): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find(
      (element) =>
        element.getAttribute('data-contract') === contract &&
        element.getAttribute('data-account-id') === String(accountId),
    )
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

function stockRow(symbol: string, accountId = 1): HTMLElement {
  const row = screen
    .getAllByTestId('stock-position-row')
    .find(
      (element) =>
        element.getAttribute('data-symbol') === symbol &&
        element.getAttribute('data-account-id') === String(accountId),
    )
  if (!row) throw new Error(`stock row ${symbol} not found`)
  return row
}

async function expandOption(contract: string, accountId = 1): Promise<HTMLElement[]> {
  await userEvent.click(within(optionRow(contract, accountId)).getByTestId('position-expand'))
  const expanded = optionRow(contract, accountId).nextElementSibling
  if (!(expanded instanceof HTMLElement)) throw new Error('expanded row missing')
  return within(expanded).findAllByTestId('lot-row')
}

async function expandStock(symbol: string, accountId = 1): Promise<HTMLElement[]> {
  await userEvent.click(within(stockRow(symbol, accountId)).getByTestId('stock-position-expand'))
  const expanded = stockRow(symbol, accountId).nextElementSibling
  if (!(expanded instanceof HTMLElement)) throw new Error('expanded row missing')
  return within(expanded).findAllByTestId('stock-lot-row')
}

function at(rows: HTMLElement[], index: number): HTMLElement {
  const row = rows[index]
  if (row === undefined) throw new Error(`lot row ${index} missing`)
  return row
}

function lotTexts(row: HTMLElement, prefix: 'lot-cell' | 'stock-lot') {
  const text = (id: string) => within(row).queryByTestId(id)?.textContent ?? null
  return {
    marketValue: text(`${prefix}-market-value`),
    pnl: text(`${prefix}-unrealized-pnl`),
    percent: text(`${prefix}-unrealized-pnl-percent`),
  }
}

function headersOf(element: HTMLElement): (string | null)[] {
  const table = element.closest('table')
  if (table === null) throw new Error('table missing')
  return [...table.querySelectorAll('th')].map((th) => th.textContent)
}

describe('FEAT-048 lot unrealized P&L in the positions page lot tables', () => {
  afterEach(() => {
    window.localStorage.removeItem(ACCOUNT_STORAGE_KEY)
  })

  it('shows short-call lot values after 剩余开仓净额 with colors (AC 1)', async () => {
    startFakeServer({ '1': { options: [C, P], stocks: [STOCK_A] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const lots = await expandOption(C.contractLabel)
    const a = at(lots, 0)
    const b = at(lots, 1)
    expect(lotTexts(a, 'lot-cell')).toEqual({ marketValue: '−20.00', pnl: '−1.66', percent: '−9.05%' })
    expect(lotTexts(b, 'lot-cell')).toEqual({
      marketValue: '−20.00',
      pnl: '+17.34',
      percent: '+46.44%',
    })
    // 正绿负红, like the position row.
    expect(within(a).getByTestId('lot-cell-unrealized-pnl')).toHaveStyle({ color: '#cf1322' })
    expect(within(b).getByTestId('lot-cell-unrealized-pnl-percent')).toHaveStyle({
      color: '#389e0d',
    })
    expect(within(b).getByTestId('lot-cell-market-value')).toHaveAttribute('data-status', 'OK')

    const headers = headersOf(a)
    const net = headers.indexOf('剩余开仓净额')
    expect(net).toBeGreaterThan(-1)
    expect(headers.slice(net, net + 4)).toEqual(['剩余开仓净额', '市值', '未实现盈亏', '冻结'])
  })

  it('shows long lots with different sizes (AC 2)', async () => {
    startFakeServer({ '1': { options: [C, P], stocks: [STOCK_A] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const lots = await expandOption(P.contractLabel)
    const l1 = at(lots, 0)
    const l2 = at(lots, 1)
    expect(lotTexts(l1, 'lot-cell')).toEqual({
      marketValue: '+17.00',
      pnl: '−8.65',
      percent: '−33.72%',
    })
    expect(lotTexts(l2, 'lot-cell')).toEqual({
      marketValue: '+34.00',
      pnl: '−7.30',
      percent: '−17.68%',
    })
  })

  it('shows stock lot values after 剩余成本 (AC 3)', async () => {
    startFakeServer({ '1': { options: [C], stocks: [STOCK_A] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('stock-position-row')

    const lots = await expandStock('ZZQ')
    const s1 = at(lots, 0)
    const s2 = at(lots, 1)
    expect(lotTexts(s1, 'stock-lot')).toEqual({
      marketValue: '+865.00',
      pnl: '−85.00',
      percent: '−8.95%',
    })
    expect(lotTexts(s2, 'stock-lot')).toEqual({
      marketValue: '+865.00',
      pnl: '+84.35',
      percent: '+10.81%',
    })
    const headers = headersOf(s1)
    const cost = headers.indexOf('剩余成本')
    expect(cost).toBeGreaterThan(-1)
    expect(headers.slice(cost, cost + 4)).toEqual(['剩余成本', '市值', '未实现盈亏', '备注'])
  })

  it('shows the backend label in gray when a lot cannot be computed (AC 6)', async () => {
    startFakeServer({ '1': { options: [P7, P8_EXPIRED], stocks: [STOCK_NO_PRICE] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const noQuote = at(await expandOption(P7.contractLabel), 0)
    expect(lotTexts(noQuote, 'lot-cell')).toEqual({
      marketValue: '无报价',
      pnl: '无报价',
      percent: null,
    })
    expect(within(noQuote).getByTestId('lot-cell-unrealized-pnl')).toHaveAttribute(
      'data-status',
      'NO_QUOTE',
    )
    expect(within(noQuote).getByTestId('lot-cell-market-value')).toHaveClass(
      'ant-typography-secondary',
    )

    const expired = at(await expandOption(P8_EXPIRED.contractLabel), 0)
    expect(lotTexts(expired, 'lot-cell')).toEqual({
      marketValue: '已到期，待处理',
      pnl: '已到期，待处理',
      percent: null,
    })

    const stockLotRow = at(await expandStock('ZZR'), 0)
    expect(lotTexts(stockLotRow, 'stock-lot')).toEqual({
      marketValue: '无报价',
      pnl: '无报价',
      percent: null,
    })
  })

  it('shows lot values in 全部账户 mode, per account row (AC 9)', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'ALL')
    startFakeServer({ ALL: { options: [C], stocks: [STOCK_A, STOCK_B] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const lots = await expandOption(C.contractLabel, 1)
    const a = at(lots, 0)
    const b = at(lots, 1)
    expect(lotTexts(a, 'lot-cell').pnl).toBe('−1.66')
    expect(lotTexts(b, 'lot-cell').pnl).toBe('+17.34')

    const stockLots = await expandStock('ZZQ', 2)
    const s3 = at(stockLots, 0)
    const s4 = at(stockLots, 1)
    expect(lotTexts(s3, 'stock-lot')).toEqual({
      marketValue: '+873.65',
      pnl: '−6.35',
      percent: '−0.72%',
    })
    expect(lotTexts(s4, 'stock-lot')).toEqual({
      marketValue: '+856.35',
      pnl: '−3.65',
      percent: '−0.42%',
    })
  })

  it('shows — for a null lot percent and for fields missing from older responses', async () => {
    const zeroCost = stock({
      symbol: 'ZZS',
      shares: 10,
      ...values('86.50', '86.50', null),
      lots: [stockLot(16, 10, '0.00', values('86.50', '86.50', null))],
    })
    // A lot as returned before FEAT-048: none of the five fields.
    const older: OptionPositionLot = {
      tradeId: 7,
      action: 'STO',
      tradedAt: '2026-11-10T15:00:00Z',
      tradedAtLocal: '2026-11-10T10:00:00',
      originalContracts: 2,
      remainingContracts: 2,
      originalNetAmount: '55.68',
      remainingNetAmount: '55.68',
      originalFrozen: '0.00',
      remainingFrozen: '0.00',
      note: null,
    }
    const olderC = option({ ...values('-40.00', '15.68', '28.16'), lots: [older] })
    startFakeServer({ '1': { options: [olderC], stocks: [zeroCost] } })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('stock-position-row')

    const zero = at(await expandStock('ZZS'), 0)
    expect(lotTexts(zero, 'stock-lot')).toEqual({
      marketValue: '+86.50',
      pnl: '+86.50',
      percent: '—',
    })
    const old = at(await expandOption(olderC.contractLabel), 0)
    expect(lotTexts(old, 'lot-cell')).toEqual({ marketValue: '—', pnl: '—', percent: '—' })
  })
})
