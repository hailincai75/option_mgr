import { toLocalDateTimeInputNY, formatDateTimeSecondsNY } from '../../utils/format'
import { auditUrl } from './auditLinks'
import {
  EMPTY_AUDIT_FILTERS,
  auditEmptyObjectHint,
  parseAuditSearch,
  toAuditParams,
  toAuditSearch,
  validateAuditFilters,
} from './auditSearch'

describe('auditSearch', () => {
  it('parses and round-trips the URL', () => {
    const search = 'objectType=OPTION_TRADE&objectId=3&page=2&size=50'
    const state = parseAuditSearch(new URLSearchParams(search))
    expect(state).toEqual({
      filters: { objectType: 'OPTION_TRADE', objectId: '3' },
      page: 2,
      size: 50,
    })
    expect(toAuditSearch(state).toString()).toBe(search)
    expect(toAuditParams(state)).toEqual({
      objectType: 'OPTION_TRADE',
      objectId: 3,
      page: 2,
      size: 50,
    })
  })

  it('falls back to defaults for invalid page / size', () => {
    const state = parseAuditSearch(new URLSearchParams('page=0&size=7'))
    expect([state.page, state.size]).toEqual([1, 20])
    expect(toAuditSearch({ filters: EMPTY_AUDIT_FILTERS, page: 1, size: 20 }).toString()).toBe('')
  })

  it.each([
    [{ objectType: '', objectId: '' }, {}],
    [{ objectType: 'CASH_TRANSFER', objectId: '' }, {}],
    [{ objectType: 'STOCK_TRADE', objectId: '12' }, {}],
    [{ objectType: 'X', objectId: '' }, { objectType: '对象类型只能是期权交易、入金/出金、股票交易或账户' }],
    [{ objectType: '', objectId: '3' }, { objectType: '请同时指定对象类型' }],
    [{ objectType: 'OPTION_TRADE', objectId: '0' }, { objectId: '对象 ID 必须是正整数' }],
    [{ objectType: 'OPTION_TRADE', objectId: 'abc' }, { objectId: '对象 ID 必须是正整数' }],
  ])('validates %j', (filters, errors) => {
    expect(validateAuditFilters(filters)).toEqual(errors)
  })

  it.each([
    [{ objectType: 'OPTION_TRADE', objectId: '4' }, '期权交易 #4 录入后没有被修改或删除过（审计只记录修改和删除）'],
    [{ objectType: 'CASH_TRANSFER', objectId: '01' }, '入金/出金 #1 录入后没有被修改或删除过（审计只记录修改和删除）'],
    [{ objectType: 'STOCK_TRADE', objectId: '12' }, '股票交易 #12 录入后没有被修改或删除过（审计只记录修改和删除）'],
    [{ objectType: 'OPTION_TRADE', objectId: '' }, null],
    [{ objectType: '', objectId: '' }, null],
    [{ objectType: 'ACCOUNT', objectId: '2' }, null],
  ])('empty-state object hint for %j', (filters, hint) => {
    expect(auditEmptyObjectHint(filters)).toBe(hint)
  })

  it('builds 历史 links', () => {
    expect(auditUrl('OPTION_TRADE', 3)).toBe('/audit?objectType=OPTION_TRADE&objectId=3')
    expect(auditUrl('CASH_TRANSFER', 1)).toBe('/audit?objectType=CASH_TRANSFER&objectId=1')
    expect(auditUrl('STOCK_TRADE', 12)).toBe('/audit?objectType=STOCK_TRADE&objectId=12')
  })
})

describe('FEAT-005 time formatting', () => {
  it('formats audit times with seconds in New York', () => {
    expect(formatDateTimeSecondsNY('2026-11-11T15:00:05Z')).toBe('2026-11-11 10:00:05')
    expect(formatDateTimeSecondsNY('2026-07-15T14:30:00Z')).toBe('2026-07-15 10:30:00')
    expect(formatDateTimeSecondsNY(null)).toBe('—')
  })

  it('converts an instant to a New York datetime-local value for edit forms', () => {
    expect(toLocalDateTimeInputNY('2026-10-26T14:00:00Z')).toBe('2026-10-26T10:00')
    expect(toLocalDateTimeInputNY('2026-07-15T14:30:00Z')).toBe('2026-07-15T10:30')
    expect(toLocalDateTimeInputNY('2026-11-03T14:30:15Z')).toBe('2026-11-03T09:30:15')
    expect(toLocalDateTimeInputNY('bad')).toBe('')
  })
})
