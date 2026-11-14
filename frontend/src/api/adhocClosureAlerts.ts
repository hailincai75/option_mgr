import { apiRequest } from './client'
import type { AdhocClosureAlertsResponse } from './types'

/**
 * FEAT-042: account-scoped (`useAccountQuery` appends the account). Invalidated by
 * `refreshAfterChange` together with `optionPositionQueryKeys.all` after any write.
 */
export const adhocClosureAlertQueryKeys = {
  all: ['adhoc-closure-alerts'] as const,
}

/** `GET /api/adhoc-closure-alerts` → 200 (needs `X-Account-Id`, added by the client). */
export function getAdhocClosureAlerts(signal?: AbortSignal): Promise<AdhocClosureAlertsResponse> {
  return apiRequest<AdhocClosureAlertsResponse>('/api/adhoc-closure-alerts', { signal })
}
