import { apiRequest } from './client'
import type { LocalDateString, NonTradingDaysResponse, TradingCalendarStatus } from './types'

/**
 * FEAT-009 trading calendar. One calendar for the whole system (Q53): these keys are NOT
 * account-scoped and the requests carry no `X-Account-Id`.
 */
export const tradingCalendarQueryKeys = {
  all: ['trading-calendar'] as const,
  status: () => ['trading-calendar', 'status'] as const,
  nonTradingDays: (from: LocalDateString, to: LocalDateString) =>
    ['trading-calendar', 'non-trading-days', from, to] as const,
}

/** `GET /api/trading-calendar/non-trading-days?from=&to=` (both inclusive, ≤ 366 days apart). */
export function getNonTradingDays(
  from: LocalDateString,
  to: LocalDateString,
  signal?: AbortSignal,
): Promise<NonTradingDaysResponse> {
  const query = new URLSearchParams({ from, to })
  return apiRequest<NonTradingDaysResponse>(
    `/api/trading-calendar/non-trading-days?${query.toString()}`,
    { signal },
  )
}

/** `GET /api/trading-calendar/status` → holiday data fetch status (stale warning). */
export function getTradingCalendarStatus(signal?: AbortSignal): Promise<TradingCalendarStatus> {
  return apiRequest<TradingCalendarStatus>('/api/trading-calendar/status', { signal })
}
