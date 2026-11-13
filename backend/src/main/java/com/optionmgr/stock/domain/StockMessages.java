package com.optionmgr.stock.domain;

import com.optionmgr.common.time.MarketTime;

/** FEAT-011 user-facing (Chinese) rejection texts, see docs/api/api-contract.md SHARES_INSUFFICIENT. */
public final class StockMessages {

    public static final String IDENTITY_NOT_EDITABLE = "标的或类型录错请删除后重新录入";

    private StockMessages() {
    }

    /** The new / edited sale itself: "持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股". */
    public static String saleExceedsHolding(String symbol, StockReplay.Step step) {
        return "持股不足：" + MarketTime.displayMinute(step.record().tradedAt()) + " 卖出 " + symbol + " "
                + step.record().shares() + " 股，当时持有 " + step.holdingBefore() + " 股";
    }

    /**
     * Another sale after a change ({@code verb} = 补录 / 修改 / 删除):
     * "补录后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股".
     */
    public static String laterSaleExceedsHolding(String verb, String symbol, StockReplay.Step step) {
        return verb + "后 " + MarketTime.displayMinute(step.record().tradedAt()) + " 的卖出 " + symbol + " "
                + step.record().shares() + " 股将超过当时持股 " + step.holdingBefore() + " 股";
    }

    /**
     * FEAT-041, the call assignment being recorded:
     * "持股不足：被指派需要 300 股 IXQZ，2026-11-20 16:00 时持有 150 股。请先录入股票（转入或更早的买入）".
     */
    public static String assignmentExceedsHolding(String symbol, StockReplay.Step step) {
        return "持股不足：被指派需要 " + step.record().shares() + " 股 " + symbol + "，"
                + MarketTime.displayMinute(step.record().tradedAt()) + " 时持有 " + step.holdingBefore()
                + " 股。请先录入股票（转入或更早的买入）";
    }

    /**
     * FEAT-041, another stored call assignment after a change ({@code verb} = 补录 / 修改 / 删除):
     * "删除后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 150 股，请先删除该被指派".
     */
    public static String laterAssignmentExceedsHolding(String verb, int contracts, String contractLabel,
                                                       StockReplay.Step step) {
        return verb + "后 " + MarketTime.displayMinute(step.record().tradedAt()) + " 的被指派 " + contracts + " 张 "
                + contractLabel + " 需要 " + step.record().shares() + " 股，当时持股 " + step.holdingBefore()
                + " 股，请先删除该被指派";
    }
}
