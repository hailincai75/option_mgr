import { stocksUrlForAssignment, validateStockFilters } from '../stock/stockListFilters'
import { EMPTY_STOCK_FILTERS } from '../stock/stockListFilters'
import { validateClosedFilters, EMPTY_CLOSED_FILTERS } from '../closed/closedListFilters'
import { validateTradeFilters, EMPTY_TRADE_FILTERS } from '../trade/tradeListFilters'
import {
  assignmentFeeForRequest,
  validateAssignmentDate,
  validateAssignmentFee,
} from './assignValidation'
import { tradesUrlForAssignment } from './positionLinks'

describe('FEAT-013 assignment fee', () => {
  it.each([
    ['', null],
    ['0.00', null],
    ['5', null],
    ['1,234.50', null],
    ['9999999999999.99', null],
    ['−1.00', '指派费不能为负数'],
    ['-1', '指派费不能为负数'],
    ['1.234', '指派费最多 2 位小数'],
    ['10,000,000,000,000.00', '指派费过大'],
    ['abc', '指派费必须是数字'],
  ])('%s → %s', (input, expected) => {
    expect(validateAssignmentFee(input)).toBe(expected)
  })

  it('sends empty as null and strips separators', () => {
    expect(assignmentFeeForRequest('')).toBeNull()
    expect(assignmentFeeForRequest(' 1,234.50 ')).toBe('1234.50')
    expect(assignmentFeeForRequest('0.00')).toBe('0.00')
  })
})

describe('FEAT-013 assignment date', () => {
  it('is required and must be YYYY-MM-DD (range is the backend’s)', () => {
    expect(validateAssignmentDate('')).toBe('指派日期不能为空')
    expect(validateAssignmentDate('2026/11/20')).toBe('日期格式不正确（YYYY-MM-DD）')
    expect(validateAssignmentDate('2026-11-21')).toBeNull()
  })
})

describe('FEAT-013 links and filters', () => {
  it('links a 指派买入 to its assignment and back', () => {
    expect(tradesUrlForAssignment('XYZ', '2026-11-20T16:00:00')).toBe(
      '/trades?symbol=XYZ&startDate=2026-11-20&endDate=2026-11-20&action=ASG',
    )
    expect(stocksUrlForAssignment('XYZ', '2026-11-20T16:00:00')).toBe(
      '/stocks?symbol=XYZ&type=ASSIGN_BUY&startDate=2026-11-20&endDate=2026-11-20',
    )
  })

  it('accepts ASG / ASSIGN_BUY in the list filters', () => {
    expect(validateTradeFilters({ ...EMPTY_TRADE_FILTERS, action: ['ASG'] })).toEqual({})
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, action: ['ASG'] })).toEqual({})
    expect(validateClosedFilters({ ...EMPTY_CLOSED_FILTERS, action: ['STO'] })).toEqual({
      action: '方向只能是 BTC、STC、EXP、ASG',
    })
    expect(validateStockFilters({ ...EMPTY_STOCK_FILTERS, type: ['ASSIGN_BUY'] })).toEqual({})
    expect(validateStockFilters({ ...EMPTY_STOCK_FILTERS, type: ['X'] })).toEqual({
      type: '类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY',
    })
  })
})
