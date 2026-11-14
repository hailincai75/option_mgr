import {
  expiryEmptyMessage,
  filterPositionsByExpiry,
  matchesExpiryFilter,
  parseExpirySearch,
  positionsUrlForExpiry,
  withExpirySearch,
} from './expiryFilter'

describe('expiryFilter (FEAT-010)', () => {
  it('parses known values and treats anything else as 全部 (rule 4)', () => {
    const parse = (search: string) => parseExpirySearch(new URLSearchParams(search))
    expect(parse('')).toBe('all')
    expect(parse('expiry=expired')).toBe('expired')
    expect(parse('expiry=today')).toBe('today')
    expect(parse('expiry=7')).toBe('7')
    expect(parse('expiry=14')).toBe('14')
    expect(parse('expiry=30')).toBe('30')
    expect(parse('expiry=10')).toBe('all')
    expect(parse('expiry=abc')).toBe('all')
    expect(parse('expiry=all')).toBe('all')
    expect(parse('expiry=EXPIRED')).toBe('all')
  })

  it('writes the filter to the URL, removing it for 全部 and keeping other params', () => {
    const base = new URLSearchParams('foo=1&expiry=7')
    expect(withExpirySearch(base, 'expired').toString()).toBe('foo=1&expiry=expired')
    expect(withExpirySearch(base, 'all').toString()).toBe('foo=1')
    expect(base.toString()).toBe('foo=1&expiry=7')
    expect(positionsUrlForExpiry('7')).toBe('/positions?expiry=7')
    expect(positionsUrlForExpiry('expired')).toBe('/positions?expiry=expired')
    expect(positionsUrlForExpiry('all')).toBe('/positions')
  })

  it('compares the backend DTE only, N 天内 including 0 and N (rule 1)', () => {
    const at = (dte: number) => ({ dte })
    expect(matchesExpiryFilter(at(-1), 'expired')).toBe(true)
    expect(matchesExpiryFilter(at(0), 'expired')).toBe(false)
    expect(matchesExpiryFilter(at(0), 'today')).toBe(true)
    expect(matchesExpiryFilter(at(1), 'today')).toBe(false)
    expect(matchesExpiryFilter(at(-1), '7')).toBe(false)
    expect(matchesExpiryFilter(at(0), '7')).toBe(true)
    expect(matchesExpiryFilter(at(7), '7')).toBe(true)
    expect(matchesExpiryFilter(at(8), '7')).toBe(false)
    expect(matchesExpiryFilter(at(14), '14')).toBe(true)
    expect(matchesExpiryFilter(at(15), '14')).toBe(false)
    expect(matchesExpiryFilter(at(30), '30')).toBe(true)
    expect(matchesExpiryFilter(at(31), '30')).toBe(false)
    expect(matchesExpiryFilter(at(-100), 'all')).toBe(true)
  })

  it('keeps the input order when filtering', () => {
    const rows = [-5, -5, 0, 2, 7, 9, 30, 58].map((dte, index) => ({ dte, id: index }))
    expect(filterPositionsByExpiry(rows, 'expired').map((r) => r.id)).toEqual([0, 1])
    expect(filterPositionsByExpiry(rows, '7').map((r) => r.id)).toEqual([2, 3, 4])
    expect(filterPositionsByExpiry(rows, '14').map((r) => r.id)).toEqual([2, 3, 4, 5])
    expect(filterPositionsByExpiry(rows, '30').map((r) => r.id)).toEqual([2, 3, 4, 5, 6])
    expect(filterPositionsByExpiry(rows, 'all')).toBe(rows)
  })

  it('has the empty-result messages (rule 6)', () => {
    expect(expiryEmptyMessage('all')).toBeNull()
    expect(expiryEmptyMessage('expired')).toBe('没有已过期未处理的持仓')
    expect(expiryEmptyMessage('today')).toBe('没有今天到期的持仓')
    expect(expiryEmptyMessage('7')).toBe('没有 7 天内到期的持仓')
    expect(expiryEmptyMessage('14')).toBe('没有 14 天内到期的持仓')
    expect(expiryEmptyMessage('30')).toBe('没有 30 天内到期的持仓')
  })
})
