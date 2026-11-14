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

/**
 * FEAT-013: trade list URL that finds one 被指派 record — its symbol, action ASG and its New York
 * date (`tradedAtLocal` "YYYY-MM-DDT16:00:00" of the generated stock record).
 */
export function tradesUrlForAssignment(symbol: string, tradedAtLocal: string): string {
  const date = tradedAtLocal.slice(0, 10)
  const search = toTradeListSearch({
    filters: { ...EMPTY_TRADE_FILTERS, symbol, action: ['ASG'], startDate: date, endDate: date },
    page: 1,
    size: 20,
  })
  return `/trades?${search.toString()}`
}

/**
 * FEAT-014: trade list URL that finds one 行权 record — its symbol, action EXR and its New York date
 * (`tradedAtLocal` "YYYY-MM-DDT16:00:00" of the generated stock record).
 */
export function tradesUrlForExercise(symbol: string, tradedAtLocal: string): string {
  const date = tradedAtLocal.slice(0, 10)
  const search = toTradeListSearch({
    filters: { ...EMPTY_TRADE_FILTERS, symbol, action: ['EXR'], startDate: date, endDate: date },
    page: 1,
    size: 20,
  })
  return `/trades?${search.toString()}`
}

/** @see tradesUrlForContract */
export const tradesUrlForPosition = tradesUrlForContract
