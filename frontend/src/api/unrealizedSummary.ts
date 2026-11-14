import { apiRequest } from './client'
import type { UnrealizedSummaryResponse } from './types'

/**
 * FEAT-017: the account's unrealized totals. Invalidated together with the option / stock
 * positions (`refreshAfterChange`, `refreshQuotes`, the expire / adjust dialogs) so the totals
 * always follow the rows.
 */
export const unrealizedSummaryQueryKeys = {
  all: ['unrealized-summary'] as const,
}

/** `GET /api/positions/unrealized-summary` → 200 (whole account; not affected by any filter). */
export function getUnrealizedSummary(signal?: AbortSignal): Promise<UnrealizedSummaryResponse> {
  return apiRequest<UnrealizedSummaryResponse>('/api/positions/unrealized-summary', { signal })
}
