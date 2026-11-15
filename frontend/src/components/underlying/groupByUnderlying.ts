import type { OptionPosition, StockPosition } from '../../api/types'
import { formatInteger } from '../../utils/format'

/** FEAT-021: one card of the 按标的 page. */
export interface UnderlyingGroup {
  symbol: string
  /** This symbol's option positions, in the order `GET /api/option-positions` returned them. */
  options: OptionPosition[]
  /** This symbol's stock position, or null when no shares are held. */
  stock: StockPosition | null
}

/**
 * Groups the two position responses by symbol (presentation only, no business values): the
 * union of symbols, ascending by UTF-16 code unit like the backend; option rows keep the API's
 * order (expiration → strike as a number → call before put), which must not be re-sorted here.
 */
export function groupByUnderlying(
  options: readonly OptionPosition[],
  stocks: readonly StockPosition[],
): UnderlyingGroup[] {
  const groups = new Map<string, UnderlyingGroup>()
  const groupFor = (symbol: string): UnderlyingGroup => {
    let group = groups.get(symbol)
    if (group === undefined) {
      group = { symbol, options: [], stock: null }
      groups.set(symbol, group)
    }
    return group
  }
  for (const position of options) groupFor(position.symbol).options.push(position)
  for (const position of stocks) groupFor(position.symbol).stock = position
  return [...groups.values()].sort((a, b) =>
    a.symbol < b.symbol ? -1 : a.symbol > b.symbol ? 1 : 0,
  )
}

/** Card title summary: `期权 N 个持仓`, `股票 M 股`, joined by ` · ` when both are present. */
export function underlyingSummary(group: UnderlyingGroup): string {
  const parts: string[] = []
  if (group.options.length > 0) parts.push(`期权 ${group.options.length} 个持仓`)
  if (group.stock !== null) parts.push(`股票 ${formatInteger(group.stock.shares)} 股`)
  return parts.join(' · ')
}
