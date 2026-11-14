import { closedTradeListQuery } from '../../api/closedTrades'
import {
  EMPTY_CLOSED_FILTERS,
  hasActiveClosedFilters,
  isClosedFilterField,
  parseClosedListSearch,
  toClosedListParams,
  toClosedListSearch,
  validateClosedFilters,
} from './closedListFilters'

describe('closedListFilters (FEAT-040)', () => {
  it('parses and writes the record type with the trade list filters', () => {
    const state = parseClosedListSearch(new URLSearchParams('type=STOCK&symbol=ixqz&page=2'))
    expect(state.filters.type).toBe('STOCK')
    expect(state.filters.symbol).toBe('ixqz')
    expect(state.page).toBe(2)
    expect(toClosedListSearch(state).toString()).toBe('symbol=ixqz&page=2&type=STOCK')
    expect(toClosedListSearch({ filters: EMPTY_CLOSED_FILTERS, page: 1, size: 20 }).toString()).toBe(
      '',
    )
  })

  it('validates the record type with the contract message', () => {
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, type: 'OPTION' })).toEqual({})
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, type: 'STOCK' })).toEqual({})
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, type: 'FUTURE' })).toEqual({
      type: '类型只能是 OPTION 或 STOCK',
    })
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, type: 'stock' })).toEqual({
      type: '类型只能是 OPTION 或 STOCK',
    })
    // Closing directions only (FEAT-006).
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, action: ['STO'] })).toEqual({
      action: '方向只能是 BTC、STC、EXP、ASG、EXR',
    })
  })

  it('builds API params and the query string with type', () => {
    const params = toClosedListParams({
      filters: { ...EMPTY_CLOSED_FILTERS, type: 'STOCK', symbol: 'IXQZ' },
      page: 1,
      size: 20,
    })
    expect(params.type).toBe('STOCK')
    expect(closedTradeListQuery(params)).toBe('symbol=IXQZ&page=1&size=20&type=STOCK')
    expect(
      toClosedListParams({ filters: EMPTY_CLOSED_FILTERS, page: 1, size: 20 }).type,
    ).toBeUndefined()
  })

  it('counts the record type as an active filter and as a filter field', () => {
    expect(hasActiveClosedFilters(EMPTY_CLOSED_FILTERS)).toBe(false)
    expect(hasActiveClosedFilters({ ...EMPTY_CLOSED_FILTERS, type: 'OPTION' })).toBe(true)
    expect(isClosedFilterField('type')).toBe(true)
    expect(isClosedFilterField('strike')).toBe(true)
    expect(isClosedFilterField('page')).toBe(false)
  })
})
