import { ApiError } from '../../api/client'
import type {
  MarketDataRefreshResponse,
  MarketDataStatus,
  OptionQuote,
  UnderlyingQuote,
} from '../../api/types'
import { EMPTY_VALUE, formatLocalDateTime, formatMoney, formatMonthDayTime } from '../../utils/format'

/**
 * FEAT-016 display helpers. Everything shown comes from the backend: the frontend never computes a
 * mark, never decides whether a quote is stale, and shows the backend's sentences verbatim.
 */

/** Shown for a null display price / mark (Q139 / Q143 "从未有过报价"). */
export const NO_QUOTE_TEXT = '无报价'

/** Hover text of a Finnhub-sourced underlying price (page spec). */
export const FINNHUB_SOURCE_HINT = '来源 Finnhub'

/** Hover text of 立即刷新 (page spec). */
export const REFRESH_COOLDOWN_HINT = '1 分钟内只能刷新一次'

export const REFRESH_BUTTON_TEXT = '立即刷新'
export const MANUAL_SET_TEXT = '手动设置'
export const MANUAL_CLEAR_TEXT = '清除'

/** Display price / mark: the backend string formatted (no rounding), or 无报价 when null / missing. */
export function quotePriceText(value: string | null | undefined): string {
  return value == null ? NO_QUOTE_TEXT : formatMoney(value)
}

/** `手动 11-23 10:05` from the override's New York entry time. */
export function manualTagText(enteredAtLocal: string | null | undefined): string {
  return enteredAtLocal == null ? '手动' : `手动 ${formatMonthDayTime(enteredAtLocal)}`
}

/** True when the underlying price shown is the automatic Finnhub price (hover `来源 Finnhub`). */
export function isFinnhubPrice(quote: UnderlyingQuote | undefined): boolean {
  return quote !== undefined && !quote.manual && quote.price !== null && quote.source === 'FINNHUB'
}

/** `1.20 / 1.30`; each side `—` when null; `—` when the contract was never quoted. */
export function bidAskText(quote: OptionQuote | undefined): string {
  if (quote === undefined || (quote.bid === null && quote.ask === null)) return EMPTY_VALUE
  return `${formatMoney(quote.bid)} / ${formatMoney(quote.ask)}`
}

/** 报价时间 column: `MM-DD HH:mm` (New York) of the last automatic quote, `—` when none. */
export function quoteTimeText(quote: { quoteTimeLocal: string | null } | undefined): string {
  return formatMonthDayTime(quote?.quoteTimeLocal)
}

/** Small status text beside 立即刷新: last successful fetch run in New York. */
export function lastSuccessText(status: MarketDataStatus | undefined): string | null {
  if (status === undefined) return null
  if (status.lastSuccessAtLocal === null) return '行情尚未成功获取'
  return `行情更新于 ${formatLocalDateTime(status.lastSuccessAtLocal)}（美东）`
}

export interface RefreshOutcome {
  kind: 'cooldown' | 'success' | 'partial'
  text: string
}

/**
 * Hint after 立即刷新 (`results` are only a hint per the contract; row labels come from the
 * positions): COOLDOWN, all symbols fine, or the symbols whose option chain was not updated.
 */
export function refreshOutcome(response: MarketDataRefreshResponse): RefreshOutcome {
  if (!response.performed) {
    return { kind: 'cooldown', text: `${REFRESH_COOLDOWN_HINT}，本次未重新获取` }
  }
  if (response.results.length === 0) {
    return { kind: 'success', text: '没有需要获取行情的持仓' }
  }
  const notUpdated = response.results
    .filter((result) => result.result !== 'SUCCESS')
    .map((result) => (result.result === 'FALLBACK' ? `${result.symbol}（仅股价）` : result.symbol))
  if (notUpdated.length === 0) {
    return { kind: 'success', text: `已刷新 ${String(response.results.length)} 个标的` }
  }
  return { kind: 'partial', text: `已刷新，期权报价未更新：${notUpdated.join('、')}` }
}

/**
 * When to ask the backend again whether 立即刷新 is allowed. The backend's clock decides (it may be
 * a test clock), so the browser's clock only schedules one re-check, clamped to 1–60 s; the
 * regular status polling covers the rest. null when nothing needs scheduling.
 */
export function refreshRecheckDelay(
  status: Pick<MarketDataStatus, 'refreshAllowed' | 'refreshAvailableAt'> | undefined,
  nowMs: number,
): number | null {
  if (status === undefined || status.refreshAllowed || status.refreshAvailableAt === null) {
    return null
  }
  const availableMs = Date.parse(status.refreshAvailableAt)
  if (Number.isNaN(availableMs)) return null
  return Math.min(60_000, Math.max(1_000, availableMs - nowMs + 500))
}

// ---------------------------------------------------------------------------
// 手动设置 price input
// ---------------------------------------------------------------------------

const PRICE_PATTERN = /^(\d+)(?:\.(\d+))?$/

/**
 * Format checks of a manual price, with the backend's messages (the backend validates again).
 * String-only: no floating point.
 */
export function validateManualPrice(value: unknown): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  if (text === '') return '请输入价格'
  const match = PRICE_PATTERN.exec(text)
  if (!match) return '请输入有效的价格'
  const [, integer = '', fraction = ''] = match
  if (/^0*$/.test(integer + fraction)) return '价格必须大于 0'
  if (fraction.length > 4) return '价格最多 4 位小数'
  if (integer.replace(/^0+/, '').length >= 7) return '价格过大'
  return null
}

/** Field names the backend may use for the price in override errors. */
const PRICE_FIELDS = ['price', 'mark']

export interface OverrideErrorDisplay {
  /** Messages to show under the price input. */
  fieldErrors: string[]
  /** Error to show above the buttons (422 message verbatim, unmapped details appended); or null. */
  formError: { code: string; message: string } | null
}

/** Splits an override PUT error into price-field errors and the rest. */
export function overrideErrorDisplay(error: Error): OverrideErrorDisplay {
  if (!(error instanceof ApiError)) {
    return { fieldErrors: [], formError: { code: 'UNKNOWN', message: error.message } }
  }
  const fieldErrors: string[] = []
  const unmapped: string[] = []
  for (const detail of error.details) {
    if (PRICE_FIELDS.includes(detail.field)) fieldErrors.push(detail.message)
    else unmapped.push(`${detail.field}: ${detail.message}`)
  }
  if (fieldErrors.length > 0 && unmapped.length === 0) return { fieldErrors, formError: null }
  return {
    fieldErrors,
    formError: {
      code: error.code,
      message: unmapped.length > 0 ? `${error.message}（${unmapped.join('；')}）` : error.message,
    },
  }
}
