import {
  normalizeDecimal,
  validateContracts,
  validateExpirationDate,
  validateMultiplier,
  validateNetAmount,
  validateNote,
  validateStrike,
  validateSymbol,
  validateTradedAt,
} from './openTradeValidation'

describe('openTradeValidation', () => {
  it.each([
    ['AAPL', null],
    [' aapl ', null],
    ['BRK.B', null],
    ['ABCDEFGHIJ', null],
    ['', '标的不能为空'],
    ['   ', '标的不能为空'],
    ['AA PL', '标的只能包含字母、数字和点'],
    ['$AAPL', '标的只能包含字母、数字和点'],
    ['ABCDEFGHIJK', '标的最多 10 个字符'],
  ])('symbol %j → %s', (value, expected) => {
    expect(validateSymbol(value)).toBe(expected)
  })

  it.each([
    ['200', null],
    ['12.5', null],
    ['12.345', null],
    ['9999999.999', null],
    ['', '行权价不能为空'],
    ['0', '行权价必须大于 0'],
    ['0.000', '行权价必须大于 0'],
    ['-5', '行权价必须是大于 0 的数字'],
    ['abc', '行权价必须是大于 0 的数字'],
    ['200.1234', '行权价最多 3 位小数'],
    ['10000000', '行权价过大'],
  ])('strike %j → %s', (value, expected) => {
    expect(validateStrike(value)).toBe(expected)
  })

  it.each([
    ['349.35', null],
    ['1,000.5', null],
    ['', '净额不能为空'],
    ['0', '净额必须大于 0'],
    ['-10.00', '净额必须是大于 0 的数字'],
    ['349.355', '净额最多 2 位小数'],
    ['12345678901234', '净额过大'],
  ])('netAmount %j → %s', (value, expected) => {
    expect(validateNetAmount(value)).toBe(expected)
  })

  it.each([
    ['1', null],
    ['100000', null],
    ['', '张数不能为空'],
    ['0', '张数必须是正整数'],
    ['-1', '张数必须是正整数'],
    ['1.5', '张数必须是正整数'],
    ['100001', '张数过大'],
  ])('contracts %j → %s', (value, expected) => {
    expect(validateContracts(value)).toBe(expected)
  })

  it.each([
    ['100', null],
    ['10', null],
    ['', 'multiplier 必须是正整数'],
    ['0', 'multiplier 必须是正整数'],
    ['10.5', 'multiplier 必须是正整数'],
    ['10001', 'multiplier 过大'],
  ])('multiplier %j → %s', (value, expected) => {
    expect(validateMultiplier(value)).toBe(expected)
  })

  it('checks expiration against the trade date (0DTE allowed)', () => {
    expect(validateExpirationDate('', '2026-11-10T10:30')).toBe('到期日不能为空')
    expect(validateExpirationDate('2026-11-09', '2026-11-10T10:30')).toBe(
      '到期日不能早于成交日（2026-11-10）',
    )
    expect(validateExpirationDate('2026-11-10', '2026-11-10T10:30')).toBeNull()
    expect(validateExpirationDate('2026-12-18', '2026-11-10T10:30')).toBeNull()
    // Unknown trade time: only the format is checked.
    expect(validateExpirationDate('2026-11-09', '')).toBeNull()
  })

  it('rejects a trade time later than now in New York', () => {
    const now = new Date('2026-11-10T14:45:00Z') // 09:45 New York
    expect(validateTradedAt('2026-11-10T09:45', now)).toBeNull()
    expect(validateTradedAt('2026-11-10T10:00', now)).toBe('成交时间不能晚于当前时间')
    expect(validateTradedAt('', now)).toBe('成交时间不能为空')
    expect(validateTradedAt('2026-11-10', now)).toBe('成交时间格式不正确')
  })

  it('limits the note to 500 characters', () => {
    expect(validateNote('x'.repeat(500))).toBeNull()
    expect(validateNote('x'.repeat(501))).toBe('备注最多 500 个字符')
  })

  it('strips thousands separators and spaces from decimals', () => {
    expect(normalizeDecimal(' 1,000.50 ')).toBe('1000.50')
  })
})
