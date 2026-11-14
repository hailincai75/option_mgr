import type { OptionGreeks } from '../../api/types'
import { EMPTY_VALUE, formatMoney, formatPlainPercent } from '../../utils/format'

/** The four Greeks with a position value (IV has only the per-contract percent, Q168). */
export type GreekKey = 'delta' | 'gamma' | 'theta' | 'vega'

export const GREEK_KEYS: readonly GreekKey[] = ['delta', 'gamma', 'theta', 'vega']

/** Column keys of the five FEAT-018 columns, in table order (after 未实现盈亏, Q173). */
export type GreeksColumnKey = 'iv' | GreekKey

export const GREEKS_COLUMNS: readonly { key: GreeksColumnKey; title: string; hint: string }[] = [
  {
    key: 'iv',
    title: 'IV',
    hint: '隐含波动率：市场预期标的未来一年的波动幅度（年化百分比）；越高，期权越贵。只显示每张值。',
  },
  {
    key: 'delta',
    title: 'Delta',
    hint: '标的涨 1 美元，期权每股价格大约变多少（小字为每张原值）。持仓值 ≈ 这笔仓位相当于多少股标的（正 = 看涨，负 = 看跌）。',
  },
  {
    key: 'gamma',
    title: 'Gamma',
    hint: '标的涨 1 美元，Delta 变多少；越大说明仓位方向变化越快，临近到期、平值附近最大。持仓值 = 标的涨 1 美元时等价股数的变化。',
  },
  {
    key: 'theta',
    title: 'Theta',
    hint: '其它不变，过一天期权每股价格大约变多少（时间价值流失）。持仓值 = 每天大约赚 / 亏多少美元；卖方通常为正。',
  },
  {
    key: 'vega',
    title: 'Vega',
    hint: 'IV 上升 1 个百分点，期权每股价格大约变多少。持仓值为美元：IV 每上升 1 个百分点，这笔仓位大约赚 / 亏多少。',
  },
]

/** FEAT-018 requirement (Q169, Q161 — multiplier written as 100): note above the table. */
export const GREEKS_NOTE =
  'IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考'

export const GREEKS_TOGGLE_LABEL = '显示 Greeks'

/**
 * What the five Greeks cells of one row show. Formatting only — every number and label is the
 * backend's (FEAT-018 rule 8):
 * - `label`: status ≠ OK → the backend's `label` (`无报价` / `已到期，待处理`) in all five columns, gray;
 * - `value`: `iv` (`35.12%`, `—` when null) and per Greek `position` with an explicit sign
 *   (`+30.21` / `−5.12` / `0.00`) and `perContract` (`−0.3021` / `0.0512`), both `—` when null (Q172);
 *   `manualMarkNote` verbatim (null when none);
 * - `missing`: older response without `greeks` → every cell `—`.
 */
export type GreeksDisplay =
  | { kind: 'label'; label: string }
  | { kind: 'missing' }
  | {
      kind: 'value'
      iv: string
      greeks: Record<GreekKey, { position: string; perContract: string }>
      manualMarkNote: string | null
    }

export function greeksDisplay(greeks: OptionGreeks | undefined): GreeksDisplay {
  if (greeks === undefined) return { kind: 'missing' }
  if (greeks.status !== 'OK') return { kind: 'label', label: greeks.label ?? EMPTY_VALUE }
  const pair = (position: string | null, perContract: string | null) => ({
    position: formatMoney(position, { signed: true }),
    perContract: formatMoney(perContract),
  })
  return {
    kind: 'value',
    iv: formatPlainPercent(greeks.ivPercent),
    greeks: {
      delta: pair(greeks.deltaPosition, greeks.deltaPerContract),
      gamma: pair(greeks.gammaPosition, greeks.gammaPerContract),
      theta: pair(greeks.thetaPosition, greeks.thetaPerContract),
      vega: pair(greeks.vegaPosition, greeks.vegaPerContract),
    },
    manualMarkNote: greeks.manualMarkNote,
  }
}

/** localStorage key remembering the 显示 Greeks switch (default off, Q173). */
export const SHOW_GREEKS_STORAGE_KEY = 'option-mgr.showGreeks'

/** Whether 显示 Greeks was left on in this browser; false when unset or storage is unavailable. */
export function readShowGreeks(): boolean {
  try {
    return window.localStorage.getItem(SHOW_GREEKS_STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

export function writeShowGreeks(show: boolean): void {
  try {
    if (show) window.localStorage.setItem(SHOW_GREEKS_STORAGE_KEY, 'true')
    else window.localStorage.removeItem(SHOW_GREEKS_STORAGE_KEY)
  } catch {
    // Private mode / blocked storage: the choice is just not remembered.
  }
}
