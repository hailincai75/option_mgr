package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-023: boundaries (FIFO with several lots, put / call assignment, expiration day, weekend,
 * all rows not computable, card disappearing and re-appearing with full history, zero / negative values, no
 * history) and the endpoint's errors. See {@link Feat023UnderlyingPnlSystemTestBase}.
 */
@DisplayName("FEAT-023 按标的盈亏（边界与错误）")
class Feat023UnderlyingPnlBoundarySystemTest extends Feat023UnderlyingPnlSystemTestBase {

    // ============================================================================================== errors

    @Test
    @DisplayName("错误：无 X-Account-Id → 400 ACCOUNT_REQUIRED；非整数 → 400 BAD_REQUEST；不存在 → 404 ACCOUNT_NOT_FOUND；POST / PUT / DELETE → 405 METHOD_NOT_ALLOWED")
    void errors() {
        scenario023();
        assertErrorShape(noAccount(() -> get(UNDERLYING)), 400, "ACCOUNT_REQUIRED", UNDERLYING);
        assertErrorShape(underlyingResp("abc"), 400, "BAD_REQUEST", UNDERLYING);
        assertErrorShape(underlyingResp("999"), 404, "ACCOUNT_NOT_FOUND", UNDERLYING);
        assertErrorShape(exchange(HttpMethod.POST, UNDERLYING, null), 405, "METHOD_NOT_ALLOWED", UNDERLYING);
        assertErrorShape(exchange(HttpMethod.PUT, UNDERLYING, "{}"), 405, "METHOD_NOT_ALLOWED", UNDERLYING);
        assertErrorShape(exchange(HttpMethod.DELETE, UNDERLYING, null), 405, "METHOD_NOT_ALLOWED", UNDERLYING);
    }

    @Test
    @DisplayName("多余的 query 参数忽略（如 startDate、expiration 过滤）：结果与不带参数相同，已实现仍为全部时间（Q195、Q158）")
    void extraQueryParametersIgnored() {
        scenario023Pulled();
        JsonNode plain = underlying("1");
        for (String q : new String[] {"?startDate=2026-11-01&endDate=2026-11-30", "?expiration=2026-12-18",
                "?symbol=QRTV", "?foo=bar"}) {
            Resp r = get(UNDERLYING + q);
            assertThat(r.status()).as("GET %s%s -> %s", UNDERLYING, q, r.body()).isEqualTo(200);
            assertThat(r.body()).as("query %s ignored", q).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("行情从未拉取：所有行无报价 / 已到期 → 每组市值、未实现 null，合计 = 已实现；净权利金照算（不需要报价）")
    void noQuotesAtAll() {
        scenario023();
        setNow("2026-11-23T10:00");
        assertThat(groupLines("1")).containsExactly(
                "IXQZ | 1 80.00 (1 80.00 · 0 0.00) | mv null | upnl null | total 80.00"
                        + " | prem 290.00 | opt 2 stock false | 0/1/1 | 1 个持仓无报价、1 个持仓已到期待处理，未计入",
                "QRTV | 1 39.70 (1 39.70 · 0 0.00) | mv null | upnl null | total 39.70"
                        + " | prem 165.05 | opt 3 stock true | 0/4/0 | 4 个持仓无报价，未计入",
                G_ZWLK);
        assertConsistent("1");
    }

    // ============================================================================================== FIFO / lots

    @Test
    @DisplayName("同一合约两批开仓 + BTC 1 张：FIFO 平掉最早的 T2（120.35）→ 已实现 +99.40；45P 剩 1 张 110.00；净权利金 275.05 → 154.70")
    void fifoTwoLots() {
        scenario023();
        openN("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "110.00", "2026-11-05T10:00"); // T4
        sources023();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(val(optRow("1", QRTV_45P))).isEqualTo(okVal("-220.00", "10.35", "4.49"));
        assertThat(groupLine("1", "QRTV")).isEqualTo(
                "QRTV | 1 39.70 (1 39.70 · 0 0.00) | mv 4750.00 | upnl 225.05 | total 264.75"
                        + " | prem 275.05 | opt 3 stock true | 4/0/0 | null");
        assertConsistent("1");

        setNow("2026-11-23T10:31");
        closeN("BTC", 1, "QRTV", "2026-12-18", "45", "PUT", "60.65", "2026-11-23T10:30");
        // FIFO: T2 120.35 − 60.65 = 59.70 (LIFO would give 110.00 − 60.65 = 49.35)
        assertThat(val(optRow("1", QRTV_45P))).isEqualTo(okVal("-110.00", "0.00", "0.00"));
        assertThat(groupLine("1", "QRTV")).isEqualTo(
                "QRTV | 2 99.40 (2 99.40 · 0 0.00) | mv 4860.00 | upnl 214.70 | total 314.10"
                        + " | prem 154.70 | opt 3 stock true | 4/0/0 | null");
        assertConsistent("1");
    }

    // ============================================================================================== expiration day / weekend

    @Test
    @DisplayName("到期日当天 12-18（周五）照常计算（保留 11-23 的报价）；周六 12-19：QRTV 三个期权、IXQZ 两个期权都已到期待处理 → IXQZ 全不可算 null，QRTV 只算股票；净权利金不变")
    void expirationDayThenWeekend() {
        scenario023Pulled();
        setNow("2026-12-18T10:00");
        JsonNode body = underlying("1");
        assertThat(body.get("asOfDate").asText()).isEqualTo("2026-12-18");
        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV, G_ZWLK);
        assertConsistent("1");

        setNow("2026-12-19T11:00");
        assertThat(underlying("1").get("asOfDate").asText()).isEqualTo("2026-12-19");
        assertThat(groupLines("1")).containsExactly(
                "IXQZ | 1 80.00 (1 80.00 · 0 0.00) | mv null | upnl null | total 80.00"
                        + " | prem 290.00 | opt 2 stock false | 0/0/2 | 2 个持仓已到期待处理，未计入",
                "QRTV | 1 39.70 (1 39.70 · 0 0.00) | mv 5000.00 | upnl 200.00 | total 239.70"
                        + " | prem 165.05 | opt 3 stock true | 1/0/3 | 3 个持仓已到期待处理，未计入",
                G_ZWLK);
        assertConsistent("1");
    }

    // ============================================================================================== assignment

    @Test
    @DisplayName("put 被指派（12-18 18:00 IXQZ 60P）：期权已实现 0.00 计 1 笔（2 笔 +80.00）；60P 离开净权利金（90.00）；股票 100 股成本 5,800.00 按上次现价 61.00 → +6,100.00 / +300.00；删除指派 → 恢复")
    void putAssignment() {
        scenario023Pulled();
        setNow("2026-12-18T18:00");
        long asg = assignId("IXQZ", "2026-12-18", "60", "PUT", 1, "2026-12-18");
        assertThat(stkRow("1", "IXQZ").get("totalCost").asText()).isEqualTo("5800.00");
        assertThat(groupLine("1", "IXQZ")).isEqualTo(
                "IXQZ | 2 80.00 (2 80.00 · 0 0.00) | mv 6100.00 | upnl 300.00 | total 380.00"
                        + " | prem 90.00 | opt 1 stock true | 1/0/1 | " + LABEL_1_EXPIRED);
        assertConsistent("1");

        deleteOptionTrade(asg);
        assertThat(groupLine("1", "IXQZ")).isEqualTo(G_IXQZ);
        assertConsistent("1");
    }

    @Test
    @DisplayName("call 被指派（12-18 18:00 QRTV 55C）：QRTV 股票交出，股票卖出已实现 5,500.00 + 85.35 − 4,800.00 = +785.35；期权 0.00 计 1 笔；组仍在（还有 40P / 45P），hasStockPosition false")
    void callAssignment() {
        scenario023Pulled();
        setNow("2026-12-18T18:00");
        assignId("QRTV", "2026-12-18", "55", "CALL", 1, "2026-12-18");
        assertThat(groupLine("1", "QRTV")).isEqualTo(
                "QRTV | 3 825.05 (2 39.70 · 1 785.35) | mv -80.00 | upnl -0.30 | total 824.75"
                        + " | prem 79.70 | opt 2 stock false | 2/0/0 | null");
        assertConsistent("1");
    }

    // ============================================================================================== card lifecycle

    @Test
    @DisplayName("ZWLK 卖完 → 组消失（已实现仍在盈亏汇总 +160.00）；再买入 10 股 → 组重新出现，已实现含之前全部历史（股票 2 笔 +160.00）")
    void groupDisappearsAndReappearsWithHistory() {
        scenario023Pulled();
        setNow("2026-11-23T10:20");
        stockTradeId("SELL", "ZWLK", 30, "960.00", "2026-11-23T10:10");
        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV);
        assertThat(pnlBySymbol("1")).contains("ZWLK | 2 160.00 (0 0.00 · 2 160.00)");
        assertConsistent("1");

        stockTradeId("BUY", "ZWLK", 10, "320.00", "2026-11-23T10:15");
        assertThat(groupLines("1")).containsExactly(G_IXQZ, G_QRTV,
                "ZWLK | 2 160.00 (0 0.00 · 2 160.00) | mv null | upnl null | total 160.00"
                        + " | prem null | opt 0 stock true | 0/1/0 | " + LABEL_1_NO_QUOTE);
        assertConsistent("1");
    }

    @Test
    @DisplayName("QRTV 全部期权平掉、股票仍在 → 组保留，netPremium null（无期权持仓），optionPositionCount 0")
    void stockOnlyAfterClosingAllOptions() {
        scenario023Pulled();
        setNow("2026-11-23T10:31");
        closeN("BTC", 1, "QRTV", "2026-12-18", "55", "CALL", "60.65", "2026-11-23T10:30");
        closeN("BTC", 1, "QRTV", "2026-12-18", "45", "PUT", "110.65", "2026-11-23T10:30");
        closeN("STC", 1, "QRTV", "2026-12-18", "40", "PUT", "29.35", "2026-11-23T10:30");
        // 39.70 + (85.35 − 60.65) + (120.35 − 110.65) + (29.35 − 40.65) = 39.70 + 24.70 + 9.70 − 11.30 = 62.80
        assertThat(groupLine("1", "QRTV")).isEqualTo(
                "QRTV | 4 62.80 (4 62.80 · 0 0.00) | mv 5000.00 | upnl 200.00 | total 262.80"
                        + " | prem null | opt 0 stock true | 1/0/0 | null");
        assertConsistent("1");
    }

    // ============================================================================================== zero / negative values

    @Test
    @DisplayName("新账户只有期权、无历史：市值合计恰为 0.00（非 null、非 -0.00）；净权利金可为负；已实现 0 笔 0.00")
    void zeroMarketValueAndNegativePremium() {
        scenario023Pulled();
        String d = createAccount("账户D");
        accountId = d;
        deposit("10000.00", "2026-11-16T10:00");
        // long 55C (mark 0.60 → +60.00) + short 2 × 40P (mark 0.30 → −60.00) → market value 0.00
        openN("BTO", 1, "QRTV", "2026-12-18", "55", "CALL", "70.00", "2026-11-20T10:00");
        openN("STO", 2, "QRTV", "2026-12-18", "40", "PUT", "80.00", "2026-11-20T10:05");
        accountId = "1";
        // 55C: −70.00 + 60.00 = −10.00; 40P: 80.00 − 60.00 = +20.00; net premium −70.00 + 80.00 = 10.00
        assertThat(groupLines(d)).containsExactly(
                "QRTV | 0 0.00 (0 0.00 · 0 0.00) | mv 0.00 | upnl 10.00 | total 10.00"
                        + " | prem 10.00 | opt 2 stock false | 2/0/0 | null");
        assertConsistent(d);

        String e = createAccount("账户E");
        accountId = e;
        deposit("10000.00", "2026-11-16T10:00");
        openN("BTO", 1, "QRTV", "2026-12-18", "55", "CALL", "70.00", "2026-11-20T10:00");
        accountId = "1";
        assertThat(groupLines(e)).containsExactly(
                "QRTV | 0 0.00 (0 0.00 · 0 0.00) | mv 60.00 | upnl -10.00 | total -10.00"
                        + " | prem -70.00 | opt 1 stock false | 1/0/0 | null");
        assertConsistent(e);
    }

    @Test
    @DisplayName("只有已过期未处理的期权（无报价）→ marketValue / unrealizedPnl null，netPremium 照算，提示只写已到期")
    void onlyExpiredPendingOption() {
        scenario023Pulled();
        String f = createAccount("账户F");
        accountId = f;
        deposit("10000.00", "2026-11-01T10:00");
        openN("STO", 1, "IXQZ", "2026-11-20", "58", "PUT", "95.00", "2026-11-02T10:00");
        accountId = "1";
        assertThat(groupLines(f)).containsExactly(
                "IXQZ | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00"
                        + " | prem 95.00 | opt 1 stock false | 0/0/1 | " + LABEL_1_EXPIRED);
        assertConsistent(f);
    }
}
