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

    /** FEAT-013: {@link #field()} of a corrected assignment's stock cost. */
    public static final String STOCK_COST = "stockCost";

    /**
     * "交易 #3 BTC 冻结变动 −24.70 → −24.69"; an expiry (FEAT-012): "到期作废 #17 冻结变动 −24.70 → −24.69"; an
     * assignment (FEAT-013): "被指派 #9 冻结变动 …" / "被指派 #9 股票成本 13,130.00 → 13,125.00".
     */
    public String text() {
        String subject = switch (action) {
            case "EXP" -> "到期作废 #" + tradeId;
            case "ASG" -> "被指派 #" + tradeId;
            default -> "交易 #" + tradeId + " " + action;
        };
        String label = STOCK_COST.equals(field) ? "股票成本" : "冻结变动";
        return subject + " " + label + " " + before + " → " + after;
    }
}
