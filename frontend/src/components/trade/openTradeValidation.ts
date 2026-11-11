import { nowLocalDateTimeNY } from '../../utils/format'

/**
 * Basic client-side format checks for the 录入开仓 form (FEAT-001). No business calculation
 * (collateral, cash) happens here; the backend has the final say on everything.
 * Messages mirror the backend's `details[].message` texts where they overlap.
 */

export const NOTE_MAX_LENGTH = 500
export const SYMBOL_MAX_LENGTH = 10

/** Users may type thousands separators / spaces in decimals; they are stripped before submit. */
export function normalizeDecimal(input: string): string {
  return input.replace(/[,\s]/g, '')
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function validateSymbol(value: unknown): string | null {
  const text = asText(value).trim()
  if (text === '') return '标的不能为空'
  if (!/^[A-Za-z0-9.]+$/.test(text)) return '标的只能包含字母、数字和点'
  if (text.length > SYMBOL_MAX_LENGTH) return `标的最多 ${String(SYMBOL_MAX_LENGTH)} 个字符`
  return null
}

const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const LOCAL_DATE_TIME_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/

/**
 * Expiration must not be before the trade's New York date (0DTE allowed). Both are plain
 * "YYYY-MM-DD" strings, so string comparison is date comparison; skipped if the time is invalid.
 */
export function validateExpirationDate(value: unknown, tradedAtLocal: unknown): string | null {
  const text = asText(value)
  if (text === '') return '到期日不能为空'
  if (!LOCAL_DATE_PATTERN.test(text)) return '到期日格式不正确'
  const traded = asText(tradedAtLocal)
  if (LOCAL_DATE_TIME_PATTERN.test(traded)) {
    const tradeDate = traded.slice(0, 10)
    if (text < tradeDate) return `到期日不能早于成交日（${tradeDate}）`
  }
  return null
}

interface DecimalRule {
  label: string
  maxDecimals: number
  maxIntegerDigits: number
}

function validatePositiveDecimal(value: unknown, rule: DecimalRule): string | null {
  const text = normalizeDecimal(asText(value))
  if (text === '') return `${rule.label}不能为空`
  if (!/^\d+(\.\d+)?$/.test(text)) return `${rule.label}必须是大于 0 的数字`
  const fraction = text.split('.')[1] ?? ''
  if (fraction.length > rule.maxDecimals) {
    return `${rule.label}最多 ${String(rule.maxDecimals)} 位小数`
  }
  if (!/[1-9]/.test(text)) return `${rule.label}必须大于 0`
  const integer = (text.split('.')[0] ?? '').replace(/^0+(?=\d)/, '')
  if (integer.length > rule.maxIntegerDigits) return `${rule.label}过大`
  return null
}

/** > 0, ≤ 3 decimals, ≤ 9,999,999.999. */
export function validateStrike(value: unknown): string | null {
  return validatePositiveDecimal(value, { label: '行权价', maxDecimals: 3, maxIntegerDigits: 7 })
}

/** > 0, ≤ 2 decimals, ≤ 13 integer digits. */
export function validateNetAmount(value: unknown): string | null {
  return validatePositiveDecimal(value, { label: '净额', maxDecimals: 2, maxIntegerDigits: 13 })
}

function validatePositiveInteger(
  value: unknown,
  { label, max, emptyMessage }: { label: string; max: number; emptyMessage: string },
): string | null {
  const text = asText(value).trim()
  if (text === '') return emptyMessage
  if (!/^\d+$/.test(text) || !/[1-9]/.test(text)) return `${label}必须是正整数`
  const digits = text.replace(/^0+/, '')
  if (digits.length > String(max).length || Number(digits) > max) return `${label}过大`
  return null
}

/** Positive integer ≤ 100,000. */
export function validateContracts(value: unknown): string | null {
  return validatePositiveInteger(value, { label: '张数', max: 100_000, emptyMessage: '张数不能为空' })
}

/** Positive integer ≤ 10,000 (default 100 is pre-filled). */
export function validateMultiplier(value: unknown): string | null {
  return validatePositiveInteger(value, {
    label: 'multiplier ',
    max: 10_000,
    emptyMessage: 'multiplier 必须是正整数',
  })
}

/** Rough client check against the browser clock; the backend has the final say. */
export function validateTradedAt(value: unknown, now: Date = new Date()): string | null {
  const text = asText(value)
  if (text === '') return '成交时间不能为空'
  if (!LOCAL_DATE_TIME_PATTERN.test(text)) return '成交时间格式不正确'
  // Same fixed-width format on both sides, so string comparison orders correctly.
  if (text.slice(0, 16) > nowLocalDateTimeNY(now)) return '成交时间不能晚于当前时间'
  return null
}

/** String.length counts UTF-16 units, matching the backend's rule. */
export function validateNote(value: unknown): string | null {
  return asText(value).length > NOTE_MAX_LENGTH
    ? `备注最多 ${String(NOTE_MAX_LENGTH)} 个字符`
    : null
}
