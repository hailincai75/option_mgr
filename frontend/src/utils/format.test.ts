import {
  decimalSign,
  EMPTY_VALUE,
  formatDateTimeNY,
  formatInstantNY,
  formatInteger,
  formatLocalDateTime,
  formatMoney,
  formatMonthDayTime,
  formatPercent,
  nowLocalDateTimeNY,
} from './format'

describe('formatInstantNY', () => {
  it('converts to New York time during daylight saving time (EDT, UTC-4)', () => {
    expect(formatInstantNY('2026-07-01T16:30:00Z')).toBe('2026-07-01 12:30:00 EDT')
  })

  it('converts to New York time during standard time (EST, UTC-5)', () => {
    expect(formatInstantNY('2026-11-10T22:09:08.676Z')).toBe('2026-11-10 17:09:08 EST')
  })

  it('rolls the date back when UTC is already the next day', () => {
    expect(formatInstantNY('2026-12-19T02:00:00Z')).toBe('2026-12-18 21:00:00 EST')
  })

  it('handles the fall-back DST transition on 2026-11-01', () => {
    expect(formatInstantNY('2026-11-01T05:30:00Z')).toBe('2026-11-01 01:30:00 EDT')
    expect(formatInstantNY('2026-11-01T06:30:00Z')).toBe('2026-11-01 01:30:00 EST')
  })

  it('shows midnight as 00, not 24', () => {
    expect(formatInstantNY('2026-11-10T05:00:00Z')).toBe('2026-11-10 00:00:00 EST')
  })

  it.each(['2026-11-10T14:30:00Z', '2026-11-10T14:30:00.1Z', '2026-11-10T14:30:00.123456789Z'])(
    'accepts a variable number of fractional-second digits: %s',
    (input) => {
      expect(formatInstantNY(input)).toBe('2026-11-10 09:30:00 EST')
    },
  )

  it.each([null, undefined, '', '   ', 'not-a-date'])('returns the placeholder for %s', (input) => {
    expect(formatInstantNY(input)).toBe(EMPTY_VALUE)
  })
})

describe('formatMoney', () => {
  it.each([
    ['0.00', '0.00'],
    ['0.30', '0.30'],
    ['30000.00', '30,000.00'],
    ['1234567.89', '1,234,567.89'],
    ['9999999999999.99', '9,999,999,999,999.99'],
    ['100', '100.00'],
    ['1.5', '1.50'],
    ['-2500.50', '−2,500.50'],
    ['-500.00', '−500.00'],
    ['-0.00', '0.00'],
    ['0012.30', '12.30'],
    ['1.234', '1.234'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatMoney(input)).toBe(expected)
  })

  it('adds "+" to positive values when signed, never to zero', () => {
    expect(formatMoney('30000.00', { signed: true })).toBe('+30,000.00')
    expect(formatMoney('-2500.50', { signed: true })).toBe('−2,500.50')
    expect(formatMoney('0.00', { signed: true })).toBe('0.00')
  })

  it.each([null, undefined, '', 'abc', '1e5', '1,000.00'])('returns the placeholder for %s', (v) => {
    expect(formatMoney(v)).toBe(EMPTY_VALUE)
  })
})

describe('decimalSign', () => {
  it.each([
    ['30000.00', 1],
    ['-0.01', -1],
    ['0.00', 0],
    ['-0.00', 0],
    ['abc', 0],
    [null, 0],
  ] as const)('%s → %s', (input, expected) => {
    expect(decimalSign(input)).toBe(expected)
  })
})

describe('formatDateTimeNY', () => {
  it('shows minute precision in New York time (standard and daylight time)', () => {
    expect(formatDateTimeNY('2026-11-02T15:00:00Z')).toBe('2026-11-02 10:00')
    expect(formatDateTimeNY('2026-07-15T14:30:00Z')).toBe('2026-07-15 10:30')
    expect(formatDateTimeNY('2026-11-03T14:30:00.123456Z')).toBe('2026-11-03 09:30')
  })

  it.each([null, undefined, '', 'nope'])('returns the placeholder for %s', (input) => {
    expect(formatDateTimeNY(input)).toBe(EMPTY_VALUE)
  })
})

describe('nowLocalDateTimeNY', () => {
  it('returns the datetime-local value for New York', () => {
    expect(nowLocalDateTimeNY(new Date('2026-11-10T14:45:00Z'))).toBe('2026-11-10T09:45')
    expect(nowLocalDateTimeNY(new Date('2026-07-15T14:30:59Z'))).toBe('2026-07-15T10:30')
    expect(nowLocalDateTimeNY(new Date('2026-11-11T03:00:00Z'))).toBe('2026-11-10T22:00')
  })
})

describe('formatLocalDateTime', () => {
  it('shows a New York local date-time as-is, to the minute', () => {
    expect(formatLocalDateTime('2026-11-20T16:00:00')).toBe('2026-11-20 16:00')
    expect(formatLocalDateTime('2027-03-19T16:00')).toBe('2027-03-19 16:00')
  })

  it('keeps non-zero seconds', () => {
    expect(formatLocalDateTime('2026-11-20T15:59:05')).toBe('2026-11-20 15:59:05')
  })

  it.each([null, undefined, '', '2026-11-20', 'nope'])('returns the placeholder for %s', (input) => {
    expect(formatLocalDateTime(input)).toBe(EMPTY_VALUE)
  })
})

describe('formatInteger', () => {
  it.each([
    [0, '0'],
    [150, '150'],
    [1000, '1,000'],
    [10000000, '10,000,000'],
    [-1500, '−1,500'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatInteger(input)).toBe(expected)
  })

  it.each([null, undefined, 1.5, Number.NaN])('returns the placeholder for %s', (input) => {
    expect(formatInteger(input)).toBe(EMPTY_VALUE)
  })
})

describe('formatMonthDayTime (FEAT-016)', () => {
  it('shows a New York local date-time as MM-DD HH:mm without conversion', () => {
    expect(formatMonthDayTime('2026-11-23T09:45:00')).toBe('11-23 09:45')
    expect(formatMonthDayTime('2026-11-23T10:05:30')).toBe('11-23 10:05')
    expect(formatMonthDayTime('2026-01-02T16:20')).toBe('01-02 16:20')
  })

  it('returns the placeholder for null or malformed input', () => {
    expect(formatMonthDayTime(null)).toBe(EMPTY_VALUE)
    expect(formatMonthDayTime(undefined)).toBe(EMPTY_VALUE)
    expect(formatMonthDayTime('2026-11-23T14:45:00Z')).toBe(EMPTY_VALUE)
    expect(formatMonthDayTime('')).toBe(EMPTY_VALUE)
  })
})

describe('formatPercent (FEAT-017)', () => {
  it('adds an explicit sign and % to the backend percent without rounding', () => {
    expect(formatPercent('-5.31')).toBe('−5.31%')
    expect(formatPercent('22.80')).toBe('+22.80%')
    expect(formatPercent('0.00')).toBe('0.00%')
    expect(formatPercent('-80.83')).toBe('−80.83%')
    expect(formatPercent('1234.5')).toBe('+1,234.50%')
  })

  it('returns the placeholder for null / missing / invalid values', () => {
    expect(formatPercent(null)).toBe(EMPTY_VALUE)
    expect(formatPercent(undefined)).toBe(EMPTY_VALUE)
    expect(formatPercent('abc')).toBe(EMPTY_VALUE)
  })
})
