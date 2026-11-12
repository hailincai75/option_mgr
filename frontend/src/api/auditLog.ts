import { apiRequest } from './client'
import type { AuditLogPage, AuditLogParams } from './types'

/** Invalidate `auditLogQueryKeys.all` after every edit / delete. */
export const auditLogQueryKeys = {
  all: ['audit-log'] as const,
  list: (params: AuditLogParams) => ['audit-log', params] as const,
}

/** `GET /api/audit-log?…` → 200 (newest first, optional object filter, paginated). */
export function getAuditLog(params: AuditLogParams, signal?: AbortSignal): Promise<AuditLogPage> {
  const query = new URLSearchParams()
  if (params.objectType !== undefined) query.append('objectType', params.objectType)
  if (params.objectId !== undefined) query.append('objectId', String(params.objectId))
  query.append('page', String(params.page))
  query.append('size', String(params.size))
  return apiRequest<AuditLogPage>(`/api/audit-log?${query.toString()}`, { signal })
}
