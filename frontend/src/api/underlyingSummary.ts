import { apiRequest } from './client'
import type { UnderlyingSummaryResponse } from './types'

/**
 * FEAT-023: per-symbol realized / unrealized / total P&L, market value and net premium for the
 * 按标的 cards. Invalidated wherever the positions or the P&L summary are (`refreshAfterChange`,
 * `refreshQuotes`, the expire / adjust dialogs); the account id is appended by `useAccountQuery`.
 */
export const underlyingSummaryQueryKeys = {
  all: ['underlying-summary'] as const,
}

/** `GET /api/positions/underlying-summary` → 200 (whole account; no parameters). */
export function getUnderlyingSummary(signal?: AbortSignal): Promise<UnderlyingSummaryResponse> {
  return apiRequest<UnderlyingSummaryResponse>('/api/positions/underlying-summary', { signal })
}
