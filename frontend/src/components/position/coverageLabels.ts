import type { OptionPosition } from '../../api/types'
import { formatLocalDateTime } from '../../utils/format'

/** One coverage tag on a short-call row (FEAT-039 rule 7 / Q62). */
export interface CoverageTag {
  kind: 'checked' | 'legacy'
  label: string
  hint: string
}

/**
 * FEAT-039 (Q62): tags for a short-call row's 冻结现金 cell, built only from backend values.
 * 受检 → `已覆盖 {N} 股`; 旧批次 → `未覆盖` (`未覆盖 {k} 张` when the row also has 受检 lots).
 * Returns [] for other rows or when the backend sent no coverage fields.
 */
export function coverageTags(position: OptionPosition): CoverageTag[] {
  if (position.direction !== 'SHORT' || position.optionType !== 'CALL') return []
  const covered = position.coveredShares ?? 0
  const legacyContracts = position.uncoveredContracts ?? 0
  const tags: CoverageTag[] = []
  if (covered > 0) {
    tags.push({
      kind: 'checked',
      label: `已覆盖 ${String(covered)} 股`,
      hint: `占用 ${position.symbol} ${String(covered)} 股，平仓、作废或被指派前这些股票不能卖出`,
    })
  }
  if (legacyContracts > 0) {
    tags.push({
      kind: 'legacy',
      label: covered > 0 ? `未覆盖 ${String(legacyContracts)} 张` : '未覆盖',
      hint: legacyHint(position),
    })
  }
  return tags
}

function legacyHint(position: OptionPosition): string {
  const required = String(position.uncoveredShares ?? 0)
  const available = String(position.availableShares ?? 0)
  const head = `上线前录入，未纳入覆盖检查：需要 ${required} 股，当前可用 ${available} 股`
  const at = position.uncoveredShortfallAtLocal
  const held = position.uncoveredShortfallHeldShares
  const shortfall =
    at != null && held != null ? `；${formatLocalDateTime(at)} 时持有 ${String(held)} 股` : ''
  return `${head}${shortfall}。补录该时间之前的股票后自动变为已覆盖`
}
