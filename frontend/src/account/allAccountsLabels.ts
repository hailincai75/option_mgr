/** FEAT-035 texts (requirement rules 1, 5, 7). */
export const ALL_ACCOUNTS_LABEL = '全部账户'
/** Rule 1: shown in the header switcher while 全部账户 is selected. */
export const ALL_ACCOUNTS_CURRENT_TEXT = '全部账户（只读）'
/** Rule 5: one line at the top of every page in 全部账户 mode. */
export const ALL_ACCOUNTS_READ_ONLY_HINT = '全部账户（只读）：录入、修改、删除请先选择一个账户'
/** Rule 5: entry pages (/trades/new, /stocks/new) opened in 全部账户 mode. */
export const ENTRY_NEEDS_ACCOUNT_TEXT = '录入需要选择一个账户'
/** Rule 7 (Q223 / Q225): pages (or sections) that do not support 全部账户 yet. */
export const PAGE_NOT_SUPPORTED_TEXT = '此页暂不支持全部账户，请选择一个账户查看'
/** Rule 7 (Q224): in place of the FEAT-019 portfolio Greeks summary. */
export const GREEKS_SUMMARY_SINGLE_ONLY_TEXT = '组合 Greeks 汇总暂只支持单个账户'

/** Q221: per-account link of an ad-hoc closure alert in 全部账户 mode. */
export function switchToAdjustText(accountName: string): string {
  return `切换到 ${accountName} 去调整`
}
