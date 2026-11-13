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

    /** "交易 #3 BTC 冻结变动 −24.70 → −24.69"; an expiry (FEAT-012): "到期作废 #17 冻结变动 −24.70 → −24.69". */
    public String text() {
        String subject = "EXP".equals(action) ? "到期作废 #" + tradeId : "交易 #" + tradeId + " " + action;
        return subject + " 冻结变动 " + before + " → " + after;
    }
}
