import type { Dashboard, HealthResponse } from '../api/types'
import { jsonResponse } from './render'

export const HEALTH_UP: HealthResponse = {
  status: 'UP',
  version: 'dev',
  time: '2026-11-10T22:09:08Z',
}

export const EMPTY_DASHBOARD: Dashboard = {
  asOfDate: '2026-11-12',
  currentMonth: '2026-11',
  cash: { totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' },
  realizedPnl: { closedCount: 0, total: '0.00' },
  currentMonthRealizedPnl: { closedCount: 0, total: '0.00' },
  positions: {
    positionCount: 0,
    frozenTotal: '0.00',
    expiringWithin7DaysCount: 0,
    expiredUnprocessedCount: 0,
  },
  stockPositions: { positionCount: 0 },
}

/** Responses for the home page's own requests (dashboard, cash summary, health). */
export function homeResponse(url: string): Promise<Response> {
  if (url === '/api/dashboard') return Promise.resolve(jsonResponse(EMPTY_DASHBOARD))
  if (url === '/api/cash/summary') return Promise.resolve(jsonResponse(EMPTY_DASHBOARD.cash))
  return Promise.resolve(jsonResponse(HEALTH_UP))
}
