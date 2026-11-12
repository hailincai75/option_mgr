import { apiRequest } from './client'
import type { Dashboard, PnlSummary, PnlSummaryParams } from './types'

/** Invalidate both after any trade or cash change (see `refreshAfterChange`). */
export const dashboardQueryKeys = {
  all: ['dashboard'] as const,
}

export const pnlSummaryQueryKeys = {
  all: ['pnl-summary'] as const,
  detail: (params: PnlSummaryParams) => ['pnl-summary', params] as const,
}

/** `GET /api/dashboard` → 200 (FEAT-008 home page figures). */
export function getDashboard(signal?: AbortSignal): Promise<Dashboard> {
  return apiRequest<Dashboard>('/api/dashboard', { signal })
}

/** `GET /api/pnl-summary?startDate=&endDate=` → 200 (FEAT-008); empty dates are left out. */
export function getPnlSummary(params: PnlSummaryParams, signal?: AbortSignal): Promise<PnlSummary> {
  const query = new URLSearchParams()
  if (params.startDate) query.append('startDate', params.startDate)
  if (params.endDate) query.append('endDate', params.endDate)
  const text = query.toString()
  return apiRequest<PnlSummary>(`/api/pnl-summary${text === '' ? '' : `?${text}`}`, { signal })
}
