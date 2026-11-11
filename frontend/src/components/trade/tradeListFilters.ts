import type { OptionTradeListParams, OptionType, TradeAction } from '../../api/types'
import { validateSymbol } from './openTradeValidation'

/**
 * Trade list (FEAT-002) filter state ↔ URL query string, plus basic client-side checks.
 * URL parameter names are the API's: symbol, expirationDate, strike, optionType,
 * action (comma-separated, e.g. `action=BTO,STO`), startDate, endDate, page, size.
 * Filters are kept as raw strings so that invalid values from a hand-edited URL can be shown
 * back in the form together with an error instead of being silently dropped.
 */

export interface TradeFilterValues {
  symbol: string
  expirationDate: string
  strike: string
  /** '' = any, otherwise expected 'CALL' / 'PUT'. */
  optionType: string
  action: string[]
  startDate: string
  endDate: string
}

export type TradeFilterField = keyof TradeFilterValues

export const TRADE_FILTER_FIELDS: readonly TradeFilterField[] = [
  'symbol',
  'expirationDate',
  'strike',
  'optionType',
  'action',
  'startDate',
  'endDate',
]

export const EMPTY_TRADE_FILTERS: TradeFilterValues = {
  symbol: '',
  expirationDate: '',
  strike: '',
  optionType: '',
  action: [],
  startDate: '',
  endDate: '',
}

export const TRADE_ACTIONS: readonly TradeAction[] = ['BTO', 'STO', 'BTC', 'STC']
export const TRADE_PAGE_SIZES = [20, 50, 100] as const
export const DEFAULT_TRADE_PAGE_SIZE = 20

export interface TradeListUrlState {
  filters: TradeFilterValues
  page: number
  size: number
}

export function isTradeFilterField(field: string): field is TradeFilterField {
  return (TRADE_FILTER_FIELDS as readonly string[]).includes(field)
}

function isTradeAction(value: string): value is TradeAction {
  return (TRADE_ACTIONS as readonly string[]).includes(value)
}

function isOptionType(value: string): value is OptionType {
  return value === 'CALL' || value === 'PUT'
}

const MAX_PAGE = 1_000_000

/** Invalid / missing page or size in the URL fall back to the defaults (never sent to the API). */
export function parseTradeListSearch(search: URLSearchParams): TradeListUrlState {
  const text = (key: string) => (search.get(key) ?? '').trim()
  const actions = search
    .getAll('action')
    .flatMap((value) => value.split(','))
    .map((value) => value.trim())
    .filter((value) => value !== '')

  const pageText = text('page')
  const page = /^\d{1,7}$/.test(pageText) ? Number(pageText) : 1
  const sizeText = text('size')
  const size = TRADE_PAGE_SIZES.find((option) => String(option) === sizeText)

  return {
    filters: {
      symbol: text('symbol'),
      expirationDate: text('expirationDate'),
      strike: text('strike'),
      optionType: text('optionType'),
      action: [...new Set(actions)],
      startDate: text('startDate'),
      endDate: text('endDate'),
    },
    page: page >= 1 && page <= MAX_PAGE ? page : 1,
    size: size ?? DEFAULT_TRADE_PAGE_SIZE,
  }
}

/** URL query for a state; empty filters, page 1 and the default size are left out. */
export function toTradeListSearch(state: TradeListUrlState): URLSearchParams {
  const search = new URLSearchParams()
  const { filters } = state
  const single = ['symbol', 'expirationDate', 'strike', 'optionType', 'startDate', 'endDate'] as const
  for (const key of single) {
    const value = filters[key].trim()
    if (value !== '') search.set(key, value)
  }
  if (filters.action.length > 0) search.set('action', filters.action.join(','))
  if (state.page !== 1) search.set('page', String(state.page))
  if (state.size !== DEFAULT_TRADE_PAGE_SIZE) search.set('size', String(state.size))
  return search
}

export function hasActiveFilters(filters: TradeFilterValues): boolean {
  return TRADE_FILTER_FIELDS.some((field) => {
    const value = filters[field]
    return Array.isArray(value) ? value.length > 0 : value.trim() !== ''
  })
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const DATE_FORMAT_ERROR = '日期格式不正确（YYYY-MM-DD）'

function validateStrikeFilter(text: string): string | null {
  if (!/^[+-]?\d+(\.\d+)?$/.test(text)) return '行权价必须是数字'
  if (text.startsWith('-') || !/[1-9]/.test(text)) return '行权价必须大于 0'
  const [integer = '', fraction = ''] = text.replace(/^\+/, '').split('.')
  if (fraction.length > 3) return '行权价最多 3 位小数'
  if (integer.replace(/^0+(?=\d)/, '').length > 7) return '行权价过大'
  return null
}

/**
 * Client checks matching the backend rules (messages identical to the contract). Only
 * non-empty filters are checked. Non-existent dates like 2026-02-30 are left to the backend.
 */
export function validateTradeFilters(
  filters: TradeFilterValues,
): Partial<Record<TradeFilterField, string>> {
  const errors: Partial<Record<TradeFilterField, string>> = {}
  const symbol = filters.symbol.trim()
  if (symbol !== '') {
    const error = validateSymbol(symbol)
    if (error !== null) errors.symbol = error
  }
  const strike = filters.strike.trim()
  if (strike !== '') {
    const error = validateStrikeFilter(strike)
    if (error !== null) errors.strike = error
  }
  for (const field of ['expirationDate', 'startDate', 'endDate'] as const) {
    const value = filters[field].trim()
    if (value !== '' && !DATE_PATTERN.test(value)) errors[field] = DATE_FORMAT_ERROR
  }
  const optionType = filters.optionType.trim()
  if (optionType !== '' && !isOptionType(optionType)) errors.optionType = '类型只能是 CALL 或 PUT'
  if (!filters.action.every(isTradeAction)) errors.action = '方向只能是 BTO、STO、BTC、STC'
  const { startDate, endDate } = filters
  if (
    errors.startDate === undefined &&
    errors.endDate === undefined &&
    startDate !== '' &&
    endDate !== '' &&
    startDate > endDate
  ) {
    // Same field as the backend uses for this error.
    errors.endDate = '开始日期不能晚于结束日期'
  }
  return errors
}

/** API params for a state whose filters passed `validateTradeFilters`. */
export function toTradeListParams(state: TradeListUrlState): OptionTradeListParams {
  const { filters } = state
  const optionType = filters.optionType.trim()
  return {
    symbol: filters.symbol.trim() || undefined,
    expirationDate: filters.expirationDate.trim() || undefined,
    strike: filters.strike.trim() || undefined,
    optionType: isOptionType(optionType) ? optionType : undefined,
    action: filters.action.length > 0 ? filters.action.filter(isTradeAction) : undefined,
    startDate: filters.startDate.trim() || undefined,
    endDate: filters.endDate.trim() || undefined,
    page: state.page,
    size: state.size,
  }
}
