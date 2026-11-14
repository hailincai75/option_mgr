import {
  exerciseFeeForRequest,
  validateExerciseDate,
  validateExerciseFee,
} from './exerciseValidation'
import { tradesUrlForExercise } from './positionLinks'

describe('FEAT-014 exercise dialog format checks', () => {
  it.each([
    ['', null],
    ['0.00', null],
    ['5', null],
    ['5.00', null],
    ['+5.1', null],
    ['1,234.50', null],
    ['-1.00', '行权费不能为负数'],
    ['−1.00', '行权费不能为负数'],
    ['1.234', '行权费最多 2 位小数'],
    ['abc', '行权费必须是数字'],
    ['10000000000000', '行权费过大'],
    ['9999999999999.99', null],
  ])('validateExerciseFee(%j) → %j', (value, expected) => {
    expect(validateExerciseFee(value)).toBe(expected)
  })

  it('sends an empty fee as null (backend default 0.00) and strips a leading +', () => {
    expect(exerciseFeeForRequest('')).toBeNull()
    expect(exerciseFeeForRequest('  ')).toBeNull()
    expect(exerciseFeeForRequest('+5.00')).toBe('5.00')
    expect(exerciseFeeForRequest('0.00')).toBe('0.00')
  })

  it.each([
    ['', '行权日期不能为空'],
    ['  ', '行权日期不能为空'],
    ['2026/11/20', '日期格式不正确（YYYY-MM-DD）'],
    ['2026-11-20', null],
  ])('validateExerciseDate(%j) → %j', (value, expected) => {
    expect(validateExerciseDate(value)).toBe(expected)
  })

  it('builds the trade list URL of one 行权 record', () => {
    expect(tradesUrlForExercise('QRTV', '2026-11-20T16:00:00')).toBe(
      '/trades?symbol=QRTV&startDate=2026-11-20&endDate=2026-11-20&action=EXR',
    )
  })
})
