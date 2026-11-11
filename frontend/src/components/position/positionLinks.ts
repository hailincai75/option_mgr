import type { OptionPosition } from '../../api/types'
import { EMPTY_TRADE_FILTERS, toTradeListSearch } from '../trade/tradeListFilters'

/** FEAT-002 trade list URL filtered to this position's contract (backend values used as-is). */
export function tradesUrlForPosition(position: OptionPosition): string {
  const search = toTradeListSearch({
    filters: {
      ...EMPTY_TRADE_FILTERS,
      symbol: position.symbol,
      expirationDate: position.expirationDate,
      strike: position.strike,
      optionType: position.optionType,
    },
    page: 1,
    size: 20,
  })
  return `/trades?${search.toString()}`
}
