package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;

/** FEAT-039 rule 7: rejection texts E1 / E2 / E3 (shares as plain integers, times New York "YYYY-MM-DD HH:mm"). */
public final class CoverageMessages {

    private CoverageMessages() {
    }

    /** E1: "需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）"; C = 0: "…（持有 0 股）". */
    public static String callNotCovered(long needed, String symbol, CoverageTimeline.State before) {
        String covered = before.covered() == 0 ? "" : "，其中 " + before.covered() + " 股已覆盖其它 call";
        return "需要 " + needed + " 股 " + symbol + "，可用 " + before.available() + " 股（持有 " + before.held() + " 股"
                + covered + "）";
    }

    /** E2: "卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call". */
    public static String saleOfCoveredShares(long shares, CoverageTimeline.State before) {
        String covered = before.covered() == 0 ? "" : "，其中 " + before.covered() + " 股被 call 覆盖";
        return "卖出 " + shares + " 股超过可用股数 " + before.available() + " 股（持有 " + before.held() + " 股" + covered
                + "），请先平掉对应的 call";
    }

    /** E3: "删除后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股". */
    public static String changeUncovers(String verb, String symbol, CoverageTimeline.Shortfall s) {
        return verb + "后 " + MarketTime.displayMinute(s.at()) + " 起 " + symbol + " 不足以覆盖卖出的 call：需要 "
                + s.required() + " 股，持有 " + s.held() + " 股";
    }
}
