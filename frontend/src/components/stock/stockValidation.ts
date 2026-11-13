import { nowLocalDateTimeNY } from '../../utils/format'

/**
 * Client-side format checks for stock records (FEAT-011). Messages match the contract's
 * `VALIDATION_ERROR` texts; the backend has the final say (shares held, cash, time vs. its clock).
 * Symbol, trade time and note reuse the option-trade checks (`openTradeValidation`).
 */

export const MAX_STOCK_SHARES = 10_000_000

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** Integer, 1 ≤ shares ≤ 10,000,000 (Q70, Q88). */
export function validateShares(value: unknown): string | null {
  const text = asText(value).trim()
  if (text === '') return '股数不能为空'
  if (!/^\d+$/.test(text) || !/[1-9]/.test(text)) return '股数必须是正整数'
  const digits = text.replace(/^0+/, '')
  if (digits.length > String(MAX_STOCK_SHARES).length || Number(digits) > MAX_STOCK_SHARES) {
    return '股数过大'
  }
  return null
}

/** Users may type thousands separators / spaces; they are stripped before validation and submit. */
export function normalizeStockAmount(input: string): string {
  return input.replace(/[,\s]/g, '')
}

/** > 0, ≤ 2 decimals, ≤ 9,999,999,999,999.99. */
export function validateStockAmount(value: unknown): string | null {
  const text = normalizeStockAmount(asText(value))
  if (text === '') return '金额不能为空'
  if (/^-\d+(\.\d+)?$/.test(text)) return '金额必须大于 0'
  if (!/^\+?\d+(\.\d+)?$/.test(text)) return '金额必须是大于 0 的数字'
  const [integer = '', fraction = ''] = text.replace(/^\+/, '').split('.')
  if (fraction.length > 2) return '金额最多 2 位小数'
  if (!/[1-9]/.test(integer + fraction)) return '金额必须大于 0'
  if (integer.replace(/^0+(?=\d)/, '').length > 13) return '金额过大'
  return null
}

const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

/** Transfer date (New York): required, not after today (rough check against the browser clock). */
export function validateTradeDate(value: unknown, now: Date = new Date()): string | null {
  const text = asText(value)
  if (text === '') return '日期不能为空'
  if (!LOCAL_DATE_PATTERN.test(text)) return '日期格式不正确'
  if (text > todayNY(now)) return '日期不能晚于今天'
  return null
}

/** Today in New York, "YYYY-MM-DD" (default transfer date). */
export function todayNY(now: Date = new Date()): string {
  return nowLocalDateTimeNY(now).slice(0, 10)
}
