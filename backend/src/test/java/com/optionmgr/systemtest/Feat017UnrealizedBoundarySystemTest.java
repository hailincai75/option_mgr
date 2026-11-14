package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-017: boundaries (Q152–Q158, Q154 rounding, status priority, expiration day, index symbols,
 * missing-from-source, FIFO / proportional remaining cost, assignment stock, weekend) and the summary endpoint's
 * errors. See {@link Feat017UnrealizedSystemTestBase}.
 */
@DisplayName("FEAT-017 未实现盈亏与市值（边界与错误）")
class Feat017UnrealizedBoundarySystemTest extends Feat017UnrealizedSystemTestBase {

    // ============================================================================================== status priority

    @Test
    @DisplayName("Q156 已过期 + 手动 mark：仍 EXPIRED_PENDING（优先于有 mark）；已过期从未报价：EXPIRED_PENDING（优先于 NO_QUOTE）")
    void expiredPendingHasPriority() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-11-20", "40", "PUT", "30.00", "2026-11-17T09:41");
        setNow("2026-11-23T10:00");

        // never quoted + expired → EXPIRED_PENDING (not NO_QUOTE)
        JsonNode never = optRow("1", "QRTV 2026-11-20 40 P");
        assertThat(never.get("optionQuote").get("mark").isNull()).isTrue();
        assertThat(val(never)).isEqualTo(EXPIRED_VAL);

        // manual mark on an expired contract → still not computed
        overrideMark("QRTV", "2026-11-20", "45", "PUT", "0.10");
        JsonNode manual = optRow("1", C_EXPIRED);
        assertThat(manual.get("optionQuote").get("mark").asText()).isEqualTo("0.10");
        assertThat(val(manual)).isEqualTo(EXPIRED_VAL);

        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("0.00 0.00 0/0/2");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("2 个持仓已到期待处理，未计入");
    }

    @Test
    @DisplayName("到期日当天正常计算（10:00 链中有报价；16:20 收盘后链中消失仍按最后报价算）；次日变为已到期，待处理")
    void expirationDayStillComputed() {
        setNow("2026-11-20T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-23", "45", "PUT", "60.00", "2026-11-20T09:40");
        String c = "QRTV 2026-11-23 45 P";
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00",
                List.of(option("QRTV261123P00045000", "bid", "0.04", "ask", "0.06"))))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode row = optRow("1", c);
        assertThat(row.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(val(row)).isEqualTo(okVal("-5.00", "55.00", "91.67"));

        // after the close the chain no longer lists it → last quote kept, still computed today
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.60", "2026-11-23 16:15:00", List.of()))).apply();
        tickAt("2026-11-23T16:20", "CLOSE");
        setNow("2026-11-23T16:30");
        row = optRow("1", c);
        assertThat(row.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(val(row)).isEqualTo(okVal("-5.00", "55.00", "91.67"));
        assertThat(grp(summary("1").get("option"))).isEqualTo("-5.00 55.00 1/0/0");

        // next day (Tue) → expired pending
        setNow("2026-11-24T09:00");
        assertThat(val(optRow("1", c))).isEqualTo(EXPIRED_VAL);
        JsonNode s = summary("1");
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-24");
        assertThat(grp(s.get("option"))).isEqualTo("0.00 0.00 0/0/1");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓已到期待处理，未计入");
    }

    @Test
    @DisplayName("周末：不拉取，按库里最后报价照算；周六 asOfDate 为周六，周五到期的持仓变为已到期，待处理")
    void weekendUsesLastQuote() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-17T09:45");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-20 15:45:00",
                List.of(option(OCC_C_EXPIRED, "bid", "0.01", "ask", "0.03"),
                        option(OCC_C_P1, "bid", "1.20", "ask", "1.30"))))).apply();
        tickAt("2026-11-20T16:00", "SCHEDULED");
        assertThat(val(optRow("1", C_EXPIRED))).as("Friday = expiration day").isEqualTo(okVal("-2.00", "58.00", "96.67"));

        tickSkipped("2026-11-21T11:00", "NON_TRADING_DAY");
        assertThat(val(optRow("1", C_P1))).isEqualTo(okVal("-125.00", "-6.30", "-5.31"));
        assertThat(val(optRow("1", C_EXPIRED))).isEqualTo(EXPIRED_VAL);
        JsonNode s = summary("1");
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-21");
        assertThat(grp(s.get("option"))).isEqualTo("-125.00 -6.30 1/0/1");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓已到期待处理，未计入");
    }

    // ============================================================================================== quotes

    @Test
    @DisplayName("Q149 指数期权（SPX）：无报价 → NO_QUOTE、不计入；手动 mark 后照算；清除后又不计入")
    void indexOptionNoQuoteThenManual() {
        scenario017();
        openN("BTO", 1, "SPX", "2026-12-18", "7000", "CALL", "500.00", "2026-11-20T11:00");
        sources017();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        String spx = "SPX 2026-12-18 7000 C";

        assertThat(val(optRow("1", spx))).isEqualTo(NO_QUOTE_VAL);
        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("160.00 61.75 3/2/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("2 个持仓无报价，未计入");

        setNow("2026-11-23T10:05");
        overrideMark("SPX", "2026-12-18", "7000", "CALL", "5.10");
        assertThat(val(optRow("1", spx))).isEqualTo(okVal("510.00", "10.00", "2.00"));
        s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("670.00 71.75 4/1/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("1 个持仓无报价，未计入");

        assertThat(deleteMarkOverride("?symbol=SPX&expirationDate=2026-12-18&strike=7000&optionType=CALL").status())
                .isEqualTo(204);
        assertThat(val(optRow("1", spx))).isEqualTo(NO_QUOTE_VAL);
        assertThat(grp(summary("1").get("option"))).isEqualTo("160.00 61.75 3/2/0");
    }

    @Test
    @DisplayName("bid 0 且无 theo → mark null → NO_QUOTE；未过期合约从链中消失（数据源已无该合约）→ 用最后报价照算")
    void bidZeroNoTheoAndMissingFromSource() {
        scenarioPulled();
        // 10:15: P2 bid 0 / ask 0.05 without theo; P1 gone from the chain
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 10:00:00",
                        List.of(option(OCC_C_P2, "bid", "0", "ask", "0.05")))))
                .cboe("IXQZ", ixqz017("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");

        JsonNode p2 = optRow("1", C_P2);
        assertThat(p2.get("optionQuote").get("mark").isNull()).isTrue();
        assertThat(val(p2)).isEqualTo(NO_QUOTE_VAL);
        JsonNode p1 = optRow("1", C_P1);
        assertThat(p1.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(val(p1)).isEqualTo(okVal("-125.00", "-6.30", "-5.31"));

        JsonNode s = summary("1");
        assertThat(grp(s.get("option"))).isEqualTo("-210.00 -6.95 2/2/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("2 个持仓无报价，未计入");
    }

    @Test
    @DisplayName("股票现价为空（从未拉到）→ NO_QUOTE、不计入；手动股价后照算（Q154：3 位小数 × 33 股 HALF_UP 2016.465 → 2016.47；4 位小数）")
    void stockNoQuoteManualAndRounding() {
        setNow("2026-11-23T09:50");
        transferInShares("ZQMB", 33, "2000.00", "2026-11-19");
        transferInShares("ZQMC", 7, "100.00", "2026-11-19");
        sources().apply(); // all unreachable
        tickAt("2026-11-23T10:00", "SCHEDULED");

        assertThat(val(stkRow("1", "ZQMB"))).isEqualTo(NO_QUOTE_VAL);
        JsonNode s = summary("1");
        assertThat(grp(s.get("stock"))).isEqualTo("0.00 0.00 0/2/0");
        assertThat(text(s.get("excludedLabel"))).isEqualTo("2 个持仓无报价，未计入");

        setNow("2026-11-23T10:05");
        overrideUnderlying("ZQMB", "61.105"); // 61.105 × 33 = 2016.465 → HALF_UP 2016.47 (HALF_EVEN would be .46)
        overrideUnderlying("ZQMC", "12.3456"); // 12.3456 × 7 = 86.4192 → 86.42
        assertThat(val(stkRow("1", "ZQMB"))).isEqualTo(okVal("2016.47", "16.47", "0.82"));
        assertThat(val(stkRow("1", "ZQMC"))).isEqualTo(okVal("86.42", "-13.58", "-13.58"));
        s = summary("1");
        assertThat(grp(s.get("stock"))).isEqualTo("2102.89 2.89 2/0/0");
        assertThat(s.get("excludedLabel").isNull()).isTrue();
        assertRowInvariants("1");
    }

    @Test
    @DisplayName("Q154 期权 mark 有 5 位小数（CBOE 0.1002 / 0.1003 → 0.10025）：空头市值 10.025 → −10.03（HALF_UP，对称），盈亏用取整后的市值")
    void optionMarketValueRounding() {
        setNow("2026-11-23T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-12-18", "30", "PUT", "20.00", "2026-11-20T10:00");
        openN("BTO", 1, "QRTV", "2026-12-18", "31", "PUT", "20.00", "2026-11-20T10:01");
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00", List.of(
                option("QRTV261218P00030000", "bid", "0.1002", "ask", "0.1003"),
                option("QRTV261218P00031000", "bid", "0.1002", "ask", "0.1003"))))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode shortRow = optRow("1", "QRTV 2026-12-18 30 P");
        assertThat(shortRow.get("optionQuote").get("mark").asText()).isEqualTo("0.10025");
        assertThat(val(shortRow)).isEqualTo(okVal("-10.03", "9.97", "49.85"));
        JsonNode longRow = optRow("1", "QRTV 2026-12-18 31 P");
        assertThat(val(longRow)).isEqualTo(okVal("10.03", "-9.97", "-49.85"));
        assertThat(grp(summary("1").get("option"))).as("sum of rounded rows, not re-rounded").isEqualTo("0.00 0.00 2/0/0");
    }

    // ============================================================================================== cost basis

    @Test
    @DisplayName("同一合约多次开仓 + BTC 整笔平掉最早一笔（FIFO）→ 用剩余开仓净额计算")
    void fifoRemainingOpeningNet() {
        setNow("2026-11-23T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-19T10:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "130.00", "2026-11-20T10:00");
        sources().cboe("QRTV", qrtv017("2026-11-23 09:45:00")).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode r = optRow("1", C_P1);
        assertThat(r.get("contracts").asInt()).isEqualTo(2);
        assertThat(r.get("openingNetTotal").asText()).isEqualTo("248.70");
        assertThat(val(r)).isEqualTo(okVal("-250.00", "-1.30", "-0.52"));

        setNow("2026-11-23T10:05");
        closeN("BTC", 1, "QRTV", "2026-12-18", "50", "PUT", "100.00", "2026-11-23T10:02");
        r = optRow("1", C_P1);
        assertThat(r.get("openingNetTotal").asText()).isEqualTo("130.00");
        assertThat(val(r)).isEqualTo(okVal("-125.00", "5.00", "3.85"));
        assertThat(grp(summary("1").get("option"))).isEqualTo("-125.00 5.00 1/0/0");
    }

    @Test
    @DisplayName("股票部分卖出：按比例分摊后的剩余总成本 3528.00；60 股 × 61.10 = 3666.00，未实现 138.00（3.91）")
    void stockPartialSellRemainingCost() {
        setNow("2026-11-23T09:50");
        transferInShares("IXQZ", 100, "5880.00", "2026-11-19");
        sellShares("IXQZ", 40, "2440.00", "2026-11-23T09:40");
        sources().cboe("IXQZ", ixqz017("2026-11-23 09:45:00")).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = stkRow("1", "IXQZ");
        assertThat(s.get("shares").asInt()).isEqualTo(60);
        assertThat(s.get("totalCost").asText()).isEqualTo("3528.00");
        assertThat(val(s)).isEqualTo(okVal("3666.00", "138.00", "3.91"));
    }

    @Test
    @DisplayName("Q8 put 被指派得到的股票：总成本已扣权利金（4440.00），52.40 × 100 = 5240.00，未实现 800.00（18.02）")
    void assignedStock() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        setNow("2026-11-23T09:50");
        assignPut("QRTV", "2026-11-20", "45", 1, "2026-11-20");
        assertThat(hasOptRow("1", C_EXPIRED)).isFalse();
        sources().cboe("QRTV", qrtv017("2026-11-23 09:45:00")).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = stkRow("1", "QRTV");
        assertThat(s.get("totalCost").asText()).isEqualTo("4440.00");
        assertThat(val(s)).isEqualTo(okVal("5240.00", "800.00", "18.02"));
        JsonNode sum = summary("1");
        assertThat(grp(sum.get("stock"))).isEqualTo("5240.00 800.00 1/0/0");
        assertThat(grp(sum.get("option"))).isEqualTo("0.00 0.00 0/0/0");
    }

    // ============================================================================================== summary scope

    @Test
    @DisplayName("Q158 合计不受到期过滤影响：带 expiry 查询参数也返回全账户；Q157 两个账户各自合计")
    void summaryIgnoresFilterAndIsPerAccount() {
        scenarioPulled();
        JsonNode all = summary("1");
        for (String q : List.of("?expiry=expired", "?expiry=today", "?expiry=7")) {
            Resp r = get(SUMMARY + q); // accountId is "1" here
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body()).as(q).isEqualTo(all);
        }
        assertThat(grp(summary(accountB).get("total"))).isEqualTo("-125.00 -5.00 1/0/0");
    }

    @Test
    @DisplayName("行情与持仓同时变化后重新查询即得新值（实时计算，不存库）：再次拉到新 mark 后行和合计都更新")
    void recomputedOnEveryRequest() {
        scenarioPulled();
        sources().cboe("QRTV", ok(cboeBody("QRTV", "52.40", "2026-11-23 10:00:00", List.of(
                        option(OCC_C_P1, "bid", "1.00", "ask", "1.10"),
                        option(OCC_C_P2, "bid", "1.80", "ask", "1.90")))))
                .cboe("IXQZ", ixqz017("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");
        assertThat(val(optRow("1", C_P1))).isEqualTo(okVal("-105.00", "13.70", "11.54"));
        assertThat(grp(summary("1").get("option"))).isEqualTo("180.00 81.75 3/1/0");
        assertThat(val(optRow(accountB, C_P5))).isEqualTo(okVal("-105.00", "15.00", "12.50"));
    }

    // ============================================================================================== errors

    @Test
    @DisplayName("合计接口错误：无 X-Account-Id → 400 ACCOUNT_REQUIRED；非整数 → 400 BAD_REQUEST；不存在 → 404 ACCOUNT_NOT_FOUND；POST → 405")
    void summaryErrors() {
        setNow("2026-11-23T10:00");
        assertErrorShape(noAccount(() -> get(SUMMARY)), 400, "ACCOUNT_REQUIRED", SUMMARY);
        assertErrorShape(summaryResp("abc"), 400, "BAD_REQUEST", SUMMARY);
        assertErrorShape(summaryResp("999"), 404, "ACCOUNT_NOT_FOUND", SUMMARY);
        assertErrorShape(exchange(HttpMethod.POST, SUMMARY, null), 405, "METHOD_NOT_ALLOWED", SUMMARY);
        assertErrorShape(exchange(HttpMethod.PUT, SUMMARY, "{}"), 405, "METHOD_NOT_ALLOWED", SUMMARY);
    }

    @Test
    @DisplayName("Q159 首页不改：GET /api/dashboard 不出现未实现字段")
    void dashboardUnchanged() {
        scenarioPulled();
        Resp r = get("/api/dashboard");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().toString()).doesNotContain("unrealized").doesNotContain("marketValue");
    }
}
