import type { StockTrade, StockTradeType } from '../../api/types'
import { formatLocalDateTime, formatMoney } from '../../utils/format'

export const STOCK_TRADE_TYPES: readonly StockTradeType[] = ['BUY', 'SELL', 'TRANSFER_IN']

const TYPE_LABELS: Record<string, string> = {
  BUY: '买入',
  SELL: '卖出',
  TRANSFER_IN: '转入',
}

/** Tag colors for the type column (买入 / 卖出 / 转入 are told apart by color). */
export const STOCK_TYPE_COLORS: Record<string, string> = {
  BUY: 'blue',
  SELL: 'orange',
  TRANSFER_IN: 'purple',
}

/** 买入 / 卖出 / 转入; unknown values are shown as-is. */
export function stockTypeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

export function isStockTradeType(value: string): value is StockTradeType {
  return (STOCK_TRADE_TYPES as readonly string[]).includes(value)
}

/** Amount field label by type (requirement: 金额标签随类型变化). */
export function stockAmountLabel(type: string | undefined): string {
  if (type === 'BUY') return '实付总额（含手续费）'
  if (type === 'SELL') return '实收净额（已扣手续费）'
  if (type === 'TRANSFER_IN') return '总成本'
  return '金额'
}

/**
 * Time of a stock record or lot as shown in tables: the date for transfers ("2026-10-01"), the
 * New York local time to the minute otherwise ("2026-11-03 10:00"). Backend values, no conversion.
 */
export function stockTimeText(record: { tradeDate: string | null; tradedAtLocal: string }): string {
  return record.tradeDate ?? formatLocalDateTime(record.tradedAtLocal)
}

/** Shown when 转入 is selected (requirement wording). */
export const TRANSFER_IN_HINT =
  '用于系统上线前已持有的股票（例如之前被指派得到的股票），不会从现金中扣款'

/** Next to the transfer date field (Q86). */
export const TRANSFER_IN_DATE_HINT = '期初持仓：记在该日开始（美东 00:00），不影响现金'

/**
 * Delete summary, e.g. "卖出 150 股 IXQZ，2026-11-05 10:00，+1,200.00" or
 * "转入 100 股 IXQZ，2026-10-01，总成本 750.00" (backend values, formatted).
 */
export function stockTradeSummary(trade: StockTrade): string {
  const head = `${stockTypeLabel(trade.type)} ${String(trade.shares)} 股 ${trade.symbol}，${stockTimeText(trade)}`
  const amount =
    trade.type === 'TRANSFER_IN'
      ? `总成本 ${formatMoney(trade.amount)}`
      : formatMoney(trade.cashChange, { signed: true })
  return `${head}，${amount}`
}
