import { normalizeDecimal } from '../trade/openTradeValidation'

/**
 * FEAT-014 行权 dialog: format checks only (messages as in the contract). The date's relation to
 * the expiration date and to "now" is checked by the backend (422 `EXERCISE_DATE_INVALID`).
 */

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** Optional: empty = 0.00. ≥ 0, ≤ 2 decimals, ≤ 9,999,999,999,999.99. */
export function validateExerciseFee(value: unknown): string | null {
  const text = normalizeDecimal(asText(value))
  if (text === '') return null
  // Accept the U+2212 minus sign users may paste from the app's own displays.
  if (/^[-−]/.test(text)) return '行权费不能为负数'
  if (!/^\+?\d+(\.\d+)?$/.test(text)) return '行权费必须是数字'
  const [integer = '', fraction = ''] = text.replace(/^\+/, '').split('.')
  if (fraction.length > 2) return '行权费最多 2 位小数'
  if (integer.replace(/^0+(?=\d)/, '').length > 13) return '行权费过大'
  return null
}

/** Value sent to the backend: null when left empty (backend default 0.00). */
export function exerciseFeeForRequest(value: string): string | null {
  const text = normalizeDecimal(value).replace(/^\+/, '')
  return text === '' ? null : text
}

/** Required, "YYYY-MM-DD". */
export function validateExerciseDate(value: unknown): string | null {
  const text = asText(value).trim()
  if (text === '') return '行权日期不能为空'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return '日期格式不正确（YYYY-MM-DD）'
  return null
}
