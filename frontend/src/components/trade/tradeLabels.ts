import type { OptionTrade } from '../../api/types'
import { decimalSign, formatInteger, formatMoney } from '../../utils/format'

const ACTION_LABELS: Record<string, string> = {
  BTO: 'BTO（买入开仓）',
  STO: 'STO（卖出开仓）',
  BTC: 'BTC（买入平仓）',
  STC: 'STC（卖出平仓）',
  // FEAT-012: an expiry record is shown by its Chinese name only.
  EXP: '到期作废',
  // FEAT-013: an assignment record (卖出 put 被指派).
  ASG: '被指派',
}

/**
 * "code（中文）" for known trade actions, "到期作废" for EXP, "被指派" for ASG; unknown values are
 * shown as-is.
 */
export function tradeActionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

/** FEAT-012: EXP records (到期作废) can only be deleted, never edited (contract). */
export function isExpiryAction(action: string): boolean {
  return action === 'EXP'
}

/** FEAT-013: ASG records (被指派). */
export function isAssignmentAction(action: string): boolean {
  return action === 'ASG'
}

/** EXP and ASG can only be deleted (contract: the frontend decides by `action`). */
export function isDeleteOnlyAction(action: string): boolean {
  return isExpiryAction(action) || isAssignmentAction(action)
}

/** Tag colors of the system-generated directions (told apart from BTO / STO / BTC / STC). */
export const SPECIAL_ACTION_COLORS: Record<string, string> = { EXP: 'purple', ASG: 'magenta' }

/** FEAT-041: a call assignment delivers shares (指派卖出); a put assignment buys them. */
export function isCallAssignment(trade: Pick<OptionTrade, 'optionType'>): boolean {
  return trade.optionType === 'CALL'
}

/**
 * Small text of a 被指派 row: put (FEAT-013) "买入 300 股 XYZ，现金 −13,500.00", call (FEAT-041)
 * "卖出 300 股 IXQZ，现金 +2,700.00" (+ "，指派费 5.00" when the fee is not zero); backend values
 * only. null for other records.
 */
export function assignmentRowText(trade: OptionTrade): string | null {
  const assignment = trade.assignment
  if (assignment === null) return null
  const verb = isCallAssignment(trade) ? '卖出' : '买入'
  const shares = formatInteger(assignment.shares)
  const cash = formatMoney(trade.cashChange, { signed: true })
  const fee =
    decimalSign(assignment.assignmentFee) === 0
      ? ''
      : `，指派费 ${formatMoney(assignment.assignmentFee)}`
  return `${verb} ${shares} 股 ${trade.symbol}，现金 ${cash}${fee}`
}

/** Text colors for signed money: positive green, negative red (index with `decimalSign()`). */
export const SIGNED_MONEY_COLORS = { 1: '#389e0d', [-1]: '#cf1322', 0: undefined } as const
