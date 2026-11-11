import {
  normalizeAmount,
  validateAmount,
  validateOccurredAt,
  validateRemark,
} from './cashTransferValidation'

describe('validateAmount', () => {
  it.each(['30000.00', '0.10', '1', '2500.5', '30,000.00', '9999999999999.99', '000.01'])(
    'accepts %s',
    (value) => {
      expect(validateAmount(value)).toBeNull()
    },
  )

  it.each([
    ['', '请输入金额'],
    [undefined, '请输入金额'],
    ['0', '金额必须大于 0'],
    ['0.00', '金额必须大于 0'],
    ['-100.00', '金额必须是大于 0 的数字'],
    ['abc', '金额必须是大于 0 的数字'],
    ['1e3', '金额必须是大于 0 的数字'],
    ['100.001', '金额最多 2 位小数'],
    ['10000000000000', '金额过大'],
  ])('rejects %s', (value, error) => {
    expect(validateAmount(value)).toBe(error)
  })

  it('strips thousands separators and spaces', () => {
    expect(normalizeAmount(' 1,234,567.89 ')).toBe('1234567.89')
  })
})

describe('validateOccurredAt', () => {
  const now = new Date('2026-11-10T14:45:00Z') // 09:45 New York

  it('accepts past and current New York times', () => {
    expect(validateOccurredAt('2026-11-02T10:00', now)).toBeNull()
    expect(validateOccurredAt('2026-11-10T09:45', now)).toBeNull()
    expect(validateOccurredAt('2026-11-10T09:45:30', now)).toBeNull()
  })

  it('rejects empty, malformed and future values', () => {
    expect(validateOccurredAt('', now)).toBe('请选择时间')
    expect(validateOccurredAt('2026-11-10 09:00', now)).toBe('时间格式不正确')
    expect(validateOccurredAt('2026-11-10T10:00', now)).toBe('时间不能晚于当前时间')
  })
})

describe('validateRemark', () => {
  it('allows up to 500 characters and rejects 501', () => {
    expect(validateRemark('')).toBeNull()
    expect(validateRemark('x'.repeat(500))).toBeNull()
    expect(validateRemark('x'.repeat(501))).toBe('备注最多 500 个字符')
  })
})
