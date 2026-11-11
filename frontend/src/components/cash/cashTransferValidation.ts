import { nowLocalDateTimeNY } from '../../utils/format'

export const REMARK_MAX_LENGTH = 500

/** Users may type thousands separators; they are stripped before validation and submit. */
export function normalizeAmount(input: string): string {
  return input.replace(/[,\s]/g, '')
}

/** Basic format checks only (required, > 0, ≤ 2 decimals, ≤ 13 integer digits); no arithmetic. */
export function validateAmount(value: unknown): string | null {
  const text = typeof value === 'string' ? normalizeAmount(value) : ''
  if (text === '') return '请输入金额'
  if (!/^\d+(\.\d+)?$/.test(text)) return '金额必须是大于 0 的数字'
  if (/^\d+\.\d{3,}$/.test(text)) return '金额最多 2 位小数'
  if (!/[1-9]/.test(text)) return '金额必须大于 0'
  if (!/^\d{1,13}(\.\d{1,2})?$/.test(text.replace(/^0+(?=\d)/, ''))) return '金额过大'
  return null
}

const LOCAL_DATE_TIME_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/

/** Rough client check (browser clock); the backend has the final say. */
export function validateOccurredAt(value: unknown, now: Date = new Date()): string | null {
  const text = typeof value === 'string' ? value : ''
  if (text === '') return '请选择时间'
  if (!LOCAL_DATE_TIME_PATTERN.test(text)) return '时间格式不正确'
  // Same fixed-width format on both sides, so string comparison orders correctly.
  if (text.slice(0, 16) > nowLocalDateTimeNY(now)) return '时间不能晚于当前时间'
  return null
}

/** String.length counts UTF-16 units, matching the backend's rule (emoji count as 2). */
export function validateRemark(value: unknown): string | null {
  return typeof value === 'string' && value.length > REMARK_MAX_LENGTH
    ? `备注最多 ${REMARK_MAX_LENGTH} 个字符`
    : null
}
