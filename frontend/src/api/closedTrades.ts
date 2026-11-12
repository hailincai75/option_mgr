import { apiRequest } from './client'
import { optionTradeListQuery } from './optionTrades'
import type { ClosedTradePage, OptionTradeListParams } from './types'

/** Invalidate `closedTradeQueryKeys.all` after any trade create / close / edit / delete. */
export const closedTradeQueryKeys = {
  all: ['closed-trades'] as const,
  list: (params: OptionTradeListParams) => ['closed-trades', params] as const,
}

/**
 * `GET /api/closed-trades?…` → 200 (FEAT-006). Same query parameters as the trade list
 * (FEAT-002); `action` only BTC / STC, dates filter the New York close date.
 */
export function listClosedTrades(
  params: OptionTradeListParams,
  signal?: AbortSignal,
): Promise<ClosedTradePage> {
  return apiRequest<ClosedTradePage>(`/api/closed-trades?${optionTradeListQuery(params)}`, {
    signal,
  })
}
