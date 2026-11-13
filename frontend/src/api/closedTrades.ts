import { apiRequest } from './client'
import { optionTradeListQuery } from './optionTrades'
import type { ClosedTradeListParams, ClosedTradePage } from './types'

/** Invalidate `closedTradeQueryKeys.all` after any trade create / close / edit / delete. */
export const closedTradeQueryKeys = {
  all: ['closed-trades'] as const,
  list: (params: ClosedTradeListParams) => ['closed-trades', params] as const,
}

/** Query string of `GET /api/closed-trades`: the trade list's parameters plus FEAT-040 `type`. */
export function closedTradeListQuery(params: ClosedTradeListParams): string {
  const query = new URLSearchParams(optionTradeListQuery(params))
  if (params.type !== undefined) query.append('type', params.type)
  return query.toString()
}

/**
 * `GET /api/closed-trades?…` → 200 (FEAT-006, FEAT-040). Same query parameters as the trade list
 * (FEAT-002) plus the record `type` (OPTION / STOCK); `action` only BTC / STC / EXP, dates filter
 * the New York close / sell date. Rows are option closes and (FEAT-040) stock sales.
 */
export function listClosedTrades(
  params: ClosedTradeListParams,
  signal?: AbortSignal,
): Promise<ClosedTradePage> {
  return apiRequest<ClosedTradePage>(`/api/closed-trades?${closedTradeListQuery(params)}`, {
    signal,
  })
}
