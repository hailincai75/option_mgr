import type { PnlSummaryParams } from '../../api/types'
import { EMPTY_TRADE_FILTERS, toTradeListSearch } from '../trade/tradeListFilters'

/**
 * 盈亏汇总 page (FEAT-008) state ↔ URL (`startDate`, `endDate`, same names as the API) and the
 * links to the 已平仓 page. Only date logic here — every amount comes from the backend.
 */

export interface PnlRange {
  startDate: string
  endDate: string
}

export type PnlRangeField = keyof PnlRange

export const EMPTY_PNL_RANGE: PnlRange = { startDate: '', endDate: '' }

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export function isPnlRangeField(field: string): field is PnlRangeField {
  return field === 'startDate' || field === 'endDate'
}

export function parsePnlSearch(search: URLSearchParams): PnlRange {
  return {
    startDate: (search.get('startDate') ?? '').trim(),
    endDate: (search.get('endDate') ?? '').trim(),
  }
}

export function toPnlSearch(range: PnlRange): URLSearchParams {
  const search = new URLSearchParams()
  if (range.startDate.trim() !== '') search.set('startDate', range.startDate.trim())
  if (range.endDate.trim() !== '') search.set('endDate', range.endDate.trim())
  return search
}

export function hasPnlRange(range: PnlRange): boolean {
  return range.startDate !== '' || range.endDate !== ''
}

/** Same rules and messages as the backend (FEAT-002/006); reversal reported on endDate. */
export function validatePnlRange(range: PnlRange): Partial<Record<PnlRangeField, string>> {
  const errors: Partial<Record<PnlRangeField, string>> = {}
  for (const field of ['startDate', 'endDate'] as const) {
    if (range[field] !== '' && !DATE_PATTERN.test(range[field])) {
      errors[field] = '日期格式不正确（YYYY-MM-DD）'
    }
  }
  if (
    Object.keys(errors).length === 0 &&
    range.startDate !== '' &&
    range.endDate !== '' &&
    range.startDate > range.endDate
  ) {
    errors.endDate = '开始日期不能晚于结束日期'
  }
  return errors
}

export function toPnlParams(range: PnlRange): PnlSummaryParams {
  return {
    startDate: range.startDate === '' ? undefined : range.startDate,
    endDate: range.endDate === '' ? undefined : range.endDate,
  }
}

function closedUrl(filters: Partial<typeof EMPTY_TRADE_FILTERS>): string {
  const search = toTradeListSearch({
    filters: { ...EMPTY_TRADE_FILTERS, ...filters },
    page: 1,
    size: 20,
  })
  return `/closed?${search.toString()}`
}

/** 已平仓 page filtered to one symbol within the current range. */
export function closedUrlForSymbol(symbol: string, range: PnlRange): string {
  return closedUrl({ symbol, startDate: range.startDate, endDate: range.endDate })
}

/** First and last day of a "YYYY-MM" month (calendar logic only). */
export function monthBounds(month: string): { first: string; last: string } {
  const [yearText = '', monthText = ''] = month.split('-')
  const lastDay = new Date(Date.UTC(Number(yearText), Number(monthText), 0)).getUTCDate()
  return { first: `${month}-01`, last: `${month}-${String(lastDay).padStart(2, '0')}` }
}

/** The month intersected with the current range (FEAT-008: 1st..last day ∩ range). */
export function monthRangeWithin(month: string, range: PnlRange): PnlRange {
  const { first, last } = monthBounds(month)
  const startDate = range.startDate !== '' && range.startDate > first ? range.startDate : first
  const endDate = range.endDate !== '' && range.endDate < last ? range.endDate : last
  return { startDate, endDate }
}

/** 已平仓 page filtered to one month (intersected with the current range). */
export function closedUrlForMonth(month: string, range: PnlRange): string {
  return closedUrl(monthRangeWithin(month, range))
}
