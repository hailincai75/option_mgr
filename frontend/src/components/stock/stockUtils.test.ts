import { stockTradeListQuery } from '../../api/stockTrades'
import type { StockTrade } from '../../api/types'
import { stockAmountLabel, stockTimeText, stockTradeSummary, stockTypeLabel } from './stockLabels'
import {
  EMPTY_STOCK_FILTERS,
  hasActiveStockFilters,
  parseStockListSearch,
  stockSellUrl,
  stocksUrlForSymbol,
  toStockListParams,
  toStockListSearch,
  validateStockFilters,
} from './stockListFilters'
import {
  normalizeStockAmount,
  todayNY,
  validateShares,
  validateStockAmount,
  validateTradeDate,
} from './stockValidation'

describe('stock validation (FEAT-011 AC 11)', () => {
  it.each([
    ['', '股数不能为空'],
    ['  ', '股数不能为空'],
    ['0', '股数必须是正整数'],
    ['-5', '股数必须是正整数'],
    ['1.5', '股数必须是正整数'],
    ['abc', '股数必须是正整数'],
    ['10000001', '股数过大'],
    ['99999999999', '股数过大'],
    ['10000000', null],
    ['1', null],
    [' 150 ', null],
  ])('shares %j → %j', (input, expected) => {
    expect(validateShares(input)).toBe(expected)
  })

  it.each([
    ['', '金额不能为空'],
    ['0', '金额必须大于 0'],
    ['0.00', '金额必须大于 0'],
    ['-1.00', '金额必须大于 0'],
    ['1.234', '金额最多 2 位小数'],
    ['abc', '金额必须是大于 0 的数字'],
    ['10000000000000.00', '金额过大'],
    ['9999999999999.99', null],
    ['1,500.65', null],
    ['750', null],
  ])('amount %j → %j', (input, expected) => {
    expect(validateStockAmount(input)).toBe(expected)
  })

  it('strips thousands separators from amounts', () => {
    expect(normalizeStockAmount('1,500.65 ')).toBe('1500.65')
  })

  it('checks the transfer date against today in New York', () => {
    const now = new Date('2026-11-13T15:00:00Z') // 10:00 NY
    expect(todayNY(now)).toBe('2026-11-13')
    expect(validateTradeDate('', now)).toBe('日期不能为空')
    expect(validateTradeDate('2026-11-14', now)).toBe('日期不能晚于今天')
    expect(validateTradeDate('2026-11-13', now)).toBeNull()
    expect(validateTradeDate('2026-10-01', now)).toBeNull()
    // 23:30 NY on 11-13 is already 11-14 in UTC; today is still 11-13 in New York.
    expect(validateTradeDate('2026-11-14', new Date('2026-11-14T04:30:00Z'))).toBe(
      '日期不能晚于今天',
    )
  })
})

describe('stock labels', () => {
  it('labels types and amounts', () => {
    expect(stockTypeLabel('BUY')).toBe('买入')
    expect(stockTypeLabel('SELL')).toBe('卖出')
    expect(stockTypeLabel('TRANSFER_IN')).toBe('转入')
    expect(stockTypeLabel('OTHER')).toBe('OTHER')
    expect(stockAmountLabel('BUY')).toBe('实付总额（含手续费）')
    expect(stockAmountLabel('SELL')).toBe('实收净额（已扣手续费）')
    expect(stockAmountLabel('TRANSFER_IN')).toBe('总成本')
    expect(stockAmountLabel(undefined)).toBe('金额')
  })

  it('shows transfers by date and others by New York time', () => {
    expect(stockTimeText({ tradeDate: '2026-10-01', tradedAtLocal: '2026-10-01T00:00:00' })).toBe(
      '2026-10-01',
    )
    expect(stockTimeText({ tradeDate: null, tradedAtLocal: '2026-11-03T10:00:00' })).toBe(
      '2026-11-03 10:00',
    )
  })

  it('builds delete summaries (requirement examples)', () => {
    const base: StockTrade = {
      id: 4,
      type: 'SELL',
      symbol: 'IXQZ',
      shares: 150,
      amount: '1200.00',
      cashChange: '1200.00',
      pricePerShare: '8.0000',
      tradedAt: '2026-11-05T15:00:00Z',
      tradedAtLocal: '2026-11-05T10:00:00',
      tradeDate: null,
      note: null,
      ledgerEntryId: 4,
      createdAt: '2026-11-13T15:00:00Z',
      hasAuditLog: false,
      soldCost: '1125.16',
      realizedPnl: '74.84',
      soldLots: [],
      assignmentTradeId: null,
      editable: true,
      deletable: true,
    }
    expect(stockTradeSummary(base)).toBe('卖出 150 股 IXQZ，2026-11-05 10:00，+1,200.00')
    expect(
      stockTradeSummary({
        ...base,
        type: 'TRANSFER_IN',
        shares: 100,
        amount: '750.00',
        cashChange: '0.00',
        tradeDate: '2026-10-01',
        tradedAtLocal: '2026-10-01T00:00:00',
      }),
    ).toBe('转入 100 股 IXQZ，2026-10-01，总成本 750.00')
    expect(
      stockTradeSummary({ ...base, type: 'BUY', amount: '1500.65', cashChange: '-1500.65' }),
    ).toBe('买入 150 股 IXQZ，2026-11-05 10:00，−1,500.65')
  })
})

describe('stock list URL state (AC 3)', () => {
  it('parses and serializes filters, page and size', () => {
    const state = parseStockListSearch(
      new URLSearchParams('symbol=ixqz&type=BUY,TRANSFER_IN&startDate=2026-11-04&page=2&size=50'),
    )
    expect(state).toEqual({
      filters: {
        symbol: 'ixqz',
        type: ['BUY', 'TRANSFER_IN'],
        startDate: '2026-11-04',
        endDate: '',
      },
      page: 2,
      size: 50,
    })
    expect(toStockListSearch(state).toString()).toBe(
      'symbol=ixqz&type=BUY%2CTRANSFER_IN&startDate=2026-11-04&page=2&size=50',
    )
    expect(toStockListSearch({ filters: EMPTY_STOCK_FILTERS, page: 1, size: 20 }).toString()).toBe(
      '',
    )
  })

  it('accepts repeated type params and falls back for bad page / size', () => {
    const state = parseStockListSearch(new URLSearchParams('type=SELL&type=SELL&page=0&size=7'))
    expect(state.filters.type).toEqual(['SELL'])
    expect([state.page, state.size]).toEqual([1, 20])
  })

  it.each([
    [{ ...EMPTY_STOCK_FILTERS, symbol: '$IXQZ' }, { symbol: '标的只能包含字母、数字和点' }],
    [{ ...EMPTY_STOCK_FILTERS, symbol: 'ABCDEFGHIJK' }, { symbol: '标的最多 10 个字符' }],
    [
      { ...EMPTY_STOCK_FILTERS, type: ['SHORT'] },
      { type: '类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL' },
    ],
    [{ ...EMPTY_STOCK_FILTERS, startDate: '2026/11/04' }, { startDate: '日期格式不正确（YYYY-MM-DD）' }],
    [
      { ...EMPTY_STOCK_FILTERS, startDate: '2026-11-06', endDate: '2026-11-05' },
      { endDate: '开始日期不能晚于结束日期' },
    ],
    [{ ...EMPTY_STOCK_FILTERS, symbol: 'BRK.B', endDate: '2026-10-31' }, {}],
  ])('validates %j', (filters, errors) => {
    expect(validateStockFilters(filters)).toEqual(errors)
  })

  it('builds API params and query strings with repeated type', () => {
    const params = toStockListParams({
      filters: { symbol: ' ixqz ', type: ['BUY', 'TRANSFER_IN'], startDate: '', endDate: '2026-10-31' },
      page: 1,
      size: 20,
    })
    expect(params).toEqual({
      symbol: 'ixqz',
      type: ['BUY', 'TRANSFER_IN'],
      startDate: undefined,
      endDate: '2026-10-31',
      page: 1,
      size: 20,
    })
    expect(stockTradeListQuery(params)).toBe(
      'symbol=ixqz&endDate=2026-10-31&type=BUY&type=TRANSFER_IN&page=1&size=20',
    )
  })

  it('knows when filters are active and builds links', () => {
    expect(hasActiveStockFilters(EMPTY_STOCK_FILTERS)).toBe(false)
    expect(hasActiveStockFilters({ ...EMPTY_STOCK_FILTERS, type: ['SELL'] })).toBe(true)
    expect(stocksUrlForSymbol('BRK.B')).toBe('/stocks?symbol=BRK.B')
    expect(stockSellUrl('IXQZ')).toBe('/stocks/new?type=SELL&symbol=IXQZ')
  })
})
