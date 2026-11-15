import type { GreeksSummaryGroup, GreeksSummaryPortfolio } from '../../api/types'
import { EMPTY_VALUE, formatMoney } from '../../utils/format'

/** FEAT-019 block title (Q175). */
export const GREEKS_SUMMARY_TITLE = '按标的 Greeks 汇总'

/** FEAT-019 gray line above the table (requirement 页面与交互 / 说明). */
export const GREEKS_SUMMARY_NOTE =
  '按标的汇总当前账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考'

/** FEAT-047: the same line in 全部账户 mode (each symbol merged across accounts, Q224). */
export const GREEKS_SUMMARY_NOTE_ALL_ACCOUNTS =
  '按标的汇总全部账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考'

/** Plain-language hints (requirement 白话解释), shown on hover of the `?`. */
export const GREEKS_SUMMARY_HINTS = {
  title:
    '组合 Greeks：把同一标的的所有期权持仓 Greeks 和股票加在一起，看这个标的整体的敏感度。不同标的的"股数"不能直接相加（1 股 QRTV ≠ 1 股 IXQZ），美元敞口、Theta、Vega 都是美元，可以跨标的相加。',
  stockDelta: '每股股票的 Delta 固定为 1，持有 100 股 = Delta +100；股票没有 Gamma / Theta / Vega。',
  netDelta:
    '净 Delta（股数）：期权 Delta + 股票 Delta = 这个标的整体相当于持有多少股（正 = 涨了赚，负 = 跌了赚）。',
  exposure:
    'Delta 美元敞口 = 净 Delta 股数 × 标的价 ≈ 相当于持有多少美元的这只股票。例：净 Delta +87.21、标的价 52.40 → 约 +4,569.80，即标的涨 1%，大约赚 45.70。',
} as const

export const PORTFOLIO_LABEL = '全组合'

/** A Greek / Delta number with an explicit sign (`+87.21` / `−18.35` / `0.00`), `—` when null. */
export function signedValue(value: string | null): string {
  return formatMoney(value, { signed: true })
}

/** 标的价 cell: the backend price as-is (no sign); `underlyingPriceLabel` (`无标的价`) when null. */
export function underlyingPriceText(group: GreeksSummaryGroup): { text: string; muted: boolean } {
  if (group.underlyingPrice === null) {
    return { text: group.underlyingPriceLabel ?? EMPTY_VALUE, muted: true }
  }
  return { text: formatMoney(group.underlyingPrice), muted: false }
}

/**
 * Delta 美元敞口 cell (formatting only): OK → signed with thousands separators; NO_UNDERLYING_PRICE →
 * the backend's `exposureLabel` (`无标的价`); NO_DELTA (or anything else) → `—`.
 */
export function exposureText(group: GreeksSummaryGroup): { text: string; muted: boolean } {
  if (group.exposureStatus === 'OK') {
    return { text: signedValue(group.dollarExposure), muted: false }
  }
  if (group.exposureStatus === 'NO_UNDERLYING_PRICE') {
    return { text: group.exposureLabel ?? EMPTY_VALUE, muted: true }
  }
  return { text: EMPTY_VALUE, muted: true }
}

export type GroupNoteKey = 'manual-price' | 'excluded' | 'missing'

/** The row's gray notes in contract order (manual price, excluded, missing); only the non-null ones. */
export function groupNotes(group: GreeksSummaryGroup): { key: GroupNoteKey; text: string }[] {
  const notes: { key: GroupNoteKey; text: string }[] = []
  if (group.manualPriceNote !== null) notes.push({ key: 'manual-price', text: group.manualPriceNote })
  if (group.excludedLabel !== null) notes.push({ key: 'excluded', text: group.excludedLabel })
  if (group.missingLabel !== null) notes.push({ key: 'missing', text: group.missingLabel })
  return notes
}

export type PortfolioNoteKey = 'excluded' | 'no-underlying-price' | 'missing'

/** Notes below the 全组合 row, in contract order; only the non-null ones. */
export function portfolioNotes(
  portfolio: GreeksSummaryPortfolio,
): { key: PortfolioNoteKey; text: string }[] {
  const notes: { key: PortfolioNoteKey; text: string }[] = []
  if (portfolio.excludedLabel !== null) notes.push({ key: 'excluded', text: portfolio.excludedLabel })
  if (portfolio.noUnderlyingPriceLabel !== null) {
    notes.push({ key: 'no-underlying-price', text: portfolio.noUnderlyingPriceLabel })
  }
  if (portfolio.missingLabel !== null) notes.push({ key: 'missing', text: portfolio.missingLabel })
  return notes
}
