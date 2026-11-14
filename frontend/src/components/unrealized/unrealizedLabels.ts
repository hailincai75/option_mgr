import type { UnrealizedFields } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatMoney, formatPercent } from '../../utils/format'

/** FEAT-017 requirement: note under the positions header (shown verbatim). */
export const UNREALIZED_NOTE =
  '未实现盈亏已含开仓手续费，未扣平仓手续费；按 mark 计算，仅供参考'

/**
 * What the 市值 / 未实现盈亏 cells of one row show. Formatting only — every number and label is
 * the backend's (FEAT-017 rule 8):
 * - `label`: status ≠ OK → the backend's `unrealizedLabel` (`无报价` / `已到期，待处理`), shown gray in
 *   both cells without a percent;
 * - otherwise `marketValue` and `pnl`, both with an explicit sign (`+6,110.00`, `−125.00`, `0.00`;
 *   user decision for every market value), and `percent` (`+22.80%`; `—` when the backend gives null, e.g. a 0.00 denominator);
 * - fields missing (older responses) → `—`.
 */
export type UnrealizedDisplay =
  | { kind: 'label'; label: string }
  | { kind: 'value'; marketValue: string; pnl: string; percent: string; sign: -1 | 0 | 1 }

export function unrealizedDisplay(row: UnrealizedFields): UnrealizedDisplay {
  const status = row.unrealizedStatus
  if (status !== undefined && status !== 'OK') {
    return { kind: 'label', label: row.unrealizedLabel ?? EMPTY_VALUE }
  }
  return {
    kind: 'value',
    marketValue: formatMoney(row.marketValue, { signed: true }),
    pnl: formatMoney(row.unrealizedPnl, { signed: true }),
    percent: formatPercent(row.unrealizedPnlPercent),
    sign: decimalSign(row.unrealizedPnl),
  }
}
