import { decimalSign, EMPTY_VALUE, formatMoney } from '../../utils/format'

/**
 * Display helpers shared by the single 作废 dialog (FEAT-012) and the 批量作废 dialog (FEAT-015).
 * Formatting only; every value comes from the backend.
 */

const DIRECTION_LABELS: Record<string, string> = { SHORT: '空头', LONG: '多头' }

/** SHORT → 空头, LONG → 多头, null → "—", unknown values as-is. */
export function directionLabel(direction: string | null): string {
  if (direction === null) return EMPTY_VALUE
  return DIRECTION_LABELS[direction] ?? direction
}

/** Frozen amounts: "—" for zero, otherwise the backend value (`signed` for changes). */
export function frozenText(value: string, signed: boolean): string {
  return decimalSign(value) === 0 ? EMPTY_VALUE : formatMoney(value, { signed })
}
