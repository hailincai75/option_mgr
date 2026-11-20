package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-048 持仓批次的未实现盈亏: acceptance criteria 1–9 of the requirement over REST, with the
 * exact numbers of the ZZQ scenario (see {@link Feat048LotUnrealizedSystemTestBase}) and the contract
 * docs/api/FEAT-048.md. Page-only parts (列位置、灰字、正绿负红、百分比小字、展开中的批次随查询刷新) are covered by the E2E spec
 * e2e/tests/feat-048-lot-unrealized-pnl.spec.ts.
 *
 * <p>Note (contract): AC7 uses the stock price 8.655 and AC9 uses 8.65 — they are separate starting states, each test
 * starts from the reset scenario.
 */
@DisplayName("FEAT-048 持仓批次的未实现盈亏 验收（系统测试）")
class Feat048LotUnrealizedAcceptanceSystemTest extends Feat048LotUnrealizedSystemTestBase {

    // =========================================================================================== AC 1

    @Test
    @DisplayName("AC1 空头批次：C 行仍 −40.00 / +15.68（+28.16%）；A −20.00 / −1.66（−9.05%），B −20.00 / +17.34（+46.44%）")
    void ac1_shortLots() {
        scenarioZ();
        JsonNode c = opt(A, C9);
        assertThat(c.get("direction").asText()).isEqualTo("SHORT");
        assertThat(c.get("contracts").asInt()).isEqualTo(2);
        assertThat(c.get("openingNetTotal").asText()).isEqualTo("55.68");
        assertThat(c.get("optionQuote").get("mark").asText()).isEqualTo("0.20");
        assertThat(val(c)).as("row unchanged (FEAT-017)").isEqualTo(AC1_ROW);

        assertThat(lotIds(c)).as("FIFO: A then B").containsExactly(ids.get("A"), ids.get("B"));
        assertThat(lotVals(c)).containsExactly(AC1_A, AC1_B);
        JsonNode a = c.get("lots").get(0);
        assertThat(a.get("remainingContracts").asInt()).isEqualTo(1);
        assertThat(a.get("remainingNetAmount").asText()).isEqualTo("18.34");
        assertThat(a.get("originalNetAmount").asText()).as("Q21: remaining = original").isEqualTo("18.34");
        assertThat(c.get("lots").get(1).get("remainingNetAmount").asText()).isEqualTo("37.34");
        assertLotInvariants(c);
    }

    // =========================================================================================== AC 2

    @Test
    @DisplayName("AC2 多头、张数不同：P 行 +51.00 / −15.95（−23.82%）；L1 +17.00 / −8.65（−33.72%），L2 +34.00 / −7.30（−17.68%）")
    void ac2_longLotsDifferentSizes() {
        scenarioZ();
        JsonNode p = opt(A, P8);
        assertThat(p.get("direction").asText()).isEqualTo("LONG");
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("-66.95");
        assertThat(p.get("optionQuote").get("mark").asText()).isEqualTo("0.17");
        assertThat(val(p)).isEqualTo(AC2_ROW);

        assertThat(lotIds(p)).containsExactly(ids.get("L1"), ids.get("L2"));
        assertThat(lotVals(p)).containsExactly(AC2_L1, AC2_L2);
        assertThat(p.get("lots").get(0).get("remainingContracts").asInt()).isEqualTo(1);
        assertThat(p.get("lots").get(0).get("remainingNetAmount").asText()).isEqualTo("-25.65");
        assertThat(p.get("lots").get(1).get("remainingContracts").asInt()).isEqualTo(2);
        assertThat(p.get("lots").get(1).get("remainingNetAmount").asText()).isEqualTo("-41.30");
        assertLotInvariants(p);

        // contract example order: 8P before 9C (strike ascending), 2 rows
        JsonNode all = optionsOf(A);
        assertThat(all.get("positionCount").asInt()).isEqualTo(2);
        assertThat(all.get("positions").get(0).get("contractLabel").asText()).isEqualTo(P8);
        assertThat(all.get("positions").get(1).get("contractLabel").asText()).isEqualTo(C9);
    }

    // =========================================================================================== AC 3

    @Test
    @DisplayName("AC3 股票批次：甲 ZZQ 行 +1,730.00 / −0.65（−0.04%）；S1 +865.00 / −85.00（−8.95%），S2 +865.00 / +84.35（+10.81%）；顶部合计不变")
    void ac3_stockLots() {
        scenarioZ();
        JsonNode s = stk(A, "ZZQ");
        assertThat(s.get("shares").asInt()).isEqualTo(200);
        assertThat(s.get("totalCost").asText()).isEqualTo("1730.65");
        assertThat(s.get("quote").get("price").asText()).isEqualTo("8.65");
        assertThat(val(s)).isEqualTo(AC3_ROW);

        assertThat(lotIds(s)).containsExactly(ids.get("S1"), ids.get("S2"));
        assertThat(lotVals(s)).containsExactly(AC3_S1, AC3_S2);
        JsonNode s1 = s.get("lots").get(0);
        assertThat(s1.get("type").asText()).isEqualTo("TRANSFER_IN");
        assertThat(s1.get("remainingShares").asInt()).isEqualTo(100);
        assertThat(s1.get("remainingCost").asText()).isEqualTo("950.00");
        assertThat(s.get("lots").get(1).get("type").asText()).isEqualTo("BUY");
        assertThat(s.get("lots").get(1).get("remainingCost").asText()).isEqualTo("780.65");
        assertLotInvariants(s);

        // rule 1 / Out of Scope: the FEAT-017 summary is unchanged (= sum of the rows)
        JsonNode sum = okAs(A, UNREALIZED);
        assertThat(sum.get("option").get("marketValue").asText()).isEqualTo("11.00");
        assertThat(sum.get("option").get("unrealizedPnl").asText()).isEqualTo("-0.27");
        assertThat(sum.get("stock").get("marketValue").asText()).isEqualTo("1730.00");
        assertThat(sum.get("stock").get("unrealizedPnl").asText()).isEqualTo("-0.65");
        assertThat(sum.get("total").get("marketValue").asText()).isEqualTo("1741.00");
        assertThat(sum.get("total").get("unrealizedPnl").asText()).isEqualTo("-0.92");
        assertAllLotInvariants(A);
        assertAllLotInvariants(B);
    }

    // =========================================================================================== AC 4

    @Test
    @DisplayName("AC4 FIFO 平仓实现最早批次：BTC 1 张 9C 净额 20.65 → 已平仓 −2.31（平掉 A）；C 1 张，行与唯一批次 B 都是 −20.00 / +17.34（+46.44%）")
    void ac4_fifoCloseRealizesEarliestLot() {
        scenarioZ();
        setNow("2026-11-16T10:05");
        long btc = closeN("BTC", 1, "ZZQ", "2026-12-18", "9", "CALL", "20.65", "2026-11-16T10:05");

        JsonNode closed = okAs(A, "/api/closed-trades");
        JsonNode item = null;
        for (JsonNode i : closed.get("items")) {
            if (i.get("id").asLong() == btc) {
                item = i;
            }
        }
        assertThat(item).as("closed row of the BTC").isNotNull();
        assertThat(item.get("contractLabel").asText()).isEqualTo(C9);
        assertThat(item.get("realizedPnl").asText()).isEqualTo("-2.31");
        assertThat(item.get("coveredLots")).hasSize(1);
        assertThat(item.get("coveredLots").get(0).get("tradeId").asLong()).as("lot A closed").isEqualTo(ids.get("A"));

        JsonNode c = opt(A, C9);
        assertThat(c.get("contracts").asInt()).isEqualTo(1);
        assertThat(c.get("openingNetTotal").asText()).isEqualTo("37.34");
        assertThat(val(c)).isEqualTo(AC1_B);
        assertThat(lotIds(c)).containsExactly(ids.get("B"));
        assertThat(lotVals(c)).as("single lot = row").containsExactly(AC1_B);
        assertLotInvariants(c);
        // P and stock lots unaffected
        assertThat(lotVals(opt(A, P8))).containsExactly(AC2_L1, AC2_L2);
        assertThat(lotVals(stk(A, "ZZQ"))).containsExactly(AC3_S1, AC3_S2);
    }

    // =========================================================================================== AC 5

    @Test
    @DisplayName("AC5 手动 Mark 0.35：C 行 −70.00 / −14.32（−25.72%）；A −35.00 / −16.66（−90.84%）；B −35.00 / +2.34（+6.27%）；清除后恢复 AC1")
    void ac5_manualMark() {
        scenarioZ();
        setNow("2026-11-16T10:05");
        overrideMark("ZZQ", "2026-12-18", "9", "CALL", "0.35");

        JsonNode c = opt(A, C9);
        assertThat(c.get("optionQuote").get("mark").asText()).isEqualTo("0.35");
        assertThat(c.get("optionQuote").get("manual").asBoolean()).isTrue();
        assertThat(val(c)).isEqualTo(okVal("-70.00", "-14.32", "-25.72"));
        assertThat(lotVals(c)).containsExactly(
                okVal("-35.00", "-16.66", "-90.84"),
                okVal("-35.00", "2.34", "6.27"));
        assertLotInvariants(c);
        assertThat(lotVals(opt(A, P8))).as("other contract unchanged").containsExactly(AC2_L1, AC2_L2);

        Resp del = deleteMarkOverride("?symbol=ZZQ&expirationDate=2026-12-18&strike=9&optionType=CALL");
        assertThat(del.status()).as("DELETE override -> %s", del.body()).isEqualTo(204);
        c = opt(A, C9);
        assertThat(c.get("optionQuote").get("manual").asBoolean()).isFalse();
        assertThat(val(c)).isEqualTo(AC1_ROW);
        assertThat(lotVals(c)).containsExactly(AC1_A, AC1_B);
    }

    // =========================================================================================== AC 6

    @Test
    @DisplayName("AC6 无法计算与旧报价：7P（链中无）批次 无报价；已过期 8P 批次 已到期，待处理；11:00 起拉取失败（行情未更新）→ A、B 仍 −1.66 / +17.34")
    void ac6_noQuoteExpiredAndStale() {
        scenarioZ(() -> {
            ids.put("P7", openN("STO", 1, "ZZQ", "2026-12-18", "7", "PUT", "12.00", "2026-11-13T10:20"));
            ids.put("P8X", openN("STO", 1, "ZZQ", "2026-11-13", "8", "PUT", "15.00", "2026-11-12T10:30"));
        }, null);

        JsonNode p7 = opt(A, P7);
        assertThat(p7.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(val(p7)).isEqualTo(NO_QUOTE_VAL);
        assertThat(lotIds(p7)).containsExactly(ids.get("P7"));
        assertThat(lotVals(p7)).containsExactly(NO_QUOTE_VAL);
        assertThat(p7.get("lots").get(0).get("remainingNetAmount").asText()).isEqualTo("12.00");

        JsonNode x = opt(A, P8_EXPIRED);
        assertThat(x.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(val(x)).isEqualTo(EXPIRED_VAL);
        assertThat(lotVals(x)).containsExactly(EXPIRED_VAL);
        assertThat(x.get("lots").get(0).get("remainingNetAmount").asText()).isEqualTo("15.00");
        assertAllLotInvariants(A);

        // 11:00 on: ZZQ pull fails → the rows keep the 10:00 quote, lots computed with the same mark
        sources().cboe("ZZQ", httpError(500)).apply();
        tickAt("2026-11-16T11:00", "SCHEDULED");
        tickAt("2026-11-16T12:00", "SCHEDULED");
        JsonNode c = opt(A, C9);
        assertThat(c.get("optionQuote").get("fetchFailed").asBoolean()).isTrue();
        assertThat(text(c.get("optionQuote").get("staleLabel"))).isEqualTo("行情未更新（上次：11-16 10:00）");
        assertThat(c.get("optionQuote").get("mark").asText()).isEqualTo("0.20");
        assertThat(val(c)).isEqualTo(AC1_ROW);
        assertThat(lotVals(c)).containsExactly(AC1_A, AC1_B);
        assertThat(lotVals(opt(A, P8))).containsExactly(AC2_L1, AC2_L2);
        JsonNode s = stk(A, "ZZQ");
        assertThat(s.get("quote").get("fetchFailed").asBoolean()).isTrue();
        assertThat(lotVals(s)).containsExactly(AC3_S1, AC3_S2);
        assertThat(lotVals(opt(A, P7))).containsExactly(NO_QUOTE_VAL);
        assertThat(lotVals(opt(A, P8_EXPIRED))).containsExactly(EXPIRED_VAL);
        assertAllLotInvariants(A);
    }

    // =========================================================================================== AC 7

    @Test
    @DisplayName("AC7 取整对齐：现价 8.655，乙 ZZQ 行 +1,731.00 / −9.00（−0.52%）；S3 +874.16 / −5.84（−0.66%）；S4 取余数 +856.84 / −3.16（−0.37%）")
    void ac7_roundingRemainder() {
        scenarioZ();
        sources().cboe("ZZQ", zzqChain("8.655", "2026-11-16 10:10:00")).apply();
        tickAt("2026-11-16T11:00", "SCHEDULED");

        JsonNode s = stk(B, "ZZQ");
        assertThat(s.get("quote").get("price").asText()).isEqualTo("8.655");
        assertThat(s.get("totalCost").asText()).isEqualTo("1740.00");
        assertThat(val(s)).isEqualTo(okVal("1731.00", "-9.00", "-0.52"));
        assertThat(lotIds(s)).containsExactly(ids.get("S3"), ids.get("S4"));
        assertThat(lotVals(s)).containsExactly(
                okVal("874.16", "-5.84", "-0.66"),
                okVal("856.84", "-3.16", "-0.37"));
        assertThat(s.get("lots").get(1).get("marketValue").asText())
                .as("Q238 A: remainder, not 8.655 × 99 = 856.845 → 856.85").isNotEqualTo("856.85");
        assertLotInvariants(s);
        // 甲 at 8.655: 1,731.00 split 865.50 / 865.50
        JsonNode sa = stk(A, "ZZQ");
        assertThat(sa.get("marketValue").asText()).isEqualTo("1731.00");
        assertLotInvariants(sa);
    }

    // =========================================================================================== AC 8

    @Test
    @DisplayName("AC8 股票部分卖出后：AC4 后卖出 50 股实收 440.00 → 行 150 股 +1,297.50 / +41.85（+3.33%）；S1 50 股 475.00 → +432.50 / −42.50（−8.95%）；S2 仍 +84.35")
    void ac8_partialStockSale() {
        scenarioZ();
        setNow("2026-11-16T10:05");
        closeN("BTC", 1, "ZZQ", "2026-12-18", "9", "CALL", "20.65", "2026-11-16T10:05");
        setNow("2026-11-16T10:10");
        sellSharesId("ZZQ", 50, "440.00", "2026-11-16T10:10");

        JsonNode s = stk(A, "ZZQ");
        assertThat(s.get("shares").asInt()).isEqualTo(150);
        assertThat(s.get("totalCost").asText()).isEqualTo("1255.65");
        assertThat(val(s)).isEqualTo(okVal("1297.50", "41.85", "3.33"));
        assertThat(lotIds(s)).containsExactly(ids.get("S1"), ids.get("S2"));
        JsonNode s1 = s.get("lots").get(0);
        assertThat(s1.get("remainingShares").asInt()).isEqualTo(50);
        assertThat(s1.get("remainingCost").asText()).isEqualTo("475.00");
        assertThat(lotVals(s)).containsExactly(
                okVal("432.50", "-42.50", "-8.95"),
                AC3_S2);
        assertLotInvariants(s);
        assertThat(lotVals(opt(A, C9))).containsExactly(AC1_B);
    }

    // =========================================================================================== AC 9

    @Test
    @DisplayName("AC9 全部账户：甲 C 的 A、B 同 AC1；乙 S3 +873.65 / −6.35（−0.72%），S4 +856.35 / −3.65（−0.42%），乙股票行 −10.00（−0.57%）；ALL 批次 = 单账户逐字；写请求 400 ALL_ACCOUNTS_READ_ONLY")
    void ac9_allAccounts() {
        scenarioZ();
        JsonNode opts = optionsOf(ALL);
        JsonNode c = optRowOf(opts, 1, C9);
        assertThat(c.get("accountName").asText()).isEqualTo(A_NAME);
        assertThat(val(c)).isEqualTo(AC1_ROW);
        assertThat(lotVals(c)).containsExactly(AC1_A, AC1_B);

        JsonNode stocks = stocksOf(ALL);
        JsonNode sb = stockRowOf(stocks, 2, "ZZQ");
        assertThat(sb.get("accountName").asText()).isEqualTo(B_NAME);
        assertThat(sb.get("totalCost").asText()).isEqualTo("1740.00");
        assertThat(val(sb)).isEqualTo(AC9_B_ROW);
        assertThat(lotIds(sb)).containsExactly(ids.get("S3"), ids.get("S4"));
        assertThat(sb.get("lots").get(0).get("remainingShares").asInt()).isEqualTo(101);
        assertThat(sb.get("lots").get(1).get("remainingShares").asInt()).isEqualTo(99);
        assertThat(lotVals(sb)).containsExactly(AC9_S3, AC9_S4);
        assertThat(lotVals(stockRowOf(stocks, 1, "ZZQ"))).containsExactly(AC3_S1, AC3_S2);

        // every ALL row (lots included) = the single-account row verbatim
        assertAllRowsEqualSingleRows(OPTION_POSITIONS, "contractLabel", List.of(A, B));
        assertAllRowsEqualSingleRows(STOCK_POSITIONS, "symbol", List.of(A, B));
        assertAllLotInvariants(ALL);

        // read-only (FEAT-035, unchanged)
        String saved = accountId;
        accountId = ALL;
        Resp r;
        try {
            r = closeResp("BTC", 1, "ZZQ", "2026-12-18", "9", "CALL", "20.65", "2026-11-16T10:00");
        } finally {
            accountId = saved;
        }
        assertGuardError(r, READ_ONLY_CODE, TRADES + "/closes");
        assertThat(lotVals(opt(A, C9))).as("nothing written").containsExactly(AC1_A, AC1_B);
    }
}
