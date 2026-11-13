import { apiRequest } from './client'
import type {
  CreateStockTradeRequest,
  StockPositionsResponse,
  StockTrade,
  StockTradeListParams,
  StockTradePage,
  UpdateStockTradeRequest,
} from './types'

/**
 * Query keys for stock trades (FEAT-011). Every write (create / edit / delete) goes through
 * `refreshAfterChange`, which invalidates `stockTradeQueryKeys.all` and `stockPositionQueryKeys.all`.
 * Keys are made per account by `useAccountQuery`.
 */
export const stockTradeQueryKeys = {
  all: ['stock-trades'] as const,
  lists: ['stock-trades', 'list'] as const,
  list: (params: StockTradeListParams) => ['stock-trades', 'list', params] as const,
  detail: (id: number) => ['stock-trades', id] as const,
}

export const stockPositionQueryKeys = {
  all: ['stock-positions'] as const,
}

/** `POST /api/stock-trades` → 201. */
export function createStockTrade(request: CreateStockTradeRequest): Promise<StockTrade> {
  return apiRequest<StockTrade>('/api/stock-trades', { method: 'POST', body: request })
}

/** `GET /api/stock-trades/{id}` → 200. */
export function getStockTrade(id: number, signal?: AbortSignal): Promise<StockTrade> {
  return apiRequest<StockTrade>(`/api/stock-trades/${String(id)}`, { signal })
}

/** Query string for `GET /api/stock-trades`; empty filters are left out, `type` is repeated. */
export function stockTradeListQuery(params: StockTradeListParams): string {
  const query = new URLSearchParams()
  for (const key of ['symbol', 'startDate', 'endDate'] as const) {
    const value = params[key]
    if (value !== undefined && value !== '') query.append(key, value)
  }
  for (const type of params.type ?? []) query.append('type', type)
  query.append('page', String(params.page))
  query.append('size', String(params.size))
  return query.toString()
}

/** `GET /api/stock-trades?…` → 200 (newest first, filtered, paginated). */
export function listStockTrades(
  params: StockTradeListParams,
  signal?: AbortSignal,
): Promise<StockTradePage> {
  return apiRequest<StockTradePage>(`/api/stock-trades?${stockTradeListQuery(params)}`, {
    signal,
  })
}

/** `PUT /api/stock-trades/{id}` → 200 (shares, amount, time, note; symbol / type not editable). */
export function updateStockTrade(
  id: number,
  request: UpdateStockTradeRequest,
): Promise<StockTrade> {
  return apiRequest<StockTrade>(`/api/stock-trades/${String(id)}`, {
    method: 'PUT',
    body: request,
  })
}

/** `DELETE /api/stock-trades/{id}` → 204. */
export async function deleteStockTrade(id: number): Promise<void> {
  await apiRequest<null>(`/api/stock-trades/${String(id)}`, { method: 'DELETE' })
}

/** `GET /api/stock-positions` → 200 (every symbol with shares > 0, symbol ascending). */
export function getStockPositions(signal?: AbortSignal): Promise<StockPositionsResponse> {
  return apiRequest<StockPositionsResponse>('/api/stock-positions', { signal })
}
