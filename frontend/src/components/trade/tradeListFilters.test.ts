import { optionTradeListQuery } from '../../api/optionTrades'
import {
  EMPTY_TRADE_FILTERS,
  hasActiveFilters,
  parseTradeListSearch,
  toTradeListParams,
  toTradeListSearch,
  validateTradeFilters,
} from './tradeListFilters'

describe('tradeListFilters', () => {
  it('parses filters, comma/repeated actions, page and size from the URL', () => {
    const state = parseTradeListSearch(
      new URLSearchParams(
        'symbol=%20aapl%20&expirationDate=2026-12-18&strike=200&optionType=PUT&action=BTO,STO&action=STO&startDate=2026-11-03&endDate=2026-11-04&page=3&size=50',
      ),
    )
    expect(state).toEqual({
      filters: {
        symbol: 'aapl',
        expirationDate: '2026-12-18',
        strike: '200',
        optionType: 'PUT',
        action: ['BTO', 'STO'],
        startDate: '2026-11-03',
        endDate: '2026-11-04',
      },
      page: 3,
      size: 50,
    })
  })

  it.each([
    ['page=0&size=0', 1, 20],
    ['page=-1&size=101', 1, 20],
    ['page=abc&size=x', 1, 20],
    ['page=1.5&size=30', 1, 20],
    ['page=99999999&size=100', 1, 100],
  ])('falls back to defaults for invalid page/size %s', (search, page, size) => {
    const state = parseTradeListSearch(new URLSearchParams(search))
    expect([state.page, state.size]).toEqual([page, size])
  })

  it('writes only non-default values to the URL', () => {
    expect(toTradeListSearch({ filters: EMPTY_TRADE_FILTERS, page: 1, size: 20 }).toString()).toBe(
      '',
    )
    expect(
      toTradeListSearch({
        filters: { ...EMPTY_TRADE_FILTERS, symbol: ' AAPL ', action: ['BTO', 'STO'] },
        page: 2,
        size: 100,
      }).toString(),
    ).toBe('symbol=AAPL&action=BTO%2CSTO&page=2&size=100')
  })

  it('round-trips URL → state → URL', () => {
    const search = 'symbol=AAPL&optionType=CALL&endDate=2026-11-02&action=STO&page=2&size=50'
    expect(toTradeListSearch(parseTradeListSearch(new URLSearchParams(search))).toString()).toBe(
      search,
    )
  })

  it('detects active filters', () => {
    expect(hasActiveFilters(EMPTY_TRADE_FILTERS)).toBe(false)
    expect(hasActiveFilters({ ...EMPTY_TRADE_FILTERS, symbol: '  ' })).toBe(false)
    expect(hasActiveFilters({ ...EMPTY_TRADE_FILTERS, action: ['BTC'] })).toBe(true)
  })

  it('accepts valid filters', () => {
    expect(
      validateTradeFilters({
        symbol: ' brk.b ',
        expirationDate: '2026-12-18',
        strike: '200.000',
        optionType: 'CALL',
        action: ['BTO', 'STO', 'BTC', 'STC'],
        startDate: '2026-11-03',
        endDate: '2026-11-03',
      }),
    ).toEqual({})
  })

  it('reports invalid filters with the contract messages', () => {
    expect(
      validateTradeFilters({
        symbol: '$AAPL',
        expirationDate: '20261218',
        strike: '200.1234',
        optionType: 'call',
        action: ['BTO', 'FOO'],
        startDate: '2026-11-04',
        endDate: '2026-11-03',
      }),
    ).toEqual({
      symbol: '标的只能包含字母、数字和点',
      expirationDate: '日期格式不正确（YYYY-MM-DD）',
      strike: '行权价最多 3 位小数',
      optionType: '类型只能是 CALL 或 PUT',
      action: '方向只能是 BTO、STO、BTC、STC、EXP、ASG',
      endDate: '开始日期不能晚于结束日期',
    })
  })

  it.each([
    ['0', '行权价必须大于 0'],
    ['-5', '行权价必须大于 0'],
    ['abc', '行权价必须是数字'],
    ['10000000', '行权价过大'],
  ])('strike %s → %s', (strike, message) => {
    expect(validateTradeFilters({ ...EMPTY_TRADE_FILTERS, strike }).strike).toBe(message)
  })

  it('builds API params and the API query (repeated action, empty filters omitted)', () => {
    const params = toTradeListParams({
      filters: { ...EMPTY_TRADE_FILTERS, symbol: ' aapl ', optionType: 'PUT', action: ['BTO', 'STO'] },
      page: 2,
      size: 50,
    })
    expect(optionTradeListQuery(params)).toBe(
      'symbol=aapl&optionType=PUT&action=BTO&action=STO&page=2&size=50',
    )
  })
})
