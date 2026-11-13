import type { ClosedRecordType, ClosedTradeListParams, TradeAction } from '../../api/types'
import {
  EMPTY_TRADE_FILTERS,
  hasActiveFilters,
  isTradeFilterField,
  parseTradeListSearch,
  toTradeListParams,
  toTradeListSearch,
  validateTradeFilters,
  type TradeFilterField,
  type TradeFilterValues,
} from '../trade/tradeListFilters'

/**
 * 已平仓 page filters (FEAT-006 + FEAT-040): the trade list filters (same URL names and rules)
 * plus the record `type` (`OPTION` / `STOCK`, '' = 不限) in the URL as `type=`.
 */
export interface ClosedFilterValues extends TradeFilterValues {
  /** '' = both, otherwise expected 'OPTION' / 'STOCK' (raw URL text, may be invalid). */
  type: string
}

export type ClosedFilterField = TradeFilterField | 'type'

export interface ClosedListUrlState {
  filters: ClosedFilterValues
  page: number
  size: number
}

export const EMPTY_CLOSED_FILTERS: ClosedFilterValues = { ...EMPTY_TRADE_FILTERS, type: '' }

/** FEAT-006: the closed-trade list only accepts closing directions (FEAT-012 adds EXP). */
export const CLOSE_ACTIONS: readonly TradeAction[] = ['BTC', 'STC', 'EXP']

/** Shown when 类型 = 不限 and any option-only filter is set (Q90, requirement wording). */
export const OPTION_ONLY_HINT = '按到期日 / 行权价 / Call·Put / 方向筛选时只显示期权'

const TYPE_ERROR = '类型只能是 OPTION 或 STOCK'

export function isClosedRecordType(value: string): value is ClosedRecordType {
  return value === 'OPTION' || value === 'STOCK'
}

export function isClosedFilterField(field: string): field is ClosedFilterField {
  return field === 'type' || isTradeFilterField(field)
}

export function parseClosedListSearch(search: URLSearchParams): ClosedListUrlState {
  const state = parseTradeListSearch(search)
  return {
    ...state,
    filters: { ...state.filters, type: (search.get('type') ?? '').trim() },
  }
}

/** URL query for a state; empty filters, page 1 and the default size are left out. */
export function toClosedListSearch(state: ClosedListUrlState): URLSearchParams {
  const search = toTradeListSearch(state)
  const type = state.filters.type.trim()
  if (type !== '') search.set('type', type)
  return search
}

export function hasActiveClosedFilters(filters: ClosedFilterValues): boolean {
  return filters.type.trim() !== '' || hasActiveFilters(filters)
}

/** Trade list checks with the closing directions, plus the record type (contract message). */
export function validateClosedFilters(
  filters: ClosedFilterValues,
): Partial<Record<ClosedFilterField, string>> {
  const errors: Partial<Record<ClosedFilterField, string>> = validateTradeFilters(
    filters,
    CLOSE_ACTIONS,
  )
  const type = filters.type.trim()
  if (type !== '' && !isClosedRecordType(type)) errors.type = TYPE_ERROR
  return errors
}

/** API params for a state whose filters passed `validateClosedFilters`. */
export function toClosedListParams(state: ClosedListUrlState): ClosedTradeListParams {
  const type = state.filters.type.trim()
  return {
    ...toTradeListParams(state),
    type: isClosedRecordType(type) ? type : undefined,
  }
}
