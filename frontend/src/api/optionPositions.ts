import { apiRequest } from './client'
import type { OptionPositionsResponse } from './types'

/** Invalidate `optionPositionQueryKeys.all` after anything that changes trades. */
export const optionPositionQueryKeys = {
  all: ['option-positions'] as const,
}

/** `GET /api/option-positions` → 200 (all current positions, backend order). */
export function getOptionPositions(signal?: AbortSignal): Promise<OptionPositionsResponse> {
  return apiRequest<OptionPositionsResponse>('/api/option-positions', { signal })
}
