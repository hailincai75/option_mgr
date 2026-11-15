import type { OptionPosition, StockPosition } from '../../api/types'
import { formatInteger } from '../../utils/format'

/** FEAT-021: one card of the 按标的 page (FEAT-047: one per account + symbol). */
export interface UnderlyingGroup {
  /** Unique across cards: account + symbol (FEAT-047). */
  key: string
  symbol: string
  /** The rows' account (FEAT-035 rows carry it for a single account too); undefined if absent. */
  accountId: number | undefined
  /** The rows' current account name (backend value), shown as the title prefix in 全部账户 mode. */
  accountName: string | undefined
  /** This card's option positions, in the order `GET /api/option-positions` returned them. */
  options: OptionPosition[]
  /** This card's stock position, or null when no shares are held. */
  stock: StockPosition | null
}

function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/**
 * Groups the two position responses by account + symbol (presentation only, no business values):
 * the union of (accountId, symbol), ascending by symbol (UTF-16 code unit like the backend) and
 * then by account id (Q203 + Q214). For a single account every row has the same account, so this
 * is one card per symbol as before. Option rows keep the API's order (expiration → strike as a
 * number → call before put → account), which must not be re-sorted here.
 */
export function groupByUnderlying(
  options: readonly OptionPosition[],
  stocks: readonly StockPosition[],
): UnderlyingGroup[] {
  const groups = new Map<string, UnderlyingGroup>()
  const groupFor = (row: OptionPosition | StockPosition): UnderlyingGroup => {
    const key = `${row.accountId === undefined ? '' : String(row.accountId)}|${row.symbol}`
    let group = groups.get(key)
    if (group === undefined) {
      group = {
        key,
        symbol: row.symbol,
        accountId: row.accountId,
        accountName: row.accountName,
        options: [],
        stock: null,
      }
      groups.set(key, group)
    }
    return group
  }
  for (const position of options) groupFor(position).options.push(position)
  for (const position of stocks) groupFor(position).stock = position
  return [...groups.values()].sort(
    (a, b) => compareText(a.symbol, b.symbol) || (a.accountId ?? 0) - (b.accountId ?? 0),
  )
}

/** Card title summary: `期权 N 个持仓`, `股票 M 股`, joined by ` · ` when both are present. */
export function underlyingSummary(group: UnderlyingGroup): string {
  const parts: string[] = []
  if (group.options.length > 0) parts.push(`期权 ${group.options.length} 个持仓`)
  if (group.stock !== null) parts.push(`股票 ${formatInteger(group.stock.shares)} 股`)
  return parts.join(' · ')
}
