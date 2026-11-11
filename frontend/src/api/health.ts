import { apiRequest } from './client'
import type { HealthResponse } from './types'

export const healthQueryKey = ['health'] as const

/** `GET /api/health` */
export function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return apiRequest<HealthResponse>('/api/health', { signal })
}
