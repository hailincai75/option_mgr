import { monthOf, monthRange, monthsToLoad, shiftMonth } from './tradingCalendar'

describe('monthRange', () => {
  it('returns the first and last day of the month', () => {
    expect(monthRange('2026-11')).toEqual({ from: '2026-11-01', to: '2026-11-30' })
    expect(monthRange('2027-03')).toEqual({ from: '2027-03-01', to: '2027-03-31' })
    expect(monthRange('2028-02')).toEqual({ from: '2028-02-01', to: '2028-02-29' })
    expect(monthRange('2027-02')).toEqual({ from: '2027-02-01', to: '2027-02-28' })
  })

  it('rejects malformed months', () => {
    expect(() => monthRange('2026-13')).toThrow()
    expect(() => monthRange('2026/11')).toThrow()
  })
})

describe('shiftMonth', () => {
  it('moves across year boundaries', () => {
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2026-11', 4)).toBe('2027-03')
    expect(shiftMonth('2026-11', 0)).toBe('2026-11')
  })
})

describe('monthOf', () => {
  it('returns the month of a YYYY-MM-DD date or null', () => {
    expect(monthOf('2027-03-26')).toBe('2027-03')
    expect(monthOf('')).toBeNull()
    expect(monthOf(undefined)).toBeNull()
    expect(monthOf('2027-3-26')).toBeNull()
    expect(monthOf('2027-13-01')).toBeNull()
  })
})

describe('monthsToLoad', () => {
  it('includes the neighbouring months shown in the date panel', () => {
    expect(monthsToLoad('2027-01')).toEqual(['2026-12', '2027-01', '2027-02'])
  })
})
