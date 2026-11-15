package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-047 全部账户合并查看（三）: acceptance criteria 1–10 of the requirement over REST, with the
 * exact numbers of scenario G (see {@link Feat047AllAccountsStrategyGreeksSystemTestBase}) and the contract
 * docs/api/FEAT-047.md. Page-only parts (卡片标题前缀、灰字、开关、`已实现` 链接先切账户、空状态) are covered by the E2E
 * spec e2e/tests/feat-047-all-accounts-strategy-greeks.spec.ts; here the data the pages show and the backend guards.
 */
@DisplayName("FEAT-047 全部账户合并查看（三）验收（系统测试）")
class Feat047AllAccountsStrategyGreeksAcceptanceSystemTest extends Feat047AllAccountsStrategyGreeksSystemTestBase {

    // =========================================================================================== AC 1

    @Test
    @DisplayName("AC1 卡片顺序与标题数据：ALL 依次 甲 ZZA（期权 1 · 股票 100 股）、乙 ZZA（期权 1）、甲 ZZB（期权 1）、乙 ZZB（期权 1）；每组带 accountId / accountName；无合计")
    void ac1_cardOrder() {
        scenarioG();
        JsonNode all = underlyingOf(ALL);
        List<String> order = new java.util.ArrayList<>();
        for (JsonNode g : all.get("groups")) {
            order.add(g.get("accountId").asLong() + " " + g.get("accountName").asText() + " " + g.get("symbol").asText()
                    + " opt " + g.get("optionPositionCount").asInt() + " stock " + g.get("hasStockPosition").asBoolean());
        }
        assertThat(order).containsExactly(
                "1 甲账户 ZZA opt 1 stock true",
                "2 乙账户 ZZA opt 1 stock false",
                "1 甲账户 ZZB opt 1 stock false",
                "2 乙账户 ZZB opt 1 stock false");
        // 股票 100 股 comes from the ALL stock-positions row of the same account + symbol
        JsonNode stocks = okAs(ALL, STOCK_POSITIONS);
        assertThat(stockOrder(stocks)).containsExactly("1 甲账户 ZZA 100");
        // the cards' rows: option-positions (ALL) grouped by accountId + symbol
        assertThat(optionOrder(okAs(ALL, OPTION_POSITIONS))).containsExactlyInAnyOrder(
                "1 甲账户 " + C_P1, "2 乙账户 " + C_P3, "1 甲账户 " + C_P2, "2 乙账户 " + C_P4);
        // no cross-group total (Q201): only asOfDate + groups
        assertThat(fieldNames(all)).containsExactlyInAnyOrder("asOfDate", "groups");
        assertThat(all.get("asOfDate").asText()).isEqualTo("2026-11-23");
    }

    // =========================================================================================== AC 2

    @Test
    @DisplayName("AC2 卡片摘要：甲 ZZA +39.70/+225.35/+265.05/+4,940.00/+85.35；乙 ZZA +80.00/+10.00/+90.00/−220.00/+230.00；甲 ZZB 0.00/+10.00/+10.00/−110.00/+120.00；乙 ZZB 0.00/—/0.00/—/+50.00 + 1 个持仓已到期待处理；整份响应 = 契约示例")
    void ac2_cardSummaries() {
        scenarioG();
        JsonNode all = underlyingOf(ALL);
        assertThat(ugLines(all)).containsExactly(U_A_ZZA, U_B_ZZA, U_A_ZZB, U_B_ZZB);
        assertThat(all).as("ALL response = contract example verbatim").isEqualTo(parse(U_ALL_JSON));

        // 乙 ZZA card 45P row: 空头 2 +230.00, mv −220.00, upnl +10.00 (+4.35%)
        JsonNode p3 = optRowOf(okAs(ALL, OPTION_POSITIONS), 2, C_P3);
        assertThat(p3.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p3.get("contracts").asInt()).isEqualTo(2);
        assertThat(p3.get("openingNetTotal").asText()).isEqualTo("230.00");
        assertThat(val(p3)).isEqualTo("-220.00 10.00 4.35 OK null");

        // switch to 甲: only 甲's two groups, numbers identical, still carrying the account fields
        JsonNode a = underlyingOf(A);
        assertThat(ugLines(a)).containsExactly(U_A_ZZA, U_A_ZZB);
        assertThat(ugLines(underlyingOf(B))).containsExactly(U_B_ZZA, U_B_ZZB);
        // Q53: ALL groups of each account = that account's own groups verbatim
        assertUnderlyingAllIsUnionOfSingles(List.of(A, B));
    }

    // =========================================================================================== AC 3

    @Test
    @DisplayName("AC3 一致性：ALL 持仓合计 +4,610.00 / +245.35 / 1 个已到期 = 4 张卡片之和；盈亏汇总 ZZA 2 笔 +119.70 = 甲 +39.70 + 乙 +80.00")
    void ac3_consistency() {
        scenarioG();
        JsonNode total = okAs(ALL, UNREALIZED).get("total");
        assertThat(total.get("marketValue").asText()).isEqualTo("4610.00");
        assertThat(total.get("unrealizedPnl").asText()).isEqualTo("245.35");
        assertThat(total.get("expiredPendingCount").asInt()).isEqualTo(1);
        assertThat(total.get("noQuoteCount").asInt()).isZero();

        JsonNode pnl = okAs(ALL, PNL);
        JsonNode zza = null;
        for (JsonNode r : pnl.get("bySymbol")) {
            if (r.get("symbol").asText().equals("ZZA")) {
                zza = r;
            }
        }
        assertThat(zza).as("pnl-summary ALL has a ZZA row").isNotNull();
        assertThat(realizedLine(zza)).isEqualTo("2 119.70 (2 119.70 · 0 0.00)");

        for (String scope : List.of(ALL, A, B)) {
            assertUnderlyingConsistent(scope);
        }
    }

    // =========================================================================================== AC 4

    @Test
    @DisplayName("AC4 链接数据：ALL 合约 ZZA 2026-12-18 45 P → 交易列表只有 P3（乙）；甲 ZZA 卡片 +39.70 → 切到甲后已平仓 symbol=ZZA 只有 H2 +39.70（Q235），与卡片一致")
    void ac4_links() {
        scenarioG();
        JsonNode trades = okAs(ALL, TRADES + "?symbol=ZZA&expirationDate=2026-12-18&strike=45&optionType=PUT");
        assertThat(trades.get("totalItems").asInt()).isEqualTo(1);
        JsonNode t = trades.get("items").get(0);
        assertThat(acc(t)).isEqualTo("2 乙账户");
        assertThat(t.get("action").asText()).isEqualTo("STO");
        assertThat(t.get("contractLabel").asText()).isEqualTo(C_P3);
        assertThat(t.get("contracts").asInt()).isEqualTo(2);

        // Q235 B: the page switches to the card's account first, then reads closed-trades with that account
        JsonNode card = underlyingOf(ALL).get("groups").get(0);
        assertThat(card.get("accountId").asLong()).isEqualTo(1);
        JsonNode closed = okAs(String.valueOf(card.get("accountId").asLong()), CLOSED + "?symbol=ZZA");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("39.70");
        assertThat(closed.get("filteredRealizedPnlTotal").asText())
                .as("closed total under the card's account = card realized")
                .isEqualTo(card.get("realized").get("realizedPnl").asText());
        JsonNode h2 = closed.get("items").get(0);
        assertThat(h2.get("contractLabel").asText()).isEqualTo(C_H1);
        assertThat(h2.get("action").asText()).isEqualTo("BTC");
        assertThat(h2.get("realizedPnl").asText()).isEqualTo("39.70");
        // without switching, ALL closed for ZZA would be 2 笔 +119.70 (why the page must switch first)
        JsonNode allClosed = okAs(ALL, CLOSED + "?symbol=ZZA");
        assertThat(allClosed.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(allClosed.get("filteredRealizedPnlTotal").asText()).isEqualTo("119.70");
    }

    // =========================================================================================== AC 5

    @Test
    @DisplayName("AC5 Greeks 合并行：ZZA +10.00 / +100.00 / +110.00 / 50.00 / +5,500.00 / −10.00 / +8.00 / −16.00；ZZB +30.00 / 0.00 / +30.00 / 40.00 / +1,200.00 / −5.00 / +4.00 / −4.50 + 1 个已到期；无账户字段；整份 = 契约示例")
    void ac5_mergedGreeksRows() {
        scenarioG();
        JsonNode all = greeksOf(ALL);
        assertThat(grSymbols(all)).containsExactly("ZZA", "ZZB");
        assertThat(grLine(grGroup(all, "ZZA"))).isEqualTo(
                "ZZA | 10.00 100.00 110.00 | 50.00 false null | 5500.00 OK null null | -10.00 8.00 -16.00"
                        + " | 2 2/0/0 missing 0/0/0/0 | null | null");
        assertThat(grLine(grGroup(all, "ZZB"))).isEqualTo(
                "ZZB | 30.00 0.00 30.00 | 40.00 false null | 1200.00 OK null null | -5.00 4.00 -4.50"
                        + " | 2 1/0/1 missing 0/0/0/0 | " + LABEL_1_EXPIRED + " | null");
        for (JsonNode g : all.get("groups")) {
            assertThat(g.has("accountId")).as("no account fields on merged rows (Q234 A)").isFalse();
            assertThat(g.has("accountName")).isFalse();
        }
        assertThat(all).as("ALL response = contract example verbatim").isEqualTo(parse(G_ALL_JSON));

        // row Greeks (FEAT-018) the merge is built from
        JsonNode opt = okAs(ALL, OPTION_POSITIONS);
        assertThat(rowGreeks(optRowOf(opt, 1, C_P1))).isEqualTo("OK -20.00 -4.00 3.00 -6.00");
        assertThat(rowGreeks(optRowOf(opt, 2, C_P3))).isEqualTo("OK 30.00 -6.00 5.00 -10.00");
        assertThat(rowGreeks(optRowOf(opt, 1, C_P2))).isEqualTo("OK 30.00 -5.00 4.00 -4.50");
        assertThat(optRowOf(opt, 2, C_P4).get("greeks").get("status").asText()).isNotEqualTo("OK");
    }

    static String rowGreeks(JsonNode row) {
        JsonNode g = row.get("greeks");
        return g.get("status").asText() + " " + text(g.get("deltaPosition")) + " " + text(g.get("gammaPosition")) + " "
                + text(g.get("thetaPosition")) + " " + text(g.get("vegaPosition"));
    }

    // =========================================================================================== AC 6

    @Test
    @DisplayName("AC6 全组合：ALL +6,700.00 / +12.00 / −20.50 + 1 个已到期；甲 +5,200.00 / +7.00 / −10.50（ZZA +4,000.00）；乙 +1,500.00 / +5.00 / −10.00（ZZB NO_DELTA）——单账户不变")
    void ac6_portfolio() {
        scenarioG();
        assertThat(pfLine(greeksOf(ALL))).isEqualTo(
                "6700.00 12.00 -20.50 | 0/1/0 missing 0/0/0 | " + LABEL_1_EXPIRED + " | null | null");

        JsonNode a = greeksOf(A);
        assertThat(pfLine(a)).isEqualTo("5200.00 7.00 -10.50 | 0/0/0 missing 0/0/0 | null | null | null");
        JsonNode aZza = grGroup(a, "ZZA");
        assertThat(text(aZza.get("optionDelta")) + " " + text(aZza.get("stockDelta")) + " "
                + text(aZza.get("netDelta")) + " " + text(aZza.get("dollarExposure")))
                .isEqualTo("-20.00 100.00 80.00 4000.00");
        JsonNode aZzb = grGroup(a, "ZZB");
        assertThat(text(aZzb.get("optionDelta")) + " " + text(aZzb.get("dollarExposure"))).isEqualTo("30.00 1200.00");

        JsonNode b = greeksOf(B);
        assertThat(pfLine(b)).isEqualTo(
                "1500.00 5.00 -10.00 | 0/1/0 missing 0/0/0 | " + LABEL_1_EXPIRED + " | null | null");
        JsonNode bZza = grGroup(b, "ZZA");
        assertThat(text(bZza.get("optionDelta")) + " " + text(bZza.get("stockDelta")) + " "
                + text(bZza.get("dollarExposure"))).isEqualTo("30.00 0.00 1500.00");
        JsonNode bZzb = grGroup(b, "ZZB");
        assertThat(bZzb.get("exposureStatus").asText()).isEqualTo("NO_DELTA");
        for (String f : List.of("optionDelta", "gamma", "theta", "vega", "netDelta", "dollarExposure")) {
            assertThat(bZzb.get(f).isNull()).as("乙 ZZB %s null", f).isTrue();
        }
        assertThat(bZzb.get("expiredPendingCount").asInt()).isEqualTo(1);
    }

    // =========================================================================================== AC 7

    @Test
    @DisplayName("AC7 手动标的价：ALL 下设 ZZA 52.00 → ZZA +5,720.00（按手动标的价）、全组合 +6,920.00，Greeks 与计数不变、期权行市值 / 未实现不变；清除后恢复 AC5、6")
    void ac7_manualUnderlyingPrice() {
        scenarioG();
        JsonNode before = greeksOf(ALL);
        JsonNode optBefore = okAs(ALL, OPTION_POSITIONS);
        JsonNode uBefore = underlyingOf(ALL);

        Resp put = putUnderlyingPriceAs(ALL, "ZZA", "52.00");
        assertThat(put.status()).as("Q218: manual price with ALL -> %s", put.body()).isEqualTo(200);

        JsonNode after = greeksOf(ALL);
        assertThat(grLine(grGroup(after, "ZZA"))).isEqualTo(
                "ZZA | 10.00 100.00 110.00 | 52.00 true null | 5720.00 OK null " + MANUAL_PRICE_NOTE
                        + " | -10.00 8.00 -16.00 | 2 2/0/0 missing 0/0/0/0 | null | null");
        assertThat(grGroup(after, "ZZB")).as("ZZB unchanged").isEqualTo(grGroup(before, "ZZB"));
        assertThat(pfLine(after)).isEqualTo(
                "6920.00 12.00 -20.50 | 0/1/0 missing 0/0/0 | " + LABEL_1_EXPIRED + " | null | null");

        // option rows: market value / unrealized / Greeks unchanged by the underlying price
        JsonNode optAfter = okAs(ALL, OPTION_POSITIONS);
        for (String[] k : new String[][] {{"1", C_P1}, {"2", C_P3}, {"1", C_P2}, {"2", C_P4}}) {
            JsonNode b0 = optRowOf(optBefore, Long.parseLong(k[0]), k[1]);
            JsonNode a0 = optRowOf(optAfter, Long.parseLong(k[0]), k[1]);
            assertThat(val(a0)).as("%s/%s value unchanged", k[0], k[1]).isEqualTo(val(b0));
            assertThat(a0.get("greeks").get("deltaPosition")).isEqualTo(b0.get("greeks").get("deltaPosition"));
        }
        // cards: 乙 ZZA, 甲 ZZB, 乙 ZZB unchanged; 甲 ZZA only its stock part moves (100 × 52.00 = 5,200.00)
        JsonNode uAfter = underlyingOf(ALL);
        assertThat(ugLines(uAfter).subList(1, 4)).isEqualTo(ugLines(uBefore).subList(1, 4));
        assertThat(ugLines(uAfter).get(0)).isEqualTo("1 甲账户 ZZA | 1 39.70 (1 39.70 · 0 0.00) | mv 5140.00"
                + " | upnl 425.35 | total 465.05 | prem 85.35 | opt 1 stock true | 2/0/0 | null");
        assertUnderlyingConsistent(ALL);

        Resp del = deleteUnderlyingPriceAs(ALL, "ZZA");
        assertThat(del.status()).as("clear manual price with ALL -> %s", del.body()).isEqualTo(204);
        assertThat(greeksOf(ALL)).as("back to AC5 / AC6").isEqualTo(parse(G_ALL_JSON));
        assertThat(underlyingOf(ALL)).isEqualTo(parse(U_ALL_JSON));
    }

    // =========================================================================================== AC 8

    @Test
    @DisplayName("AC8 写后更新：甲 BTC 1 张 P1 净额 60.65 → ALL 甲 ZZA 已实现 +64.40 / 未实现 +200.00 / 合计 +264.40 / 市值 +5,000.00 / 净权利金 null；Greeks ZZA +30.00 / +130.00 / +6,500.00 / Γ −6.00 Θ +5.00 V −10.00；全组合 +7,700.00 / +9.00 / −14.50")
    void ac8_afterWrite() {
        scenarioG();
        Map<String, JsonNode> before = summariesWorld();
        setNow("2026-11-23T10:31");
        accountId = A;
        closeN("BTC", 1, "ZZA", "2026-12-18", "55", "CALL", "60.65", "2026-11-23T10:30");

        JsonNode u = underlyingOf(ALL);
        assertThat(ugLines(u)).containsExactly(U_A_ZZA_AFTER_BTC, U_B_ZZA, U_A_ZZB, U_B_ZZB);

        JsonNode g = greeksOf(ALL);
        assertThat(grLine(grGroup(g, "ZZA"))).isEqualTo(
                "ZZA | 30.00 100.00 130.00 | 50.00 false null | 6500.00 OK null null | -6.00 5.00 -10.00"
                        + " | 1 1/0/0 missing 0/0/0/0 | null | null");
        assertThat(grGroup(g, "ZZB")).isEqualTo(grGroup(before.get(ALL + " " + GREEKS), "ZZB"));
        assertThat(pfLine(g)).isEqualTo(
                "7700.00 9.00 -14.50 | 0/1/0 missing 0/0/0 | " + LABEL_1_EXPIRED + " | null | null");

        // 乙 unchanged; 甲 single = ALL 甲 groups
        assertThat(okAs(B, UNDERLYING)).isEqualTo(before.get(B + " " + UNDERLYING));
        assertThat(okAs(B, GREEKS)).isEqualTo(before.get(B + " " + GREEKS));
        assertUnderlyingAllIsUnionOfSingles(List.of(A, B));
        assertUnderlyingConsistent(ALL);
    }

    // =========================================================================================== AC 9

    @Test
    @DisplayName("AC9 无持仓：所有账户都没有持仓 → ALL 两接口 groups []，全组合 0.00 / 计数 0 / Label null（策略页 暂无持仓）")
    void ac9_noPositions() {
        setNow("2026-11-23T10:00");
        renameAccount(A, A_NAME);
        assertThat(createAccount(B_NAME)).isEqualTo(B);
        accountId = A;
        depositId("1000.00", "2026-11-02T10:00");

        JsonNode u = underlyingOf(ALL);
        assertThat(u.get("groups")).isEmpty();
        assertThat(u.get("asOfDate").asText()).isEqualTo("2026-11-23");
        JsonNode g = greeksOf(ALL);
        assertThat(g.get("groups")).isEmpty();
        assertThat(pfLine(g)).isEqualTo("0.00 0.00 0.00 | 0/0/0 missing 0/0/0 | null | null | null");
    }

    // =========================================================================================== AC 10

    @Test
    @DisplayName("AC10 后端：两接口 ALL → 200（不再 ALL_ACCOUNTS_NOT_SUPPORTED），all / ' ALL ' 相同；/{id} 与审计仍 NOT_SUPPORTED；接受 ALL 的 GET 共 13 个")
    void ac10_acceptsAll() {
        scenarioG();
        for (String path : List.of(UNDERLYING, GREEKS)) {
            JsonNode upper = okAs(ALL, path);
            assertThat(okAs("all", path)).as("%s lower case", path).isEqualTo(upper);
            assertThat(okAs(" ALL ", path)).as("%s trimmed", path).isEqualTo(upper);
        }
        // the 13 GETs that accept ALL (FEAT-035 7 + FEAT-046 4 + FEAT-047 2)
        List<String> thirteen = new java.util.ArrayList<>(ALL_READS);
        thirteen.addAll(LISTS);
        thirteen.add(UNDERLYING);
        thirteen.add(GREEKS);
        assertThat(thirteen).hasSize(13).doesNotHaveDuplicates();
        for (String path : thirteen) {
            assertThat(getAs(ALL, path).status()).as("%s accepts ALL", path).isEqualTo(200);
        }
        // still not supported
        assertGuardError(getAs(ALL, "/api/audit-log"), NOT_SUPPORTED_CODE, "/api/audit-log");
        JsonNode anyTrade = okAs(ALL, TRADES).get("items").get(0);
        String one = TRADES + "/" + anyTrade.get("id").asLong();
        assertGuardError(getAs(ALL, one), NOT_SUPPORTED_CODE, one);
        String stockOne = STOCK_TRADES + "/" + okAs(ALL, STOCK_TRADES).get("items").get(0).get("id").asLong();
        assertGuardError(getAs(ALL, stockOne), NOT_SUPPORTED_CODE, stockOne);
    }

    @Test
    @DisplayName("AC10 新增空账户（丙账户）后两接口 ALL 结果完全不变")
    void ac10_emptyAccount() {
        scenarioG();
        JsonNode u = okAs(ALL, UNDERLYING);
        JsonNode g = okAs(ALL, GREEKS);
        createAccount("丙账户");
        assertThat(okAs(ALL, UNDERLYING)).isEqualTo(u);
        assertThat(okAs(ALL, GREEKS)).isEqualTo(g);
        assertThat(okAs(ALL, UNDERLYING)).isEqualTo(parse(U_ALL_JSON));
        assertThat(okAs(ALL, GREEKS)).isEqualTo(parse(G_ALL_JSON));
        // the empty account itself: no groups, zero portfolio
        assertThat(okAs("3", UNDERLYING).get("groups")).isEmpty();
        assertThat(pfLine(okAs("3", GREEKS))).isEqualTo("0.00 0.00 0.00 | 0/0/0 missing 0/0/0 | null | null | null");
    }

    @Test
    @DisplayName("AC10 只有一个账户：ALL 摘要 = 该账户（同样带账户字段），ALL Greeks = 该账户逐字相同")
    void ac10_singleAccount() {
        setNow("2026-11-23T09:50");
        renameAccount(A, A_NAME);
        accountId = A;
        depositId("50000.00", "2026-10-01T09:00");
        transferInId("ZZA", 100, "4800.00", "2026-10-01");
        openN("STO", 1, "ZZA", "2026-11-20", "52", "CALL", "60.35", "2026-10-20T10:00");
        closeN("BTC", 1, "ZZA", "2026-11-20", "52", "CALL", "20.65", "2026-10-30T10:00");
        openN("STO", 1, "ZZA", "2026-12-18", "55", "CALL", "85.35", "2026-11-02T10:00");
        openN("STO", 1, "ZZB", "2026-12-18", "38", "PUT", "120.00", "2026-11-05T10:00");
        sourcesG().apply();
        tickAt("2026-11-23T10:00", "SCHEDULED");

        JsonNode single = underlyingOf(A);
        assertThat(ugLines(single)).containsExactly(U_A_ZZA, U_A_ZZB);
        assertThat(underlyingOf(ALL)).as("ALL with one account = that account").isEqualTo(single);
        JsonNode g = greeksOf(A);
        assertThat(greeksOf(ALL)).as("ALL Greeks with one account = that account verbatim").isEqualTo(g);
        assertThat(pfLine(g)).isEqualTo("5200.00 7.00 -10.50 | 0/0/0 missing 0/0/0 | null | null | null");
    }

    @Test
    @DisplayName("AC10 只读：ALL 平仓 P1、开仓、改 P3 备注 → 400 ALL_ACCOUNTS_READ_ONLY，两接口与数据不变")
    void ac10_writesRejected() {
        scenarioG();
        Map<String, JsonNode> before = summariesWorld();
        Map<String, Object> lists = listsWorld();

        accountId = ALL;
        Resp close = closeResp("BTC", 1, "ZZA", "2026-12-18", "55", "CALL", "60.65", "2026-11-23T10:00");
        Resp open = post(TRADES, trade("STO", 1, "ZZB", "2026-12-18", "35", "PUT", "40.00",
                "2026-11-23T10:00").toString());
        long p3 = okAs(B, TRADES + "?symbol=ZZA&expirationDate=2026-12-18&strike=45&optionType=PUT")
                .get("items").get(0).get("id").asLong();
        Resp edit = exchange(HttpMethod.PUT, TRADES + "/" + p3,
                "{\"contracts\":2,\"netAmount\":\"230.00\",\"tradedAtLocal\":\"2026-11-05T10:00\",\"multiplier\":100,"
                        + "\"note\":\"改不了\"}");
        accountId = A;
        assertGuardError(close, READ_ONLY_CODE, TRADES + "/closes");
        assertGuardError(open, READ_ONLY_CODE, TRADES);
        assertGuardError(edit, READ_ONLY_CODE, TRADES + "/" + p3);

        assertThat(summariesWorld()).as("summaries unchanged by rejected ALL writes").isEqualTo(before);
        assertThat(listsWorld()).as("data unchanged by rejected ALL writes").isEqualTo(lists);
    }
}
