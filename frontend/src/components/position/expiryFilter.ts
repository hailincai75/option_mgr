import type { OptionPosition } from '../../api/types'

/**
 * FEAT-010 持仓页到期过滤 (Q80, Q105, Q109, Q110): filter state ↔ URL (`?expiry=`) and which
 * positions match. Only compares the backend's `dte` (Q34: negative = 已过期, 0 = 今天到期);
 * no date math here.
 */

export type ExpiryFilter = 'all' | 'expired' | 'today' | '7' | '14' | '30'

export const EXPIRY_SEARCH_PARAM = 'expiry'

export const EXPIRY_FILTER_OPTIONS: { value: ExpiryFilter; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'expired', label: '已过期未处理' },
  { value: 'today', label: '今天到期' },
  { value: '7', label: '7 天内' },
  { value: '14', label: '14 天内' },
  { value: '30', label: '30 天内' },
]

const FILTER_VALUES: readonly string[] = EXPIRY_FILTER_OPTIONS.map((option) => option.value)

function isExpiryFilter(value: string): value is ExpiryFilter {
  return value !== 'all' && FILTER_VALUES.includes(value)
}

/** Missing or unknown values (e.g. `10`, `abc`, `all`) mean 全部 (rule 4). */
export function parseExpirySearch(search: URLSearchParams): ExpiryFilter {
  const value = (search.get(EXPIRY_SEARCH_PARAM) ?? '').trim()
  return isExpiryFilter(value) ? value : 'all'
}

/** Copy of `search` with `expiry` set to `filter` (removed for 全部); other params kept. */
export function withExpirySearch(search: URLSearchParams, filter: ExpiryFilter): URLSearchParams {
  const next = new URLSearchParams(search)
  if (filter === 'all') next.delete(EXPIRY_SEARCH_PARAM)
  else next.set(EXPIRY_SEARCH_PARAM, filter)
  return next
}

/** Rule 1: 已过期未处理 DTE < 0; 今天到期 DTE = 0; N 天内 0 ≤ DTE ≤ N. */
export function matchesExpiryFilter(position: Pick<OptionPosition, 'dte'>, filter: ExpiryFilter) {
  const { dte } = position
  switch (filter) {
    case 'all':
      return true
    case 'expired':
      return dte < 0
    case 'today':
      return dte === 0
    case '7':
    case '14':
    case '30':
      return dte >= 0 && dte <= Number(filter)
  }
}

/** Keeps backend order (Q35); only drops non-matching rows. */
export function filterPositionsByExpiry<T extends Pick<OptionPosition, 'dte'>>(
  positions: T[],
  filter: ExpiryFilter,
): T[] {
  return filter === 'all' ? positions : positions.filter((p) => matchesExpiryFilter(p, filter))
}

/** Rule 6 empty-result text; null for 全部 (the page's own empty state is used). */
export function expiryEmptyMessage(filter: ExpiryFilter): string | null {
  switch (filter) {
    case 'all':
      return null
    case 'expired':
      return '没有已过期未处理的持仓'
    case 'today':
      return '没有今天到期的持仓'
    case '7':
    case '14':
    case '30':
      return `没有 ${filter} 天内到期的持仓`
  }
}

/** Positions page with the given filter (home page links, Q105). */
export function positionsUrlForExpiry(filter: ExpiryFilter): string {
  const search = withExpirySearch(new URLSearchParams(), filter).toString()
  return search === '' ? '/positions' : `/positions?${search}`
}

export const STOCK_HIDDEN_NOTICE = "股票持仓不参与到期过滤，选择'全部'查看"
