import type { CreateExpirationAdjustmentRequest, OptionPosition } from '../../api/types'

/** FEAT-043 rule 1: text of the row button. */
export const ADJUST_EXPIRATION_BUTTON_TEXT = '调整到期日'

/** FEAT-043: the 调整到期日 button only follows the backend flag. */
export function isExpirationAdjustable(position: OptionPosition): boolean {
  return position.expirationAdjustable === true
}

/** "2026-12-04（National Day of Mourning）"; just the date when the backend gives no event name. */
export function currentExpirationText(position: OptionPosition): string {
  const name = position.adhocClosureEventName?.trim()
  return name ? `${position.expirationDate}（${name}）` : position.expirationDate
}

/** "3 条开仓 / 平仓记录" (requirement AC 2). */
export function recordCountText(count: number): string {
  return `${String(count)} 条开仓 / 平仓记录`
}

/** Requirement rule 2: explanation under the form. */
export function adjustmentExplanation(count: number): string {
  return `该合约在本账户的 ${String(count)} 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录`
}

/** Request body: the current contract exactly as on the row, plus the new date and note. */
export function toExpirationAdjustmentRequest(
  position: OptionPosition,
  newExpirationDate: string,
  note: string | undefined,
): CreateExpirationAdjustmentRequest {
  const trimmed = note?.trim() ?? ''
  return {
    symbol: position.symbol,
    expirationDate: position.expirationDate,
    strike: position.strike,
    optionType: position.optionType,
    newExpirationDate,
    note: trimmed === '' ? null : trimmed,
  }
}
