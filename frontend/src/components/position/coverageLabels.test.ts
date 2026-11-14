import { describe, expect, it } from 'vitest'
import type { OptionPosition } from '../../api/types'
import { coverageTags } from './coverageLabels'

type CoverageFields = Pick<
  OptionPosition,
  | 'symbol'
  | 'direction'
  | 'optionType'
  | 'coveredShares'
  | 'uncoveredContracts'
  | 'uncoveredShares'
  | 'availableShares'
  | 'uncoveredShortfallAtLocal'
  | 'uncoveredShortfallHeldShares'
>

function row(partial: Partial<CoverageFields>): OptionPosition {
  const fields: CoverageFields = {
    symbol: 'IXQZ',
    direction: 'SHORT',
    optionType: 'CALL',
    coveredShares: 0,
    uncoveredContracts: 0,
    uncoveredShares: 0,
    availableShares: 0,
    uncoveredShortfallAtLocal: null,
    uncoveredShortfallHeldShares: null,
    ...partial,
  }
  // Only the coverage fields are read by coverageTags.
  return fields as OptionPosition
}

const LEGACY_HINT =
  '上线前录入，未纳入覆盖检查：需要 200 股，当前可用 0 股；2026-11-03 10:00 时持有 0 股。补录该时间之前的股票后自动变为已覆盖'

describe('coverageTags (FEAT-039 rule 7)', () => {
  it('labels a fully checked short call', () => {
    expect(coverageTags(row({ coveredShares: 100 }))).toEqual([
      {
        kind: 'checked',
        label: '已覆盖 100 股',
        hint: '占用 IXQZ 100 股，平仓、作废或被指派前这些股票不能卖出',
      },
    ])
  })

  it('labels a legacy-only row 未覆盖 with the shortfall hint', () => {
    const tags = coverageTags(
      row({
        uncoveredContracts: 2,
        uncoveredShares: 200,
        uncoveredShortfallAtLocal: '2026-11-03T10:00:00',
        uncoveredShortfallHeldShares: 0,
      }),
    )
    expect(tags).toEqual([{ kind: 'legacy', label: '未覆盖', hint: LEGACY_HINT }])
  })

  it('shows both labels, legacy with its contract count, when a row has both kinds', () => {
    const tags = coverageTags(
      row({
        coveredShares: 100,
        uncoveredContracts: 2,
        uncoveredShares: 200,
        uncoveredShortfallAtLocal: '2026-11-03T10:00:00',
        uncoveredShortfallHeldShares: 0,
      }),
    )
    expect(tags.map((tag) => tag.label)).toEqual(['已覆盖 100 股', '未覆盖 2 张'])
  })

  it('omits the time clause when the backend gives no shortfall time', () => {
    const [tag] = coverageTags(
      row({ uncoveredContracts: 1, uncoveredShares: 100, availableShares: 50 }),
    )
    expect(tag?.hint).toBe(
      '上线前录入，未纳入覆盖检查：需要 100 股，当前可用 50 股。补录该时间之前的股票后自动变为已覆盖',
    )
  })

  it('returns nothing for short puts, long calls, or rows without coverage fields', () => {
    expect(coverageTags(row({ optionType: 'PUT', coveredShares: null }))).toEqual([])
    expect(coverageTags(row({ direction: 'LONG', coveredShares: null }))).toEqual([])
    expect(coverageTags(row({ coveredShares: undefined, uncoveredContracts: undefined }))).toEqual(
      [],
    )
  })
})
