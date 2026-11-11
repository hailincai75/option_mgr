const ACTION_LABELS: Record<string, string> = {
  BTO: 'BTO（买入开仓）',
  STO: 'STO（卖出开仓）',
  BTC: 'BTC（买入平仓）',
  STC: 'STC（卖出平仓）',
}

/** "code（中文）" for known trade actions; unknown values are shown as-is. */
export function tradeActionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

/** Text colors for signed money: positive green, negative red (index with `decimalSign()`). */
export const SIGNED_MONEY_COLORS = { 1: '#389e0d', [-1]: '#cf1322', 0: undefined } as const
