package com.optionmgr.audit.domain;

/**
 * Q42: a system correction of another trade caused by an edit/delete, formatted for display.
 *
 * @param tradeId       the corrected trade
 * @param action        its action (e.g. BTC)
 * @param contractLabel its contract label
 * @param field         corrected property (currently always {@code frozenChange})
 * @param before        display value before, e.g. −24.70
 * @param after         display value after, e.g. −24.69
 */
public record SideEffect(long tradeId, String action, String contractLabel, String field, String before,
                         String after) {

    /** "交易 #3 BTC 冻结变动 −24.70 → −24.69". */
    public String text() {
        return "交易 #" + tradeId + " " + action + " 冻结变动 " + before + " → " + after;
    }
}
