package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA boundary system tests for FEAT-047 全部账户合并查看（三）(contract docs/api/FEAT-047.md): merged status differing
 * from a single account (NO_DELTA → OK, contract note), symbols with only expired-pending options in every account,
 * {@code noUnderlyingPriceCount} counting a merged symbol once, an account with only closed-out history in a symbol
 * (no card, realized not borrowed from another account), Q233 A rounding of the merged exposure, coverage still per
 * account (FEAT-039), manual mark (Q170), renamed accounts, header errors. Scenario G and helpers: see
 * {@link Feat047AllAccountsStrategyGreeksSystemTestBase}.
 */
@DisplayName("FEAT-047 全部账户合并查看（三）边界（系统测试）")
class Feat047AllAccountsStrategyGreeksBoundarySystemTest extends Feat047AllAccountsStrategyGreeksSystemTestBase {

    @Test
    @DisplayName("契约注：乙单独的 ZZB 只有已到期待处理 P4 → 乙 NO_DELTA；ALL 合并甲的 P2 后 ZZB 为 OK +1,200.00，P4 只计入 expiredPendingCount")
    void mergedNoDeltaBecomesOk() {
        scenarioG();
        JsonNode bZzb = grGroup(greeksOf(B), "ZZB");
        assertThat(grLine(bZzb)).isEqualTo(
                "ZZB | null 0.00 null | 40.00 false null | null NO_DELTA null null | null null null"
                        + " | 1 0/0/1 missing 0/0/0/0 | " + LABEL_1_EXPIRED + " | null");
        JsonNode aZzb = grGroup(greeksOf(A), "ZZB");
        assertThat(aZzb.get("exposureStatus").asText()).isEqualTo("OK");
        assertThat(aZzb.get("expiredPendingCount").asInt()).isZero();

        JsonNode allZzb = grGroup(greeksOf(ALL), "ZZB");
        assertThat(allZzb.get("exposureStatus").asText()).isEqualTo("OK");
        assertThat(text(allZzb.get("dollarExposure"))).isEqualTo("1200.00");
        assertThat(allZzb.get("expiredPendingCount").asInt()).isEqualTo(1);
        assertThat(text(allZzb.get("excludedLabel"))).isEqualTo(LABEL_1_EXPIRED);
        // the option part comes from 甲 only (边界 "标的只在一个账户" for the option side)
        for (String f : List.of("optionDelta", "gamma", "theta", "vega", "netDelta", "dollarExposure")) {
            assertThat(allZzb.get(f)).as("ALL ZZB %s = 甲 ZZB %s", f, f).isEqualTo(aZzb.get(f));
        }
    }

    @Test
    @DisplayName("边界：某标的在所有账户里都只剩已到期待处理期权 → ALL 该组 NO_DELTA、期权部分 null、expiredPendingCount 2；全组合数值不变、提示个数为合计")
    void allAccountsOnlyExpiredPending() {
        scenarioG();
        JsonNode before = greeksOf(ALL);
        accountId = A;
        openN("STO", 1, "ZZF", "2026-11-20", "30", "PUT", "40.00", "2026-11-03T10:00");
        accountId = B;
        openN("STO", 1, "ZZF", "2026-11-20", "30", "PUT", "45.00", "2026-11-03T10:00");
        accountId = A;

        JsonNode all = greeksOf(ALL);
        assertThat(grSymbols(all)).containsExactly("ZZA", "ZZB", "ZZF");
        JsonNode zzf = grGroup(all, "ZZF");
        assertThat(zzf.get("exposureStatus").asText()).isEqualTo("NO_DELTA");
        for (String f : List.of("optionDelta", "gamma", "theta", "vega", "netDelta", "dollarExposure")) {
            assertThat(zzf.get(f).isNull()).as("ZZF %s null", f).isTrue();
        }
        assertThat(zzf.get("stockDelta").asText()).isEqualTo("0.00");
        assertThat(zzf.get("optionRowCount").asInt()).isEqualTo(2);
        assertThat(zzf.get("expiredPendingCount").asInt()).isEqualTo(2);
        assertThat(text(zzf.get("excludedLabel"))).isEqualTo("2 个持仓已到期待处理，未计入");
        JsonNode p = all.get("portfolio");
        assertThat(p.get("dollarExposure")).isEqualTo(before.get("portfolio").get("dollarExposure"));
        assertThat(p.get("theta")).isEqualTo(before.get("portfolio").get("theta"));
        assertThat(p.get("vega")).isEqualTo(before.get("portfolio").get("vega"));
        assertThat(p.get("expiredPendingCount").asInt()).as("1 (P4) + 2 (ZZF)").isEqualTo(3);
        assertThat(text(p.get("excludedLabel"))).isEqualTo("3 个持仓已到期待处理，未计入");

        // strategy cards: one per account, each excluded 1
        JsonNode u = underlyingOf(ALL);
        assertThat(ugLines(u)).contains(
                "1 甲账户 ZZF | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00 | prem 40.00"
                        + " | opt 1 stock false | 0/0/1 | " + LABEL_1_EXPIRED,
                "2 乙账户 ZZF | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00 | prem 45.00"
                        + " | opt 1 stock false | 0/0/1 | " + LABEL_1_EXPIRED);
        assertUnderlyingConsistent(ALL);
    }

    @Test
    @DisplayName("noUnderlyingPriceCount：甲、乙都持有无行情的 ZZC 100 股 → ALL 只有一个 ZZC 组（股票 Delta +200.00、无标的价），全组合 noUnderlyingPriceCount 1；单账户各 1")
    void noUnderlyingPriceCountedOncePerMergedSymbol() {
        scenarioG();
        accountId = A;
        transferInId("ZZC", 100, "3000.00", "2026-10-01");
        accountId = B;
        transferInId("ZZC", 100, "3100.00", "2026-10-01");
        accountId = A;

        JsonNode all = greeksOf(ALL);
        assertThat(grSymbols(all)).containsExactly("ZZA", "ZZB", "ZZC");
        assertThat(grLine(grGroup(all, "ZZC"))).isEqualTo(
                "ZZC | null 200.00 200.00 | null false " + NO_PRICE + " | null NO_UNDERLYING_PRICE " + NO_PRICE
                        + " null | null null null | 0 0/0/0 missing 0/0/0/0 | null | null");
        assertThat(pfLine(all)).isEqualTo("6700.00 12.00 -20.50 | 0/1/1 missing 0/0/0 | " + LABEL_1_EXPIRED
                + " | 1 个标的无标的价，美元敞口未计入 | null");
        for (String acc : List.of(A, B)) {
            JsonNode s = greeksOf(acc);
            assertThat(grGroup(s, "ZZC").get("stockDelta").asText()).isEqualTo("100.00");
            assertThat(s.get("portfolio").get("noUnderlyingPriceCount").asInt()).as("account %s", acc).isEqualTo(1);
        }
        // strategy: two ZZC cards (one per account), each 1 个持仓无报价
        JsonNode u = underlyingOf(ALL);
        assertThat(ugLines(u)).contains(
                "1 甲账户 ZZC | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00 | prem null"
                        + " | opt 0 stock true | 0/1/0 | 1 个持仓无报价，未计入",
                "2 乙账户 ZZC | 0 0.00 (0 0.00 · 0 0.00) | mv null | upnl null | total 0.00 | prem null"
                        + " | opt 0 stock true | 0/1/0 | 1 个持仓无报价，未计入");
        assertUnderlyingConsistent(ALL);
    }

    @Test
    @DisplayName("只有已平仓历史的账户 + 标的不出卡片：乙在 ZZD 只有已平仓（+40.00）→ 无 (乙, ZZD)；甲转入 ZZD 后只有甲 ZZD 卡片，已实现 0.00（不借用乙的），盈亏汇总 ALL ZZD 1 笔 +40.00")
    void closedOutHistoryAccountHasNoCard() {
        scenarioG();
        accountId = B;
        openN("STO", 1, "ZZD", "2026-12-18", "30", "PUT", "60.00", "2026-11-03T10:00");
        closeN("BTC", 1, "ZZD", "2026-12-18", "30", "PUT", "20.00", "2026-11-10T10:00");
        accountId = A;

        JsonNode u0 = underlyingOf(ALL);
        assertThat(ugLines(u0)).as("history only → no ZZD card at all").containsExactly(U_A_ZZA, U_B_ZZA, U_A_ZZB,
                U_B_ZZB);
        assertThat(grSymbols(greeksOf(ALL))).as("history only → no ZZD Greeks row").containsExactly("ZZA", "ZZB");

        transferInId("ZZD", 100, "3000.00", "2026-10-01");
        JsonNode u = underlyingOf(ALL);
        int zzd = 0;
        for (JsonNode g : u.get("groups")) {
            if (g.get("symbol").asText().equals("ZZD")) {
                zzd++;
                assertThat(g.get("accountId").asLong()).as("only 甲's ZZD card").isEqualTo(1);
                assertThat(realizedLine(g.get("realized"))).as("甲 has no ZZD history: realized not merged")
                        .isEqualTo("0 0.00 (0 0.00 · 0 0.00)");
                assertThat(g.get("hasStockPosition").asBoolean()).isTrue();
                assertThat(g.get("optionPositionCount").asInt()).isZero();
                assertThat(g.get("netPremium").isNull()).isTrue();
            }
        }
        assertThat(zzd).isEqualTo(1);
        // the merged realized is in the pnl-summary only
        String zzdPnl = null;
        for (JsonNode r : okAs(ALL, PNL).get("bySymbol")) {
            if (r.get("symbol").asText().equals("ZZD")) {
                zzdPnl = realizedLine(r);
            }
        }
        assertThat(zzdPnl).isEqualTo("1 40.00 (1 40.00 · 0 0.00)");
        // Greeks ZZD row: 甲's 100 shares only (no price: never quoted)
        JsonNode zzdG = grGroup(greeksOf(ALL), "ZZD");
        assertThat(zzdG.get("stockDelta").asText()).isEqualTo("100.00");
        assertThat(zzdG.get("optionRowCount").asInt()).isZero();
        assertUnderlyingConsistent(ALL);
        assertUnderlyingAllIsUnionOfSingles(List.of(A, B));
    }

    @Test
    @DisplayName("Q233 A：甲 净 Delta 87.21、乙 190.63、标的价 52.40 → 合并 277.84 × 52.40 = 14,558.816 → 14558.82（各账户之和 4,569.80 + 9,989.01 = 14,558.81）；Greeks 本身为精确和")
    void mergedExposureRounding() {
        setNow("2026-11-23T09:50");
        renameAccount(A, A_NAME);
        assertThat(createAccount(B_NAME)).isEqualTo(B);
        accountB = B;
        accountId = A;
        depositId("10000.00", "2026-10-01T09:00");
        openN("BTO", 1, "ZZE", "2026-12-18", "40", "CALL", "1250.00", "2026-11-02T10:00");
        accountId = B;
        depositId("10000.00", "2026-10-01T09:00");
        transferInId("ZZE", 100, "5000.00", "2026-10-01");
        openN("BTO", 1, "ZZE", "2026-12-18", "38", "CALL", "1450.00", "2026-11-02T10:00");
        accountId = A;
        ObjectNode c40 = option("ZZE261218C00040000", "bid", "12.40", "ask", "12.60", "iv", "0.3000", "delta",
                "0.8721", "gamma", "0.0100", "theta", "-0.0200", "vega", "0.0300");
        ObjectNode c38 = option("ZZE261218C00038000", "bid", "14.40", "ask", "14.60", "iv", "0.3000", "delta",
                "0.9063", "gamma", "0.0080", "theta", "-0.0150", "vega", "0.0250");
        sources().cboe("ZZE", ok(cboeBody("ZZE", "52.40", "2026-11-23 09:45:00", List.of(c40, c38)))).apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode a = grGroup(greeksOf(A), "ZZE");
        JsonNode b = grGroup(greeksOf(B), "ZZE");
        JsonNode all = greeksOf(ALL);
        JsonNode m = grGroup(all, "ZZE");
        assertThat(text(a.get("netDelta")) + " " + text(a.get("dollarExposure"))).isEqualTo("87.21 4569.80");
        assertThat(text(b.get("netDelta")) + " " + text(b.get("dollarExposure"))).isEqualTo("190.63 9989.01");
        assertThat(text(m.get("netDelta"))).isEqualTo("277.84");
        assertThat(text(m.get("dollarExposure"))).as("Q233 A: merged netDelta × price, rounded once")
                .isEqualTo("14558.82");
        assertThat(new BigDecimal("277.84").multiply(new BigDecimal("52.40")).setScale(2, RoundingMode.HALF_UP)
                .toPlainString()).isEqualTo("14558.82");
        assertThat(all.get("portfolio").get("dollarExposure").asText()).isEqualTo("14558.82");
        // the Greeks themselves are exact sums of the accounts' groups
        for (String f : List.of("optionDelta", "stockDelta", "gamma", "theta", "vega")) {
            assertThat(money(m.get(f))).as("merged %s = 甲 + 乙", f)
                    .isEqualByComparingTo(money(a.get(f)).add(money(b.get(f))));
        }
    }

    @Test
    @DisplayName("覆盖只看同一账户（FEAT-039）：甲有 ZZA 100 股，乙 STO ZZA call → 422 COVERAGE_INSUFFICIENT；两接口不变（合并 Greeks 不代表跨账户覆盖）")
    void coverageStillPerAccount() {
        scenarioG();
        Map<String, JsonNode> before = summariesWorld();
        accountId = B;
        Resp r = post(TRADES, trade("STO", 1, "ZZA", "2026-12-18", "60", "CALL", "30.00", "2026-11-23T09:55")
                .toString());
        accountId = A;
        assertErrorShape(r, 422, "COVERAGE_INSUFFICIENT", TRADES);
        assertThat(summariesWorld()).isEqualTo(before);
        // 甲's own P1 stays covered by its own shares
        JsonNode p1 = optRowOf(okAs(ALL, OPTION_POSITIONS), 1, C_P1);
        JsonNode p1Single = optRowOf(okAs(A, OPTION_POSITIONS), 1, C_P1);
        assertThat(p1).as("ALL row = 甲's row (coverage fields included)").isEqualTo(p1Single);
    }

    @Test
    @DisplayName("手动 mark（Q170）：ALL 下把 P3 的 mark 设为 1.30 → 乙 ZZA 卡片市值 −260.00 / 未实现 −30.00，Greeks 与美元敞口不变；清除后恢复")
    void manualMarkDoesNotChangeGreeks() {
        scenarioG();
        JsonNode g0 = okAs(ALL, GREEKS);
        String saved = accountId;
        accountId = ALL;
        Resp put = putOptionOverride(optionOverrideBody("ZZA", "2026-12-18", "45", "PUT", "1.30"));
        accountId = saved;
        assertThat(put.status()).as("Q218 manual mark with ALL -> %s", put.body()).isEqualTo(200);

        assertThat(greeksOf(ALL)).as("Greeks / exposure unaffected by a manual mark (Q170)").isEqualTo(g0);
        JsonNode u = underlyingOf(ALL);
        assertThat(ugLines(u).get(1)).isEqualTo("2 乙账户 ZZA | 1 80.00 (1 80.00 · 0 0.00) | mv -260.00"
                + " | upnl -30.00 | total 50.00 | prem 230.00 | opt 1 stock false | 1/0/0 | null");
        assertThat(ugLines(u).get(0)).isEqualTo(U_A_ZZA);
        assertUnderlyingConsistent(ALL);

        assertThat(deleteMarkOverride("?symbol=ZZA&expirationDate=2026-12-18&strike=45&optionType=PUT").status())
                .isEqualTo(204);
        assertThat(underlyingOf(ALL)).isEqualTo(parse(U_ALL_JSON));
    }

    @Test
    @DisplayName("改名账户：乙账户改名为 乙二号 → ALL 摘要乙的组 accountName 立即为新名称，其余不变")
    void renamedAccount() {
        scenarioG();
        renameAccount(B, "乙二号");
        JsonNode u = underlyingOf(ALL);
        for (JsonNode g : u.get("groups")) {
            assertThat(g.get("accountName").asText()).isEqualTo(g.get("accountId").asLong() == 1 ? A_NAME : "乙二号");
        }
        assertThat(ugLines(u).get(1)).isEqualTo(U_B_ZZA.replace("乙账户", "乙二号"));
        assertThat(okAs(ALL, GREEKS)).isEqualTo(parse(G_ALL_JSON));
    }

    @Test
    @DisplayName("请求头错误不变：缺失 → ACCOUNT_REQUIRED；'abc' / 'ALLX' → BAD_REQUEST；'99' → ACCOUNT_NOT_FOUND（两接口）")
    void headerErrorsUnchanged() {
        scenarioG();
        for (String path : List.of(UNDERLYING, GREEKS)) {
            for (String blank : new String[] {null, "", "   "}) {
                assertErrorShape(getAs(blank, path), 400, "ACCOUNT_REQUIRED", path);
            }
            for (String bad : List.of("abc", "ALLX", "1,2")) {
                Resp r = getAs(bad, path);
                assertErrorShape(r, 400, "BAD_REQUEST", path);
                assertThat(details(r)).containsExactly("X-Account-Id=账户 id 必须是整数");
            }
            assertErrorShape(getAs("99", path), 404, "ACCOUNT_NOT_FOUND", path);
        }
    }

    @Test
    @DisplayName("同一次读取：ALL 下同一标的不同账户的行用同一个标的价；asOfDate 与持仓接口相同；甲、乙、ALL 的 Greeks 都与持仓行重算一致")
    void oneReadOneQuoteBook() {
        scenarioG();
        for (String scope : List.of(ALL, A, B)) {
            greeksOf(scope); // asserts recomputation (incl. one price per symbol across accounts)
            assertUnderlyingConsistent(scope);
        }
        assertThat(okAs(ALL, UNDERLYING).get("asOfDate")).isEqualTo(okAs(ALL, OPTION_POSITIONS).get("asOfDate"));
    }
}
