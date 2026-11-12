import type { OptionPosition } from '../../api/types'
import { EMPTY_TRADE_FILTERS, toTradeListSearch } from '../trade/tradeListFilters'

/** The four contract fields, as returned by positions and closed trades. */
export type ContractFields = Pick<
  OptionPosition,
  'symbol' | 'expirationDate' | 'strike' | 'optionType'
>

/** FEAT-002 trade list URL filtered to one contract (backend values used as-is). */
export function tradesUrlForContract(contract: ContractFields): string {
  const search = toTradeListSearch({
    filters: {
      ...EMPTY_TRADE_FILTERS,
      symbol: contract.symbol,
      expirationDate: contract.expirationDate,
      strike: contract.strike,
      optionType: contract.optionType,
    },
    page: 1,
    size: 20,
  })
  return `/trades?${search.toString()}`
}

/** @see tradesUrlForContract */
export const tradesUrlForPosition = tradesUrlForContract
