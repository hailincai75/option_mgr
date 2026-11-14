import type { OptionPosition } from '../../api/types'

export const ADHOC_CLOSURE_TAG_TEXT = '临时休市'

/**
 * FEAT-042 去调整 on the positions page: the first row (backend order) the backend flags as expiring
 * on that ad-hoc closure date. Only compares the backend's flag and dates; no date logic here.
 */
export function firstAdhocClosureRow(
  positions: readonly OptionPosition[],
  date: string,
): OptionPosition | undefined {
  return positions.find(
    (position) => position.expiresOnAdhocClosure === true && position.expirationDate === date,
  )
}
