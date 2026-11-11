import { apiRequest } from './client'
import type {
  CreateOptionTradeRequest,
  OptionTrade,
  OptionTradeListParams,
  OptionTradePage,
} from './types'

/**
 * Query keys for option trades. Creating a trade changes cash and the trade list →
 * invalidate `["cash"]` and `optionTradeQueryKeys.lists`.
 */
export const optionTradeQueryKeys = {
  all: ['option-trades'] as const,
  lists: ['option-trades', 'list'] as const,
  list: (params: OptionTradeListParams) => ['option-trades', 'list', params] as const,
  detail: (id: number) => ['option-trades', id] as const,
}

/** `POST /api/option-trades` → 201 (opening trade, BTO / STO). */
export function createOptionTrade(request: CreateOptionTradeRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades', { method: 'POST', body: request })
}

/** `GET /api/option-trades/{id}` → 200. */
export function getOptionTrade(id: number, signal?: AbortSignal): Promise<OptionTrade> {
  return apiRequest<OptionTrade>(`/api/option-trades/${String(id)}`, { signal })
}

/** Builds the query string for `GET /api/option-trades`; empty filters are left out. */
export function optionTradeListQuery(params: OptionTradeListParams): string {
  const query = new URLSearchParams()
  const single = ['symbol', 'expirationDate', 'strike', 'optionType', 'startDate', 'endDate'] as const
  for (const key of single) {
    const value = params[key]
    if (value !== undefined && value !== '') query.append(key, value)
  }
  for (const action of params.action ?? []) query.append('action', action)
  query.append('page', String(params.page))
  query.append('size', String(params.size))
  return query.toString()
}

/** `GET /api/option-trades?…` → 200 (newest first, filtered, paginated). */
export function listOptionTrades(
  params: OptionTradeListParams,
  signal?: AbortSignal,
): Promise<OptionTradePage> {
  return apiRequest<OptionTradePage>(`/api/option-trades?${optionTradeListQuery(params)}`, {
    signal,
  })
}
