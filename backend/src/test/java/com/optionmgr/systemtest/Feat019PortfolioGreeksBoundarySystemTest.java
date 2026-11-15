package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-019: boundaries (NO_DELTA vs NO_UNDERLYING_PRICE, index options, net Delta 0, negative
 * exposure HALF_UP, 3-decimal manual price, Q181 rounding of rows before summing, different missing counts, a Greek
 * missing on every row → null, Delta missing, manual mark (Q170), assignment stock, FIFO, weekend, sort order, empty
 * account) and the endpoint's errors. See {@link Feat019PortfolioGreeksSystemTestBase}.
 */
@DisplayName("FEAT-019 组合 Greeks 汇总（边界与错误）")
class Feat019PortfolioGreeksBoundarySystemTest extends Feat019PortfolioGreeksSystemTestBase {

    static final String IXQZ_A = okRow("-18.35", "100.00", "81.65", "61.10", "4988.82", "-3.13", "2.47", "-5.03",
            "null", "null");

    // ============================================================================================== errors / empty

    @Test
    @DisplayName("错误：无 X-Account-Id → 400 ACCOUNT_REQUIRED；非整数 → 400 BAD_REQUEST；不存在 → 404 ACCOUNT_NOT_FOUND；POST / PUT / DELETE → 405")
    void errors() {
        setNow("2026-11-23T10:00");
        assertErrorShape(noAccount(() -> get(GREEKS_SUMMARY)), 400, "ACCOUNT_REQUIRED", GREEKS_SUMMARY);
        assertErrorShape(greeksSummaryResp("abc"), 400, "BAD_REQUEST", GREEKS_SUMMARY);
        assertErrorShape(greeksSummaryResp("999"), 404, "ACCOUNT_NOT_FOUND", GREEKS_SUMMARY);
        assertErrorShape(exchange(HttpMethod.POST, GREEKS_SUMMARY, null), 405, "METHOD_NOT_ALLOWED", GREEKS_SUMMARY);
        assertErrorShape(exchange(HttpMethod.PUT, GREEKS_SUMMARY, "{}"), 405, "METHOD_NOT_ALLOWED", GREEKS_SUMMARY);
        assertErrorShape(exchange(HttpMethod.DELETE, GREEKS_SUMMARY, null), 405, "METHOD_NOT_ALLOWED",
                GREEKS_SUMMARY);
    }

    @Test
    @DisplayName("没有任何持仓的账户 → groups []，全组合 0.00 / 0.00 / 0.00、计数 0、Label 全 null（另一个账户有持仓也不影响）")
    void emptyAccount() {
        scenarioPulled019();
        String empty = createAccount("空账户");
        JsonNode s = greeksSummary(empty);
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(s.get("groups")).isEmpty();
        assertThat(pf(s)).isEqualTo("0.00 0.00 0.00 | null | null | null");
        assertThat(pfCounts(s)).isEqualTo("0/0/0 missing 0/0/0");

        reset();
        setNow("2026-11-23T10:00");
        JsonNode fresh = greeksSummary("1");
        assertThat(fresh.get("groups")).isEmpty();
        assertThat(pf(fresh)).isEqualTo("0.00 0.00 0.00 | null | null | null");
    }

    // ============================================================================================== exclusion

    @Test
    @DisplayName("只剩已到期待处理期权、没有股票 → 期权 / 净 Delta / 敞口 null，NO_DELTA（不算无标的价）；有股票时净 Delta = 股票 Delta、敞口照常；作废处理后该组消失")
    void onlyExpiredPendingOption() {
        setNow("2026-11-17T09:50");
        deposit("15000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        sources().cboe("QRTV", qrtv018("2026-11-20 09:45:00",
                greekOption(OCC_C_EXPIRED, "0.04", "0.06", "0.4100", "-0.0100", "0.0040", "-0.0080", "0.0010"))).apply();
        tickAt("2026-11-20T10:00", "SCHEDULED");
        setNow("2026-11-23T10:00");

        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        String price = text(q.get("underlyingPrice"));
        assertThat(price).as("Q151 last underlying price").isEqualTo("52.40");
        assertThat(row(q)).isEqualTo("null 0.00 null | 52.40 null false | null NO_DELTA null null | null null null"
                + " | 1 个持仓已到期待处理，未计入 | null");
        assertThat(counts(q)).isEqualTo("1 0/0/1 missing 0/0/0/0");
        assertThat(pf(s)).isEqualTo("0.00 0.00 0.00 | 1 个持仓已到期待处理，未计入 | null | null");
        assertThat(pfCounts(s)).as("NO_DELTA is not counted as 无标的价").isEqualTo("0/1/0 missing 0/0/0");

        // with shares: net Delta = stock Delta, exposure computed
        setNow("2026-11-23T10:05");
        transferInShares("QRTV", 100, "5000.00", "2026-11-19");
        s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo("null 100.00 100.00 | 52.40 null false | 5240.00 OK null null"
                + " | null null null | 1 个持仓已到期待处理，未计入 | null");
        assertThat(pf(s)).isEqualTo("5240.00 0.00 0.00 | 1 个持仓已到期待处理，未计入 | null | null");

        expire("QRTV", "2026-11-20", "45", "PUT", 1);
        s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo(
                okRow("null", "100.00", "100.00", "52.40", "5240.00", "null", "null", "null", "null", "null"));
    }

    @Test
    @DisplayName("Q149 / Q170 指数期权 SPX：无报价、无标的价 → NO_DELTA（不是无标的价）；手动 mark 仍不计入；手动标的价 5000.00 后仍 NO_DELTA、无手动标注")
    void indexOption() {
        scenarioPulled019();
        setNow("2026-11-23T10:05");
        openN("BTO", 1, "SPX", "2026-12-18", "7000", "CALL", "500.00", "2026-11-23T10:01");

        JsonNode s = greeksSummary("1");
        assertThat(symbols(s)).containsExactly("IXQZ", "QRTV", "SPX");
        String spx = "null 0.00 null | null 无标的价 false | null NO_DELTA null null | null null null"
                + " | 1 个持仓无报价，未计入 | null";
        assertThat(row(group(s, "SPX"))).isEqualTo(spx);
        assertThat(pf(s)).as("exposure unchanged; 2 rows without quote; SPX not counted as 无标的价")
                .isEqualTo("9558.62 -0.77 0.86 | 2 个持仓无报价，未计入 | null | null");
        assertThat(pfCounts(s)).isEqualTo("2/0/0 missing 0/0/0");

        overrideMark("SPX", "2026-12-18", "7000", "CALL", "5.10");
        assertThat(row(group(greeksSummary("1"), "SPX"))).isEqualTo(spx);

        overrideUnderlying("SPX", "5000.00");
        s = greeksSummary("1");
        assertThat(row(group(s, "SPX"))).isEqualTo("null 0.00 null | 5000.00 null true | null NO_DELTA null null"
                + " | null null null | 1 个持仓无报价，未计入 | null");
        assertThat(pf(s)).startsWith("9558.62 -0.77 0.86 |");
    }

    @Test
    @DisplayName("Q180 不同个数的缺值：P1 delta、vega 缺，P2 gamma、vega 缺 → Delta、Gamma：1 个持仓缺值；Vega：2 个持仓缺值，未计入；QRTV Vega 无任何贡献 → null；Delta 缺值影响敞口（57.00 × 52.40 = 2986.80）；全组合只含 Delta / Theta / Vega")
    void differentMissingCounts() {
        scenarioPulled019();
        sources().cboe("QRTV", qrtv018("2026-11-23 10:00:00",
                        greekOption(OCC_C_P1, "1.20", "1.30", "0.3512", null, "0.0512", "-0.0456", null),
                        greekOption(OCC_C_P2, "1.80", "1.90", "0.3320", "0.2850", null, "-0.0390", null)))
                .cboe("IXQZ", ixqz018("2026-11-23 10:00:00")).apply();
        tickAt("2026-11-23T10:15", "SCHEDULED");

        JsonNode s = greeksSummary("1");
        JsonNode q = group(s, "QRTV");
        assertThat(row(q)).isEqualTo(okRow("57.00", "0.00", "57.00", "52.40", "2986.80", "-5.12", "-3.24", "null",
                "1 个持仓无报价，未计入", "Delta、Gamma：1 个持仓缺值；Vega：2 个持仓缺值，未计入"));
        assertThat(counts(q)).isEqualTo("3 2/1/0 missing 1/1/0/2");
        assertThat(row(group(s, "IXQZ"))).isEqualTo(IXQZ_A);
        assertThat(pf(s)).as("vega = IXQZ only; Gamma not in the portfolio label").isEqualTo(
                "7975.62 -0.77 -5.03 | 1 个持仓无报价，未计入 | null | Delta：1 个持仓缺值；Vega：2 个持仓缺值，未计入");
        assertThat(pfCounts(s)).isEqualTo("1/0/0 missing 1/0/2");
    }

    @Test
    @DisplayName("计入的行 Delta 全部缺失、无股票 → 期权 Delta / 净 Delta null → NO_DELTA；Theta 等照常")
    void allDeltaMissingNoStock() {
        setNow("2026-11-23T09:50");
        deposit("30000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-20T10:00");
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00",
                greekOption(OCC_C_P1, "1.20", "1.30", "0.3512", null, "0.0512", "-0.0456", "0.0711"))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo("null 0.00 null | 52.40 null false | null NO_DELTA null null"
                + " | -5.12 4.56 -7.11 | null | Delta：1 个持仓缺值，未计入");
        assertThat(pf(s)).isEqualTo("0.00 4.56 -7.11 | null | null | Delta：1 个持仓缺值，未计入");
    }

    // ============================================================================================== rounding / zero

    @Test
    @DisplayName("净 Delta 为 0（100 股 + STO 1 张 delta 1 的 call）→ 净 Delta 0.00、敞口 0.00（无负零）；Gamma 0 → 0.00")
    void netDeltaZero() {
        setNow("2026-11-23T09:50");
        deposit("10000.00", "2026-11-16T10:00");
        transferInShares("IXQZ", 100, "5880.00", "2026-11-19");
        openN("STO", 1, "IXQZ", "2026-12-18", "40", "CALL", "2110.00", "2026-11-20T10:00");
        sources().cboe("IXQZ", ok(cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00", List.of(
                greekOption("IXQZ261218C00040000", "21.00", "21.20", "0.5000", "1", "0", "-0.0100", "0.0050")))))
                .apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "IXQZ"))).isEqualTo(okRow("-100.00", "100.00", "0.00", "61.10", "0.00", "0.00",
                "1.00", "-0.50", "null", "null"));
        assertThat(pf(s)).isEqualTo("0.00 1.00 -0.50 | null | null | null");
    }

    @Test
    @DisplayName("负敞口 HALF_UP（按绝对值进位）：BTO 1 IXQZ 55P delta -0.18345 → 行 -18.35，× 61.10 = -1121.185 → -1121.19；手动 61.105（3 位）→ -1121.27675 → -1121.28，标的价原样 61.105")
    void negativeExposureHalfUp() {
        setNow("2026-11-23T09:50");
        deposit("10000.00", "2026-11-16T10:00");
        openN("BTO", 1, "IXQZ", "2026-12-18", "55", "PUT", "60.00", "2026-11-20T10:00");
        sources().cboe("IXQZ", ok(cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00", List.of(
                greekOption("IXQZ261218P00055000", "0.55", "0.65", "0.2900", "-0.18345", "0.03125", "-0.0247",
                        "0.0503"))))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "IXQZ"))).isEqualTo(okRow("-18.35", "0.00", "-18.35", "61.10", "-1121.19", "3.13",
                "-2.47", "5.03", "null", "null"));
        assertThat(pf(s)).isEqualTo("-1121.19 -2.47 5.03 | null | null | null");

        setNow("2026-11-23T10:05");
        overrideUnderlying("IXQZ", "61.105");
        s = greeksSummary("1");
        assertThat(row(group(s, "IXQZ"))).isEqualTo(manualRow("-18.35", "0.00", "-18.35", "61.105", "-1121.28",
                "3.13", "-2.47", "5.03", "null", "null"));
        assertThat(pf(s)).isEqualTo("-1121.28 -2.47 5.03 | null | null | null");
    }

    @Test
    @DisplayName("Q181 先取整行值再相加：两行各 delta 0.00005 × 100 = 0.005 → 0.01，组 0.02（不是 0.01）；theta -0.00005 → -0.01 ×2 = -0.02；敞口 0.02 × 52.40 = 1.048 → 1.05")
    void rowsRoundedBeforeSumming() {
        setNow("2026-11-23T09:50");
        deposit("10000.00", "2026-11-16T10:00");
        openN("BTO", 1, "QRTV", "2026-12-18", "70", "CALL", "1.00", "2026-11-20T10:00");
        openN("BTO", 1, "QRTV", "2026-12-18", "75", "CALL", "1.00", "2026-11-20T10:01");
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00",
                greekOption("QRTV261218C00070000", "0.01", "0.02", "0.9000", "0.00005", "0.00005", "-0.00005",
                        "0.00005"),
                greekOption("QRTV261218C00075000", "0.01", "0.02", "0.9500", "0.00005", "0.00005", "-0.00005",
                        "0.00005"))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(text(greeks("1", "QRTV 2026-12-18 70 C").get("deltaPosition"))).isEqualTo("0.01");

        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo(okRow("0.02", "0.00", "0.02", "52.40", "1.05", "0.02", "-0.02",
                "0.02", "null", "null"));
        assertThat(pf(s)).isEqualTo("1.05 -0.02 0.02 | null | null | null");
    }

    // ============================================================================================== quotes / data

    @Test
    @DisplayName("Q170 手动 mark 不影响汇总（Greeks 与敞口不变）；手动 mark 清除后也不变")
    void manualMarkDoesNotChangeSummary() {
        scenarioPulled019();
        String before = all(greeksSummary("1"));
        setNow("2026-11-23T10:05");
        overrideMark("QRTV", "2026-12-18", "50", "PUT", "1.40");
        overrideMark("QRTV", "2026-12-18", "47.5", "PUT", "0.30");
        assertThat(all(greeksSummary("1"))).isEqualTo(before);
        assertThat(deleteMarkOverride("?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT").status())
                .isEqualTo(204);
        assertThat(all(greeksSummary("1"))).isEqualTo(before);
    }

    @Test
    @DisplayName("没有标的价的股票标的（AAQZ 10 股，从未拉取）排在最前，NO_UNDERLYING_PRICE；全组合敞口不变并提示 1 个标的无标的价，美元敞口未计入；按 symbol 排序")
    void stockWithoutPriceAndSorting() {
        scenarioPulled019();
        setNow("2026-11-23T10:05");
        transferInShares("AAQZ", 10, "100.00", "2026-11-19");

        JsonNode s = greeksSummary("1");
        assertThat(symbols(s)).containsExactly("AAQZ", "IXQZ", "QRTV");
        assertThat(row(group(s, "AAQZ"))).isEqualTo("null 10.00 10.00 | null 无标的价 false | null"
                + " NO_UNDERLYING_PRICE 无标的价 null | null null null | null | null");
        assertThat(pf(s)).isEqualTo(
                "9558.62 -0.77 0.86 | 1 个持仓无报价，未计入 | 1 个标的无标的价，美元敞口未计入 | null");
        assertThat(pfCounts(s)).isEqualTo("1/0/1 missing 0/0/0");
    }

    @Test
    @DisplayName("put 被指派生成的股票自动进入股票 Delta（QRTV 100 股 → 100.00，敞口 5240.00）；期权行消失")
    void assignedStockCounts() {
        setNow("2026-11-17T09:50");
        deposit("20000.00", "2026-11-16T09:00");
        openN("STO", 1, "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-17T09:40");
        setNow("2026-11-23T09:50");
        assignPut("QRTV", "2026-11-20", "45", 1, "2026-11-20");
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00", p1Greeks())).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo(
                okRow("null", "100.00", "100.00", "52.40", "5240.00", "null", "null", "null", "null", "null"));
        assertThat(counts(group(s, "QRTV"))).isEqualTo("0 0/0/0 missing 0/0/0/0");
    }

    @Test
    @DisplayName("同一合约多次开仓 + BTC 整笔平掉最早一笔（FIFO）→ 按剩余张数：90.63 → 60.42，敞口 3166.01")
    void fifoClose() {
        setNow("2026-11-23T09:50");
        deposit("30000.00", "2026-11-16T10:00");
        openN("STO", 1, "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-19T10:00");
        openN("STO", 2, "QRTV", "2026-12-18", "50", "PUT", "250.00", "2026-11-19T10:05");
        sources().cboe("QRTV", qrtv018("2026-11-23 09:45:00", p1Greeks())).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");
        assertThat(row(group(greeksSummary("1"), "QRTV"))).isEqualTo(okRow("90.63", "0.00", "90.63", "52.40",
                "4749.01", "-15.36", "13.68", "-21.33", "null", "null"));

        setNow("2026-11-23T10:05");
        closeN("BTC", 1, "QRTV", "2026-12-18", "50", "PUT", "125.00", "2026-11-23T10:01");
        JsonNode s = greeksSummary("1");
        assertThat(row(group(s, "QRTV"))).isEqualTo(okRow("60.42", "0.00", "60.42", "52.40", "3166.01", "-10.24",
                "9.12", "-14.22", "null", "null"));
        assertThat(pf(s)).isEqualTo("3166.01 9.12 -14.22 | null | null | null");
    }

    @Test
    @DisplayName("周末（周六 11-28）不拉取：用库里最后的值，汇总与周一相同，asOfDate 2026-11-28")
    void weekendUsesLastValues() {
        scenarioPulled019();
        JsonNode mon = greeksSummary("1");
        setNow("2026-11-28T11:00");
        JsonNode sat = greeksSummary("1");
        assertThat(sat.get("asOfDate").asText()).isEqualTo("2026-11-28");
        assertThat(all(sat)).isEqualTo(all(mon));
    }
}
