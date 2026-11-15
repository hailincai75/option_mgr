package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA system tests for FEAT-035 全部账户合并查看（一）: acceptance criteria 1–10 of the requirement over REST, with the
 * exact numbers of scenario S (see {@link Feat035AllAccountsSystemTestBase}) and the contract docs/api/FEAT-035.md.
 * Page-only parts of the criteria (hidden buttons, switcher, placeholders) are covered by the E2E spec
 * e2e/tests/feat-035-all-accounts-view.spec.ts; here the data the pages show and the backend guards.
 */
@DisplayName("FEAT-035 全部账户合并查看（一）验收（系统测试）")
class Feat035AllAccountsAcceptanceSystemTest extends Feat035AllAccountsSystemTestBase {

    // =========================================================================================== AC 1

    @Test
    @DisplayName("AC1 单账户甲：持仓只有 A4、A1 两行，每行 accountId 1 / 甲账户；ALL 与单账户只差请求头")
    void ac1_singleAccountAfterSwitch() {
        scenarioS();
        JsonNode a = okAs(A, OPTION_POSITIONS);
        assertThat(optionOrder(a)).containsExactly("1 甲账户 " + L_A4, "1 甲账户 " + L_A1);
        assertThat(a.get("positionCount").asInt()).isEqualTo(2);
        assertThat(a.get("frozenTotal").asText()).isEqualTo("5000.00");
        // rows still carry the write affordances of a single account (buttons come back on the page)
        JsonNode a1 = optRowOf(a, 1, L_A1);
        assertThat(a1.get("accountId").isIntegralNumber()).as("accountId is a JSON number").isTrue();
        assertThat(a1.get("accountName").isTextual()).isTrue();
        assertThat(a1.has("assignable")).isTrue();
        assertThat(a1.has("expirationAdjustable")).isTrue();

        JsonNode b = okAs(B, OPTION_POSITIONS);
        assertThat(optionOrder(b)).containsExactly("2 乙账户 " + L_B4, "2 乙账户 " + L_B3);

        // the switcher offers ALL only with 2+ accounts (Q220): here 2 accounts exist
        JsonNode accounts = noAccount(() -> get("/api/accounts")).body();
        assertThat(accounts.size()).isEqualTo(2);
    }

    // =========================================================================================== AC 2

    @Test
    @DisplayName("AC2 首页 ALL：现金 70,473.05 / 14,500.00 / 55,973.05；已实现 +90.00（2 笔）、本月 +60.00（1 笔）；期权 4 个、冻结 14,500.00、7 天内 1、已过期 1、股票 1")
    void ac2_dashboard() {
        scenarioS();
        JsonNode d = okAs(ALL, DASHBOARD);
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        JsonNode cash = d.get("cash");
        assertThat(List.of(cash.get("totalCash").asText(), cash.get("frozenCash").asText(),
                cash.get("availableCash").asText())).containsExactly("70473.05", "14500.00", "55973.05");
        assertThat(cash.get("totalCash").isTextual()).as("money as JSON string").isTrue();
        assertThat(d.get("realizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("90.00");
        assertThat(d.get("currentMonthRealizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("60.00");
        JsonNode p = d.get("positions");
        assertThat(p.get("positionCount").asInt()).isEqualTo(4);
        assertThat(p.get("frozenTotal").asText()).isEqualTo("14500.00");
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isEqualTo(1);
        assertThat(p.get("expiredUnprocessedCount").asInt()).isEqualTo(1);
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(1);

        // Q216: every number = sum of the two accounts' dashboards; structure identical to single account
        JsonNode da = okAs(A, DASHBOARD);
        JsonNode db = okAs(B, DASHBOARD);
        assertThat(fieldNames(d)).containsExactlyInAnyOrderElementsOf(fieldNames(da));
        // stockPositions.positionCount is the Q227 exception (distinct symbols across accounts), checked separately
        com.fasterxml.jackson.databind.node.ObjectNode dNoStock = d.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode daNoStock = da.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode dbNoStock = db.deepCopy();
        for (com.fasterxml.jackson.databind.node.ObjectNode n : List.of(dNoStock, daNoStock, dbNoStock)) {
            n.remove("stockPositions");
        }
        assertFieldwiseSum("dashboard", dNoStock, daNoStock, dbNoStock);
        assertThat(d.get("stockPositions").get("positionCount").asInt())
                .isEqualTo(distinctSymbols(okAs(ALL, STOCK_POSITIONS)));
        // single-account numbers of the scenario (unchanged behaviour)
        assertThat(da.get("cash").get("totalCash").asText()).isEqualTo("50263.05");
        assertThat(db.get("cash").get("availableCash").asText()).isEqualTo("10710.00");
        assertThat(da.get("realizedPnl").get("total").asText()).isEqualTo("60.00");
        assertThat(db.get("realizedPnl").get("total").asText()).isEqualTo("30.00");
        assertThat(db.get("currentMonthRealizedPnl").get("closedCount").asInt()).isZero();

        // consistency with the other ALL endpoints (contract table)
        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cash.get("totalCash")).isEqualTo(cs.get("totalCash"));
        assertThat(cash.get("frozenCash")).isEqualTo(cs.get("frozenCash"));
        assertThat(cash.get("availableCash")).isEqualTo(cs.get("availableCash"));
        JsonNode op = okAs(ALL, OPTION_POSITIONS);
        assertThat(p.get("positionCount").asInt()).isEqualTo(op.get("positionCount").asInt());
        assertThat(p.get("frozenTotal")).isEqualTo(op.get("frozenTotal"));
        assertThat(d.get("stockPositions").get("positionCount").asInt())
                .isEqualTo(okAs(ALL, STOCK_POSITIONS).get("positionCount").asInt());
    }

    // =========================================================================================== AC 3

    @Test
    @DisplayName("AC3 资金汇总 ALL：甲 50,263.05 / 5,000.00 / 45,263.05、乙 20,210.00 / 9,500.00 / 10,710.00、合计 70,473.05 / 14,500.00 / 55,973.05；单账户 accounts 只有自己")
    void ac3_cashSummary() {
        scenarioS();
        assertThat(okAs(ALL, CASH_SUMMARY)).isEqualTo(parse("""
                {
                  "totalCash": "70473.05",
                  "frozenCash": "14500.00",
                  "availableCash": "55973.05",
                  "accounts": [
                    { "accountId": 1, "accountName": "甲账户", "totalCash": "50263.05", "frozenCash": "5000.00", "availableCash": "45263.05" },
                    { "accountId": 2, "accountName": "乙账户", "totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00" }
                  ]
                }"""));
        assertThat(okAs(B, CASH_SUMMARY)).isEqualTo(parse("""
                {"totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00",
                 "accounts": [{"accountId": 2, "accountName": "乙账户", "totalCash": "20210.00", "frozenCash": "9500.00", "availableCash": "10710.00"}]}"""));
        assertThat(okAs(A, CASH_SUMMARY)).isEqualTo(parse("""
                {"totalCash": "50263.05", "frozenCash": "5000.00", "availableCash": "45263.05",
                 "accounts": [{"accountId": 1, "accountName": "甲账户", "totalCash": "50263.05", "frozenCash": "5000.00", "availableCash": "45263.05"}]}"""));
        // the ledger (流水) part is not merged in FEAT-035 (rule 7, FEAT-046)
        assertGuardError(getAs(ALL, "/api/cash/ledger"), NOT_SUPPORTED_CODE, "/api/cash/ledger");
    }

    // =========================================================================================== AC 4

    @Test
    @DisplayName("AC4 持仓 ALL：4 行依次 乙 B4（已过期 3 天，冻结 4,500.00）、甲 A4（DTE 4，已覆盖 100 股）、甲 A1（118.70，5,000.00）、乙 B3（120.00，5,000.00）；持仓 4 个、冻结 14,500.00")
    void ac4_optionRows() {
        scenarioS();
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(all.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(optionOrder(all)).containsExactly(
                "2 乙账户 " + L_B4, "1 甲账户 " + L_A4, "1 甲账户 " + L_A1, "2 乙账户 " + L_B3);
        assertThat(all.get("positionCount").asInt()).isEqualTo(4);
        assertThat(all.get("frozenTotal").asText()).isEqualTo("14500.00");

        JsonNode b4 = all.get("positions").get(0);
        assertThat(b4.get("dte").asInt()).isEqualTo(-3);
        assertThat(b4.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(b4.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(b4.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(b4.get("openingNetTotal").asText()).isEqualTo("60.00");
        assertThat(b4.get("direction").asText()).isEqualTo("SHORT");

        JsonNode a4 = all.get("positions").get(1);
        assertThat(a4.get("dte").asInt()).isEqualTo(4);
        assertThat(a4.get("expirationStatus").asText()).isEqualTo("ACTIVE");
        assertThat(a4.get("coveredShares").asInt()).isEqualTo(100);
        assertThat(a4.get("uncoveredContracts").asInt()).isZero();
        assertThat(a4.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(a4.get("openingNetTotal").asText()).isEqualTo("84.35");

        JsonNode a1 = all.get("positions").get(2);
        JsonNode b3 = all.get("positions").get(3);
        for (JsonNode r : List.of(a1, b3)) {
            assertThat(r.get("symbol").asText()).isEqualTo("QRTV");
            assertThat(r.get("expirationDate").asText()).isEqualTo("2026-12-18");
            assertThat(r.get("strike").asText()).isEqualTo("50");
            assertThat(r.get("optionType").asText()).isEqualTo("PUT");
            assertThat(r.get("contracts").asInt()).isEqualTo(1);
            assertThat(r.get("dte").asInt()).isEqualTo(25);
            assertThat(r.get("frozenCash").asText()).isEqualTo("5000.00");
        }
        assertThat(a1.get("openingNetTotal").asText()).isEqualTo("118.70");
        assertThat(b3.get("openingNetTotal").asText()).isEqualTo("120.00");
        // Q215: same contract stays two rows, each with only its own lots
        assertThat(a1.get("lots").size()).isEqualTo(1);
        assertThat(b3.get("lots").size()).isEqualTo(1);
        assertThat(a1.get("lots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("A1"));
        assertThat(b3.get("lots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("B3"));
    }

    @Test
    @DisplayName("AC4/Q53 ALL 的每一行（期权、股票）与该账户单独看到的行完全相同（全部字段），ALL 行 = 各账户行的并集")
    void ac4_rowsIdenticalToSingleAccount() {
        scenarioS();
        assertAllRowsEqualSingleRows(OPTION_POSITIONS, "contractLabel", List.of(A, B));
        assertAllRowsEqualSingleRows(STOCK_POSITIONS, "symbol", List.of(A, B));
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        // frozenTotal = sum of the accounts' frozenTotal
        assertThat(all.get("frozenTotal").asText()).isEqualTo(d(okAs(A, OPTION_POSITIONS).get("frozenTotal").asText())
                .add(d(okAs(B, OPTION_POSITIONS).get("frozenTotal").asText())).toPlainString());
    }

    // =========================================================================================== AC 5

    @Test
    @DisplayName("AC5 股票与未实现合计 ALL：甲 IXQZ 100 股 市值 6,110.00 未实现 +230.00；A1 −6.30、B3 −5.00、A4 −0.65、B4 已到期待处理；合计 −335.00/−11.95 ｜ 6,110.00/230.00 ｜ 5,775.00/218.05")
    void ac5_stockAndUnrealized() {
        scenarioS();
        JsonNode st = okAs(ALL, STOCK_POSITIONS);
        assertThat(st.get("positionCount").asInt()).isEqualTo(1);
        assertThat(stockOrder(st)).containsExactly("1 甲账户 IXQZ 100");
        JsonNode ix = st.get("positions").get(0);
        assertThat(ix.get("totalCost").asText()).isEqualTo("5880.00");
        assertThat(ix.get("coveredShares").asInt()).isEqualTo(100);
        assertThat(ix.get("availableShares").asInt()).isZero();
        assertThat(ix.get("quote").get("price").asText()).isEqualTo("61.10");
        assertThat(val(ix)).isEqualTo("6110.00 230.00 3.91 OK null");

        JsonNode op = okAs(ALL, OPTION_POSITIONS);
        assertThat(val(optRowOf(op, 1, L_A1))).isEqualTo("-125.00 -6.30 -5.31 OK null");
        assertThat(val(optRowOf(op, 2, L_B3))).isEqualTo("-125.00 -5.00 -4.17 OK null");
        assertThat(val(optRowOf(op, 1, L_A4))).isEqualTo("-85.00 -0.65 -0.77 OK null");
        assertThat(val(optRowOf(op, 2, L_B4))).isEqualTo("null null null EXPIRED_PENDING 已到期，待处理");
        // same contract, same mark in both accounts (one QuoteBook per request)
        assertThat(optRowOf(op, 1, L_A1).get("optionQuote").get("mark").asText()).isEqualTo("1.25");
        assertThat(optRowOf(op, 2, L_B3).get("optionQuote")).isEqualTo(optRowOf(op, 1, L_A1).get("optionQuote"));

        assertThat(okAs(ALL, UNREALIZED)).isEqualTo(parse("""
                {
                  "asOfDate": "2026-11-23",
                  "option": { "marketValue": "-335.00", "unrealizedPnl": "-11.95", "includedCount": 3, "noQuoteCount": 0, "expiredPendingCount": 1 },
                  "stock":  { "marketValue": "6110.00", "unrealizedPnl": "230.00", "includedCount": 1, "noQuoteCount": 0, "expiredPendingCount": 0 },
                  "total":  { "marketValue": "5775.00", "unrealizedPnl": "218.05", "includedCount": 4, "noQuoteCount": 0, "expiredPendingCount": 1 },
                  "excludedLabel": "1 个持仓已到期待处理，未计入"
                }"""));
        // Q216: ALL = fieldwise sum of both accounts (excludedLabel rebuilt from the summed counts)
        JsonNode ua = okAs(A, UNREALIZED);
        JsonNode ub = okAs(B, UNREALIZED);
        JsonNode u = okAs(ALL, UNREALIZED);
        for (String g : List.of("option", "stock", "total")) {
            assertFieldwiseSum("unrealized." + g, u.get(g), ua.get(g), ub.get(g));
        }
        assertThat(ua.get("excludedLabel").isNull()).as("甲 alone has nothing excluded").isTrue();
        assertThat(ub.get("excludedLabel").asText()).isEqualTo("1 个持仓已到期待处理，未计入");
    }

    @Test
    @DisplayName("AC5（Q227 补充）乙再转入 IXQZ 50 股：股票两行（甲 IXQZ、乙 IXQZ），共 1 个标的、首页股票持仓 1 个标的；乙单独 1 个标的；再加 QRTV（甲、乙各持）→ 4 行、2 个标的")
    void ac5_q227DistinctSymbolCount() {
        scenarioS();
        accountId = B;
        transferInId("IXQZ", 50, "3000.00", "2026-11-19");
        accountId = A;

        JsonNode st = okAs(ALL, STOCK_POSITIONS);
        assertThat(stockOrder(st)).containsExactly("1 甲账户 IXQZ 100", "2 乙账户 IXQZ 50");
        assertThat(st.get("positionCount").asInt()).as("共 1 个标的").isEqualTo(1);
        assertThat(okAs(ALL, DASHBOARD).get("stockPositions").get("positionCount").asInt())
                .as("首页 股票持仓 1 个标的").isEqualTo(1);
        assertThat(okAs(B, STOCK_POSITIONS).get("positionCount").asInt()).as("乙账户 1 个标的").isEqualTo(1);
        assertThat(okAs(B, DASHBOARD).get("stockPositions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(okAs(A, STOCK_POSITIONS).get("positionCount").asInt()).isEqualTo(1);
        // rows stay split and unchanged; market value / unrealized still add up (Q216); option counts untouched
        assertThat(val(stockRowOf(st, 2, "IXQZ"))).isEqualTo("3055.00 55.00 1.83 OK null");
        JsonNode u = okAs(ALL, UNREALIZED).get("stock");
        assertThat(u.get("marketValue").asText()).isEqualTo("9165.00");
        assertThat(u.get("unrealizedPnl").asText()).isEqualTo("285.00");
        assertThat(u.get("includedCount").asInt()).as("included rows, not symbols").isEqualTo(2);
        assertThat(okAs(ALL, DASHBOARD).get("positions").get("positionCount").asInt()).isEqualTo(4);
        assertAllRowsEqualSingleRows(STOCK_POSITIONS, "symbol", List.of(A, B));

        // decision example: 甲 has QRTV + IXQZ, 乙 has QRTV (+ IXQZ) → 2 distinct symbols
        transferInId("QRTV", 10, "500.00", "2026-11-19");
        accountId = B;
        transferInId("QRTV", 20, "1000.00", "2026-11-19");
        accountId = A;
        JsonNode st2 = okAs(ALL, STOCK_POSITIONS);
        assertThat(stockOrder(st2)).containsExactly("1 甲账户 IXQZ 100", "2 乙账户 IXQZ 50", "1 甲账户 QRTV 10",
                "2 乙账户 QRTV 20");
        assertThat(st2.get("positionCount").asInt()).isEqualTo(2);
        assertThat(okAs(ALL, DASHBOARD).get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
        assertThat(okAs(A, STOCK_POSITIONS).get("positionCount").asInt()).isEqualTo(2);
        assertThat(okAs(B, STOCK_POSITIONS).get("positionCount").asInt()).isEqualTo(2);
    }

    // =========================================================================================== AC 6

    @Test
    @DisplayName("AC6 到期过滤的数据：7 天内只有甲 A4（DTE 4）、已过期未处理只有乙 B4；frozenTotal 与未实现合计不随过滤（后端无过滤参数）")
    void ac6_expiryFilterData() {
        scenarioS();
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        List<String> within7 = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (JsonNode p : all.get("positions")) {
            String key = p.get("accountName").asText() + " " + p.get("contractLabel").asText();
            if (p.get("expirationStatus").asText().equals("EXPIRED")) {
                expired.add(key);
            } else if (p.get("dte").asInt() >= 0 && p.get("dte").asInt() <= 7) {
                within7.add(key);
            }
        }
        assertThat(within7).containsExactly("甲账户 " + L_A4);
        assertThat(expired).containsExactly("乙账户 " + L_B4);
        JsonNode d = okAs(ALL, DASHBOARD).get("positions");
        assertThat(d.get("expiringWithin7DaysCount").asInt()).isEqualTo(within7.size());
        assertThat(d.get("expiredUnprocessedCount").asInt()).isEqualTo(expired.size());
        // the page link ?expiry=7 is a frontend filter: the same request with the query param changes nothing
        JsonNode withParam = okAs(ALL, OPTION_POSITIONS + "?expiry=7");
        assertThat(withParam.get("frozenTotal").asText()).isEqualTo("14500.00");
        assertThat(withParam.get("positionCount").asInt()).isEqualTo(4);
        assertThat(okAs(ALL, UNREALIZED + "?expiry=7").get("total").get("unrealizedPnl").asText())
                .isEqualTo("218.05");
    }

    // =========================================================================================== AC 7

    @Test
    @DisplayName("AC7 盈亏汇总 ALL：QRTV 2 笔 +90.00（期权 +90.00、股票 0.00）；2026-11 1 笔 +60.00、2026-10 1 笔 +30.00；11 月范围 QRTV 1 笔 +60.00；乙账户 QRTV 1 笔 +30.00")
    void ac7_pnlSummary() {
        scenarioS();
        assertThat(okAs(ALL, PNL)).isEqualTo(parse("""
                {
                  "startDate": null,
                  "endDate": null,
                  "closedCount": 2,
                  "realizedPnlTotal": "90.00",
                  "option": { "closedCount": 2, "realizedPnl": "90.00" },
                  "stock": { "closedCount": 0, "realizedPnl": "0.00" },
                  "bySymbol": [
                    { "symbol": "QRTV", "closedCount": 2, "realizedPnl": "90.00",
                      "option": { "closedCount": 2, "realizedPnl": "90.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
                  ],
                  "byMonth": [
                    { "month": "2026-11", "closedCount": 1, "realizedPnl": "60.00",
                      "option": { "closedCount": 1, "realizedPnl": "60.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } },
                    { "month": "2026-10", "closedCount": 1, "realizedPnl": "30.00",
                      "option": { "closedCount": 1, "realizedPnl": "30.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
                  ]
                }"""));

        JsonNode nov = okAs(ALL, PNL + "?startDate=2026-11-01&endDate=2026-11-30");
        assertThat(nov.get("startDate").asText()).isEqualTo("2026-11-01");
        assertThat(nov.get("endDate").asText()).isEqualTo("2026-11-30");
        assertThat(nov.get("closedCount").asInt()).isEqualTo(1);
        assertThat(nov.get("realizedPnlTotal").asText()).isEqualTo("60.00");
        assertThat(nov.get("bySymbol").size()).isEqualTo(1);
        assertThat(nov.get("bySymbol").get(0).get("symbol").asText()).isEqualTo("QRTV");
        assertThat(nov.get("bySymbol").get(0).get("closedCount").asInt()).isEqualTo(1);
        assertThat(nov.get("bySymbol").get(0).get("realizedPnl").asText()).isEqualTo("60.00");
        assertThat(nov.get("byMonth").size()).isEqualTo(1);
        assertThat(nov.get("byMonth").get(0).get("month").asText()).isEqualTo("2026-11");

        JsonNode b = okAs(B, PNL);
        assertThat(b.get("closedCount").asInt()).isEqualTo(1);
        assertThat(b.get("realizedPnlTotal").asText()).isEqualTo("30.00");
        assertThat(b.get("bySymbol").size()).isEqualTo(1);
        assertThat(b.get("bySymbol").get(0).get("symbol").asText()).isEqualTo("QRTV");
        assertThat(b.get("bySymbol").get(0).get("closedCount").asInt()).isEqualTo(1);
        assertThat(b.get("bySymbol").get(0).get("realizedPnl").asText()).isEqualTo("30.00");

        // parameter validation unchanged under ALL (FEAT-008): reversed range → 400 VALIDATION_ERROR
        Resp bad = getAs(ALL, PNL + "?startDate=2026-11-30&endDate=2026-11-01");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code").asText()).isEqualTo(getAs(A, PNL + "?startDate=2026-11-30&endDate=2026-11-01")
                .body().get("code").asText());
    }

    // =========================================================================================== AC 8

    @Test
    @DisplayName("AC8 临时休市 2026-12-18（Test Closure）ALL：2 个持仓（甲账户 1 个、乙账户 1 个），byAccount 两项；乙账户单独 1 个、FEAT-042 原文案；B3 行 expiresOnAdhocClosure")
    void ac8_adhocClosureAlert() {
        scenarioData(); // A1 and B3 entered BEFORE the closure exists (Q126)
        setNow("2026-11-23T09:55");
        injectDec18Closure();
        pull();

        assertThat(okAs(ALL, ALERTS)).isEqualTo(parse("""
                {
                  "alerts": [
                    {
                      "date": "2026-12-18",
                      "eventName": "Test Closure",
                      "affectedPositionCount": 2,
                      "byAccount": [
                        { "accountId": 1, "accountName": "甲账户", "affectedPositionCount": 1 },
                        { "accountId": 2, "accountName": "乙账户", "affectedPositionCount": 1 }
                      ],
                      "message": "2026-12-18 临时休市（Test Closure），你有 2 个持仓在该日到期（甲账户 1 个、乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日"
                    }
                  ]
                }"""));
        assertThat(okAs(B, ALERTS)).isEqualTo(parse("""
                {
                  "alerts": [
                    {
                      "date": "2026-12-18",
                      "eventName": "Test Closure",
                      "affectedPositionCount": 1,
                      "byAccount": [ { "accountId": 2, "accountName": "乙账户", "affectedPositionCount": 1 } ],
                      "message": "2026-12-18 临时休市（Test Closure），你有 1 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日"
                    }
                  ]
                }"""));
        JsonNode a = okAs(A, ALERTS).get("alerts").get(0);
        assertThat(a.get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(a.get("byAccount").size()).isEqualTo(1);
        assertThat(a.get("byAccount").get(0).get("accountId").asLong()).isEqualTo(1);

        // "切换到 乙账户 去调整" → single-account positions page locates B3 (first expiresOnAdhocClosure row)
        JsonNode bRows = okAs(B, OPTION_POSITIONS);
        JsonNode first = null;
        for (JsonNode p : bRows.get("positions")) {
            if (p.get("expiresOnAdhocClosure").asBoolean() && first == null) {
                first = p;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("contractLabel").asText()).isEqualTo(L_B3);
        assertThat(first.get("adhocClosureEventName").asText()).isEqualTo(CLOSURE_NAME);
        assertThat(first.get("lots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("B3"));
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        assertThat(optRowOf(all, 1, L_A1).get("expiresOnAdhocClosure").asBoolean()).isTrue();
        assertThat(optRowOf(all, 2, L_B3).get("expiresOnAdhocClosure").asBoolean()).isTrue();
        assertThat(optRowOf(all, 1, L_A4).get("expiresOnAdhocClosure").asBoolean()).isFalse();
        assertThat(optRowOf(all, 2, L_B4).get("expiresOnAdhocClosure").asBoolean()).isFalse();
    }

    // =========================================================================================== AC 9

    @Test
    @DisplayName("AC9 不指明单个账户的入金 100.00：ALL → 400 ALL_ACCOUNTS_READ_ONLY（中文、details X-Account-Id）；无请求头 → ACCOUNT_REQUIRED；两个账户现金、流水都不变")
    void ac9_writeRejected() {
        scenarioS();
        java.util.Map<String, Object> before = world();
        String body = "{\"type\": \"DEPOSIT\", \"amount\": \"100.00\", \"occurredAtLocal\": \"2026-11-23T09:30\", \"remark\": null}";
        accountId = ALL;
        Resp r = post(TRANSFERS, body);
        assertGuardError(r, READ_ONLY_CODE, TRANSFERS);
        accountId = null;
        Resp none = post(TRANSFERS, body);
        assertErrorShape(none, 400, "ACCOUNT_REQUIRED", TRANSFERS);
        accountId = A;
        assertThat(world()).as("nothing written").isEqualTo(before);
        assertThat(okAs(A, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("50263.05");
        assertThat(okAs(B, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("20210.00");
    }

    // =========================================================================================== AC 10

    @Test
    @DisplayName("AC10 兜底：交易列表、股票交易、已平仓、策略（underlying-summary）、组合 Greeks、审计、流水收到 ALL → 400 ALL_ACCOUNTS_NOT_SUPPORTED；ALL 持仓行的每行 Greeks 照常")
    void ac10_unsupportedAndRowGreeks() {
        scenarioS();
        long a1 = ids.get("A1");
        long ixqz = ids.get("A_IXQZ");
        for (String path : List.of(TRADES, TRADES + "/" + a1, "/api/closed-trades", STOCK_TRADES,
                STOCK_TRADES + "/" + ixqz, "/api/cash/ledger", "/api/positions/greeks-summary",
                "/api/positions/underlying-summary", "/api/audit-log")) {
            Resp r = getAs(ALL, path);
            assertGuardError(r, NOT_SUPPORTED_CODE, path);
            assertThat(getAs(A, path).status()).as("%s still OK for a single account", path).isEqualTo(200);
        }
        JsonNode all = okAs(ALL, OPTION_POSITIONS);
        JsonNode g = optRowOf(all, 2, L_B3).get("greeks");
        assertThat(g).as("per-row greeks present under ALL").isNotNull();
        assertThat(g.isObject()).isTrue();
        assertThat(g).isEqualTo(optRowOf(okAs(B, OPTION_POSITIONS), 2, L_B3).get("greeks"));
        assertThat(g).isEqualTo(optRowOf(all, 1, L_A1).get("greeks"));
    }
}
