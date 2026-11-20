package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-048: boundaries of the contract docs/api/FEAT-048.md — Q238 remainder with 3 lots and lots
 * entered at the same time (Q19 order), stock NO_QUOTE, 0.00-cost lot → null percent, expiration day →
 * EXPIRED_PENDING next day, weekend (last quote), MISSING_FROM_SOURCE, THEO mark, assignment stock lot, backfill /
 * delete replay, the FEAT-043 adjustment response, unchanged errors. See {@link Feat048LotUnrealizedSystemTestBase}.
 */
@DisplayName("FEAT-048 持仓批次的未实现盈亏（边界与错误）")
class Feat048LotUnrealizedBoundarySystemTest extends Feat048LotUnrealizedSystemTestBase {

    // ============================================================================================== rounding / order

    @Test
    @DisplayName("Q238 三个批次 + 手动价 12.3456：33 / 33 / 34 股 → +407.40、+407.40、最后取余数 +419.76（不是 419.75）；同一时间的两笔按录入顺序（Q19）；清除手动价 → 批次全部 无报价")
    void q238ThreeLotsRemainderAndSameTimeOrder() {
        scenarioZ(() -> {
            ids.put("Z1", buyId("ZZS", 33, "400.00", "2026-11-02T10:00"));
            ids.put("Z2", buyId("ZZS", 33, "400.00", "2026-11-03T10:00"));
            ids.put("Z3", buyId("ZZS", 34, "420.00", "2026-11-03T10:00")); // same time, entered after Z2
        }, null);

        // ZZS not served by the fake CBOE → no price → NO_QUOTE row, every lot follows (Q239)
        JsonNode s = stk(A, "ZZS");
        assertThat(s.get("quote").get("price").isNull()).isTrue();
        assertThat(val(s)).isEqualTo(NO_QUOTE_VAL);
        assertThat(lotVals(s)).containsExactly(NO_QUOTE_VAL, NO_QUOTE_VAL, NO_QUOTE_VAL);
        assertLotInvariants(s);

        setNow("2026-11-16T10:05");
        overrideUnderlying("ZZS", "12.3456");
        s = stk(A, "ZZS");
        assertThat(s.get("quote").get("manual").asBoolean()).isTrue();
        assertThat(val(s)).isEqualTo(okVal("1234.56", "14.56", "1.19"));
        assertThat(lotIds(s)).as("same time: first entered first (Q19)")
                .containsExactly(ids.get("Z1"), ids.get("Z2"), ids.get("Z3"));
        assertThat(lotVals(s)).containsExactly(
                okVal("407.40", "7.40", "1.85"),
                okVal("407.40", "7.40", "1.85"),
                okVal("419.76", "-0.24", "-0.06"));
        assertThat(s.get("lots").get(2).get("marketValue").asText())
                .as("remainder, not 12.3456 × 34 = 419.7504 → 419.75").isNotEqualTo("419.75");
        assertLotInvariants(s);

        assertThat(deleteUnderlyingOverride("ZZS").status()).isEqualTo(204);
        s = stk(A, "ZZS");
        assertThat(val(s)).isEqualTo(NO_QUOTE_VAL);
        assertThat(lotVals(s)).containsExactly(NO_QUOTE_VAL, NO_QUOTE_VAL, NO_QUOTE_VAL);
        // the ZZQ rows are untouched by another symbol's override
        assertThat(lotVals(stk(A, "ZZQ"))).containsExactly(AC3_S1, AC3_S2);
    }

    @Test
    @DisplayName("Q155 / Q240 剩余成本 0.00 的批次（转入 3 股 0.01、卖出 2 股后剩 1 股 0.00）：市值 +8.65、未实现 +8.65、百分比 null；另一批次照常 +6.50（+8.13%）")
    void zeroCostLotPercentNull() {
        scenarioZ(() -> {
            ids.put("R1", transferInId("ZZR", 3, "0.01", "2026-10-01"));
            sellSharesId("ZZR", 2, "17.00", "2026-11-04T10:00");
            ids.put("R2", buyId("ZZR", 10, "80.00", "2026-11-05T10:00"));
        }, null);
        setNow("2026-11-16T10:05");
        overrideUnderlying("ZZR", "8.65");

        JsonNode s = stk(A, "ZZR");
        assertThat(s.get("shares").asInt()).isEqualTo(11);
        assertThat(s.get("totalCost").asText()).isEqualTo("80.00");
        assertThat(val(s)).isEqualTo(okVal("95.15", "15.15", "18.94"));
        assertThat(lotIds(s)).containsExactly(ids.get("R1"), ids.get("R2"));
        JsonNode r1 = s.get("lots").get(0);
        assertThat(r1.get("remainingShares").asInt()).isEqualTo(1);
        assertThat(r1.get("remainingCost").asText()).isEqualTo("0.00");
        assertThat(r1.has("unrealizedPnlPercent")).as("present as null, not omitted").isTrue();
        assertThat(lotVals(s)).containsExactly(
                okVal("8.65", "8.65", "null"),
                okVal("86.50", "6.50", "8.13"));
        assertLotInvariants(s);
    }

    // ============================================================================================== dates / quotes

    @Test
    @DisplayName("到期日当天照常计算（EXPIRES_TODAY，两个批次 −2.00 / +8.00（+80.00%）、−2.00 / +10.00（+83.33%））；次日变为 已到期，待处理，批次跟随")
    void expirationDayThenExpiredPending() {
        String today = "ZZQ 2026-11-16 8.5 P";
        scenarioZData(() -> {
            ids.put("T1", openN("STO", 1, "ZZQ", "2026-11-16", "8.5", "PUT", "10.00", "2026-11-13T10:05"));
            ids.put("T2", openN("STO", 1, "ZZQ", "2026-11-16", "8.5", "PUT", "12.00", "2026-11-13T10:10"));
        }, null);
        sources().cboe("ZZQ", zzqChain("8.65", "2026-11-16 09:45:00",
                option("ZZQ261116P00008500", "bid", "0.01", "ask", "0.03"))).apply();
        tickAt("2026-11-16T10:00", "SCHEDULED");

        JsonNode t = opt(A, today);
        assertThat(t.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(val(t)).isEqualTo(okVal("-4.00", "18.00", "81.82"));
        assertThat(lotIds(t)).containsExactly(ids.get("T1"), ids.get("T2"));
        assertThat(lotVals(t)).containsExactly(
                okVal("-2.00", "8.00", "80.00"),
                okVal("-2.00", "10.00", "83.33"));
        assertLotInvariants(t);

        setNow("2026-11-17T09:00");
        t = opt(A, today);
        assertThat(val(t)).isEqualTo(EXPIRED_VAL);
        assertThat(lotVals(t)).containsExactly(EXPIRED_VAL, EXPIRED_VAL);
        assertLotInvariants(t);
        // the 12-18 rows keep the last quote
        assertThat(lotVals(opt(A, C9))).containsExactly(AC1_A, AC1_B);
    }

    @Test
    @DisplayName("周末不拉取：周六按库里最后报价，批次仍为 AC1 / AC2 / AC3 的数字")
    void weekendUsesLastQuote() {
        scenarioZ();
        tickSkipped("2026-11-21T11:00", "NON_TRADING_DAY");
        assertThat(optionsOf(A).get("asOfDate").asText()).isEqualTo("2026-11-21");
        assertThat(lotVals(opt(A, C9))).containsExactly(AC1_A, AC1_B);
        assertThat(lotVals(opt(A, P8))).containsExactly(AC2_L1, AC2_L2);
        assertThat(lotVals(stk(A, "ZZQ"))).containsExactly(AC3_S1, AC3_S2);
        assertAllLotInvariants(A);
    }

    @Test
    @DisplayName("Q239 数据源已无该合约（MISSING_FROM_SOURCE）：行用最后 mark 0.20 计算，批次也用同一个 mark")
    void missingFromSourceUsesLastMark() {
        scenarioZ();
        sources().cboe("ZZQ", ok(cboeBody("ZZQ", "8.65", "2026-11-16 10:10:00",
                List.of(option(OCC_P8, "bid", "0.15", "ask", "0.19"))))).apply();
        tickAt("2026-11-16T11:00", "SCHEDULED");

        JsonNode c = opt(A, C9);
        assertThat(c.get("optionQuote").get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(c.get("optionQuote").get("mark").asText()).isEqualTo("0.20");
        assertThat(val(c)).isEqualTo(AC1_ROW);
        assertThat(lotVals(c)).containsExactly(AC1_A, AC1_B);
        assertLotInvariants(c);
    }

    @Test
    @DisplayName("mark 为 theo（bid 0、theo 0.25）：行 −50.00 / +5.68（+10.20%）；A −25.00 / −6.66（−36.31%）；B −25.00 / +12.34（+33.05%）")
    void theoMark() {
        scenarioZ();
        ObjectNode c9 = option(OCC_C9, "bid", "0", "ask", "0.30", "theo", "0.25");
        sources().cboe("ZZQ", ok(cboeBody("ZZQ", "8.65", "2026-11-16 10:10:00",
                List.of(c9, option(OCC_P8, "bid", "0.15", "ask", "0.19"))))).apply();
        tickAt("2026-11-16T11:00", "SCHEDULED");

        JsonNode c = opt(A, C9);
        assertThat(c.get("optionQuote").get("markBasis").asText()).isEqualTo("THEO");
        assertThat(c.get("optionQuote").get("mark").asText()).isEqualTo("0.25");
        assertThat(val(c)).isEqualTo(okVal("-50.00", "5.68", "10.20"));
        assertThat(lotVals(c)).containsExactly(
                okVal("-25.00", "-6.66", "-36.31"),
                okVal("-25.00", "12.34", "33.05"));
        assertLotInvariants(c);
    }

    // ============================================================================================== writes / replay

    @Test
    @DisplayName("被指派得到的股票批次（Q8）：已过期 8P 被指派 → ASSIGN_BUY 100 股成本 785.00，市值 +865.00 / +80.00（+10.19%）；行 300 股 +2,595.00 / +79.35（+3.15%）")
    void assignedStockLot() {
        scenarioZ(() -> ids.put("P8X", openN("STO", 1, "ZZQ", "2026-11-13", "8", "PUT", "15.00",
                "2026-11-12T10:30")), null);
        assignPut("ZZQ", "2026-11-13", "8", 1, "2026-11-13");
        assertThat(hasOpt(A, P8_EXPIRED)).isFalse();

        JsonNode s = stk(A, "ZZQ");
        assertThat(s.get("shares").asInt()).isEqualTo(300);
        assertThat(s.get("totalCost").asText()).isEqualTo("2515.65");
        assertThat(val(s)).isEqualTo(okVal("2595.00", "79.35", "3.15"));
        JsonNode asg = s.get("lots").get(2);
        assertThat(asg.get("type").asText()).isEqualTo("ASSIGN_BUY");
        assertThat(asg.get("remainingCost").asText()).isEqualTo("785.00");
        assertThat(lotVals(s)).containsExactly(AC3_S1, AC3_S2, okVal("865.00", "80.00", "10.19"));
        assertLotInvariants(s);
    }

    @Test
    @DisplayName("补录 / 删除交易重放：补录更早的 BTO 1 张 8P 20.00 → 批次 新、L1、L2，行 +68.00 / −18.95（−21.79%）；删除 L1 → 新、L2，行 +51.00 / −10.30（−16.80%）")
    void backfillAndDeleteReplay() {
        scenarioZ();
        setNow("2026-11-16T10:05");
        long n = openN("BTO", 1, "ZZQ", "2026-12-18", "8", "PUT", "20.00", "2026-11-11T10:00");
        JsonNode p = opt(A, P8);
        assertThat(val(p)).isEqualTo(okVal("68.00", "-18.95", "-21.79"));
        assertThat(lotIds(p)).containsExactly(n, ids.get("L1"), ids.get("L2"));
        assertThat(lotVals(p)).containsExactly(okVal("17.00", "-3.00", "-15.00"), AC2_L1, AC2_L2);
        assertLotInvariants(p);

        deleteOptionTrade(ids.get("L1"));
        p = opt(A, P8);
        assertThat(val(p)).isEqualTo(okVal("51.00", "-10.30", "-16.80"));
        assertThat(lotIds(p)).containsExactly(n, ids.get("L2"));
        assertThat(lotVals(p)).containsExactly(okVal("17.00", "-3.00", "-15.00"), AC2_L2);
        assertLotInvariants(p);
    }

    @Test
    @DisplayName("FEAT-043 调整到期日响应：position.lots 带同样 5 个字段；9C 12-18 → 12-17（mark 0.25）→ A −25.00 / −6.66（−36.31%），B −25.00 / +12.34（+33.05%）；响应 = GET 行")
    void expirationAdjustmentResponseLots() {
        // 乙 holds the 12-17 9C so that contract is tracked and quoted (quotes are shared across accounts)
        scenarioZData(null, () -> openN("BTO", 1, "ZZQ", "2026-12-17", "9", "CALL", "30.00", "2026-11-13T10:15"));
        injectDec18Closure();
        sources().cboe("ZZQ", zzqChain("8.65", "2026-11-16 09:45:00",
                option("ZZQ261217C00009000", "bid", "0.20", "ask", "0.30"))).apply();
        tickAt("2026-11-16T10:00", "SCHEDULED");

        JsonNode before = opt(A, C9);
        assertThat(before.get("expirationAdjustable").asBoolean()).isTrue();
        assertThat(text(before.get("defaultNewExpirationDate"))).isEqualTo("2026-12-17");
        assertThat(lotVals(before)).containsExactly(AC1_A, AC1_B);

        ObjectNode body = json.createObjectNode();
        body.put("symbol", "ZZQ");
        body.put("expirationDate", "2026-12-18");
        body.put("strike", "9");
        body.put("optionType", "CALL");
        body.put("newExpirationDate", "2026-12-17");
        body.putNull("note");
        Resp r = post(TRADES + "/expiration-adjustments", body.toString());
        assertThat(r.status()).as("POST expiration-adjustments -> %s", r.body()).isEqualTo(200);

        JsonNode pos = r.body().get("position");
        String label = "ZZQ 2026-12-17 9 C";
        assertThat(pos.get("contractLabel").asText()).isEqualTo(label);
        assertThat(pos.get("optionQuote").get("mark").asText()).isEqualTo("0.25");
        assertThat(val(pos)).isEqualTo(okVal("-50.00", "5.68", "10.20"));
        assertThat(lotIds(pos)).containsExactly(ids.get("A"), ids.get("B"));
        assertThat(lotVals(pos)).containsExactly(
                okVal("-25.00", "-6.66", "-36.31"),
                okVal("-25.00", "12.34", "33.05"));
        assertLotInvariants(pos);
        assertThat(pos).as("response position = GET row").isEqualTo(opt(A, label));
        assertAllLotInvariants(A);
        assertAllLotInvariants(B);
    }

    // ============================================================================================== shape / errors

    @Test
    @DisplayName("契约形状：所有账户、两个接口每个批次都有 5 个新字段（null 不省略）、旧字段都在；没有持仓的账户 positions 为空")
    void shapeEverywhere() {
        scenarioZ(() -> openN("STO", 1, "ZZQ", "2026-12-18", "7", "PUT", "12.00", "2026-11-13T10:20"), null);
        for (String acc : List.of(A, B, ALL)) {
            assertAllLotInvariants(acc);
        }
        String empty = createAccount("空账户");
        assertThat(optionsOf(empty).get("positions")).isEmpty();
        assertThat(stocksOf(empty).get("positions")).isEmpty();
    }

    @Test
    @DisplayName("错误不变：两个持仓接口 缺账户头 400 ACCOUNT_REQUIRED、非数字 400 BAD_REQUEST、不存在 404 ACCOUNT_NOT_FOUND")
    void errorsUnchanged() {
        scenarioZ();
        for (String path : List.of(OPTION_POSITIONS, STOCK_POSITIONS)) {
            assertErrorShape(getAs(null, path), 400, "ACCOUNT_REQUIRED", path);
            assertErrorShape(getAs("abc", path), 400, "BAD_REQUEST", path);
            assertErrorShape(getAs("999", path), 404, "ACCOUNT_NOT_FOUND", path);
        }
    }
}
