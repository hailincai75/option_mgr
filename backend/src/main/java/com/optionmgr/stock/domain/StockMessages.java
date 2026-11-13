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
}
