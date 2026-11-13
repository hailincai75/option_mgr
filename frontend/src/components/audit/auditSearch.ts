import type { AuditLogParams, AuditObjectType } from '../../api/types'

/**
 * 审计记录 page state ↔ URL: `objectType`, `objectId`, `page`, `size` (same names as the API).
 * Filter values stay raw strings so a hand-edited URL can be shown back with an error.
 */

export interface AuditFilterValues {
  /** '' = all, otherwise expected OPTION_TRADE / CASH_TRANSFER / ACCOUNT (FEAT-033). */
  objectType: string
  objectId: string
}

export type AuditFilterField = keyof AuditFilterValues

export interface AuditUrlState {
  filters: AuditFilterValues
  page: number
  size: number
}

export const EMPTY_AUDIT_FILTERS: AuditFilterValues = { objectType: '', objectId: '' }
export const AUDIT_PAGE_SIZES = [20, 50, 100] as const
const DEFAULT_SIZE = 20

export const AUDIT_OBJECT_TYPE_LABELS: Record<AuditObjectType, string> = {
  OPTION_TRADE: '期权交易',
  CASH_TRANSFER: '入金/出金',
  ACCOUNT: '账户',
}

function isObjectType(value: string): value is AuditObjectType {
  return value === 'OPTION_TRADE' || value === 'CASH_TRANSFER' || value === 'ACCOUNT'
}

export function isAuditFilterField(field: string): field is AuditFilterField {
  return field === 'objectType' || field === 'objectId'
}

/** Invalid / missing page or size fall back to the defaults (never sent to the API). */
export function parseAuditSearch(search: URLSearchParams): AuditUrlState {
  const text = (key: string) => (search.get(key) ?? '').trim()
  const pageText = text('page')
  const page = /^\d{1,7}$/.test(pageText) && Number(pageText) >= 1 ? Number(pageText) : 1
  const size = AUDIT_PAGE_SIZES.find((option) => String(option) === text('size'))
  return {
    filters: { objectType: text('objectType'), objectId: text('objectId') },
    page,
    size: size ?? DEFAULT_SIZE,
  }
}

/** Empty filters, page 1 and the default size are left out. */
export function toAuditSearch(state: AuditUrlState): URLSearchParams {
  const search = new URLSearchParams()
  const objectType = state.filters.objectType.trim()
  const objectId = state.filters.objectId.trim()
  if (objectType !== '') search.set('objectType', objectType)
  if (objectId !== '') search.set('objectId', objectId)
  if (state.page !== 1) search.set('page', String(state.page))
  if (state.size !== DEFAULT_SIZE) search.set('size', String(state.size))
  return search
}

/**
 * Extra empty-state line when the page is filtered to one trade / cash entry (the rows' "历史"
 * link): audits only record edits and deletes, not creation (Q42), so an empty result just means
 * the record was never changed. Null without a full object filter, and for accounts (whose
 * creation is audited, so an empty result there is not this case). Expects valid filters.
 */
export function auditEmptyObjectHint(filters: AuditFilterValues): string | null {
  const objectType = filters.objectType.trim()
  const objectId = filters.objectId.trim()
  if (objectId === '') return null
  if (objectType !== 'OPTION_TRADE' && objectType !== 'CASH_TRANSFER') return null
  const label = `${AUDIT_OBJECT_TYPE_LABELS[objectType]} #${String(Number(objectId))}`
  return `${label} 录入后没有被修改或删除过（审计只记录修改和删除）`
}

/** Client checks matching the backend rules; errors keep the request from being sent. */
export function validateAuditFilters(
  filters: AuditFilterValues,
): Partial<Record<AuditFilterField, string>> {
  const errors: Partial<Record<AuditFilterField, string>> = {}
  const objectType = filters.objectType.trim()
  const objectId = filters.objectId.trim()
  if (objectType !== '' && !isObjectType(objectType)) {
    errors.objectType = '对象类型只能是期权交易、入金/出金或账户'
  }
  if (objectId !== '') {
    if (!/^\d{1,15}$/.test(objectId) || !/[1-9]/.test(objectId)) {
      errors.objectId = '对象 ID 必须是正整数'
    } else if (objectType === '') {
      errors.objectType = '请同时指定对象类型'
    }
  }
  return errors
}

/** API params for a state whose filters passed `validateAuditFilters`. */
export function toAuditParams(state: AuditUrlState): AuditLogParams {
  const objectType = state.filters.objectType.trim()
  const objectId = state.filters.objectId.trim()
  return {
    objectType: isObjectType(objectType) ? objectType : undefined,
    objectId: objectId === '' ? undefined : Number(objectId),
    page: state.page,
    size: state.size,
  }
}
