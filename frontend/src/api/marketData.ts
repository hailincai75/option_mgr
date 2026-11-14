import { apiRequest } from './client'
import type {
  MarketDataRefreshResponse,
  MarketDataStatus,
  OptionContractKey,
  OptionOverrideRequest,
  OptionQuote,
  UnderlyingOverrideRequest,
  UnderlyingQuote,
} from './types'

/**
 * FEAT-016 market data. One set of quotes and overrides for the whole system (rule 1): these keys
 * are NOT account-scoped and the requests carry no `X-Account-Id`. The quotes themselves arrive on
 * the (account-scoped) option / stock positions, which must be invalidated after any change here.
 */
export const marketDataQueryKeys = {
  all: ['market-data'] as const,
  status: () => ['market-data', 'status'] as const,
}

/** `GET /api/market-data/status` → top warning and 立即刷新 state. */
export function getMarketDataStatus(signal?: AbortSignal): Promise<MarketDataStatus> {
  return apiRequest<MarketDataStatus>('/api/market-data/status', { signal })
}

/** `POST /api/market-data/refresh` (synchronous; `performed: false` within the cooldown). */
export function refreshMarketData(): Promise<MarketDataRefreshResponse> {
  return apiRequest<MarketDataRefreshResponse>('/api/market-data/refresh', { method: 'POST' })
}

function underlyingOverridePath(symbol: string): string {
  return `/api/market-data/overrides/underlyings/${encodeURIComponent(symbol)}`
}

/** `PUT /api/market-data/overrides/underlyings/{symbol}` → the symbol's quote object. */
export function setUnderlyingOverride(
  symbol: string,
  body: UnderlyingOverrideRequest,
): Promise<UnderlyingQuote> {
  return apiRequest<UnderlyingQuote>(underlyingOverridePath(symbol), { method: 'PUT', body })
}

/** `DELETE /api/market-data/overrides/underlyings/{symbol}` → 204 (idempotent). */
export async function clearUnderlyingOverride(symbol: string): Promise<void> {
  await apiRequest<null>(underlyingOverridePath(symbol), { method: 'DELETE' })
}

/** `PUT /api/market-data/overrides/options` → the contract's quote object. */
export function setOptionOverride(body: OptionOverrideRequest): Promise<OptionQuote> {
  return apiRequest<OptionQuote>('/api/market-data/overrides/options', { method: 'PUT', body })
}

/** `DELETE /api/market-data/overrides/options?symbol=&expirationDate=&strike=&optionType=` → 204. */
export async function clearOptionOverride(contract: OptionContractKey): Promise<void> {
  const query = new URLSearchParams({
    symbol: contract.symbol,
    expirationDate: contract.expirationDate,
    strike: contract.strike,
    optionType: contract.optionType,
  })
  await apiRequest<null>(`/api/market-data/overrides/options?${query.toString()}`, {
    method: 'DELETE',
  })
}
