import { apiRequest } from './client'
import type { GreeksSummaryResponse } from './types'

/**
 * FEAT-019: the account's per-symbol Greeks and whole-portfolio totals. Invalidated together with
 * the option / stock positions (`refreshAfterChange`, `refreshQuotes`, the expire / adjust dialogs)
 * so the summary always follows the rows. Only requested while 显示 Greeks is on.
 */
export const greeksSummaryQueryKeys = {
  all: ['greeks-summary'] as const,
}

/** `GET /api/positions/greeks-summary` → 200 (whole account; not affected by any filter). */
export function getGreeksSummary(signal?: AbortSignal): Promise<GreeksSummaryResponse> {
  return apiRequest<GreeksSummaryResponse>('/api/positions/greeks-summary', { signal })
}
