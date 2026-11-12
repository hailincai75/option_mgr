import type { AuditObjectType } from '../../api/types'

/** 审计记录 page filtered to one object (the rows' "历史" links). */
export function auditUrl(objectType: AuditObjectType, objectId: number): string {
  const search = new URLSearchParams({ objectType, objectId: String(objectId) })
  return `/audit?${search.toString()}`
}
