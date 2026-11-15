package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * QA system tests for FEAT-046 全部账户合并查看（二）: acceptance criteria 1–10 of the requirement over REST, with the exact
 * numbers of scenario L (see {@link Feat046AllAccountsListsSystemTestBase}) and the contract docs/api/FEAT-046.md.
 * Page-only parts (账户 column, hidden write controls, 历史 switching the account, 账户余额 header) are covered by the E2E
 * spec e2e/tests/feat-046-all-accounts-lists.spec.ts; here the data the pages show and the backend guards.
 */
@DisplayName("FEAT-046 全部账户合并查看（二）验收（系统测试）")
class Feat046AllAccountsListsAcceptanceSystemTest extends Feat046AllAccountsListsSystemTestBase {

    /** "id accountId accountName action contractLabel tradedAtLocal cashChange frozenChange realizedPnl hasAuditLog". */
    static List<String> tradeRows(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : page.get("items")) {
            out.add(t.get("id").asLong() + " " + acc(t) + " " + t.get("action").asText() + " "
                    + t.get("contractLabel").asText() + " " + t.get("tradedAtLocal").asText() + " "
                    + t.get("cashChange").asText() + " " + t.get("frozenChange").asText() + " "
                    + text(t.get("realizedPnl")) + " " + t.get("hasAuditLog").asBoolean());
        }
        return out;
    }

    // =========================================================================================== AC 1

    @Test
    @DisplayName("AC1 交易列表 ALL：共 8 条，#11 甲 / #10 乙 / #9 甲 / #8 甲 / #7 乙 / #6 甲 / #5 乙 / #4 乙（同一时间按录入顺序，Q229），净额 / 冻结与单账户相同")
    void ac1_tradeList() {
        scenarioL();
        JsonNode all = okAs(ALL, TRADES);
        assertThat(all.get("page").asInt()).isEqualTo(1);
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(all.get("totalItems").asInt()).isEqualTo(8);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);
        assertThat(tradeRows(all)).containsExactly(
                "8 1 甲账户 STO " + C_65C + " 2026-11-16T10:30:00 84.35 0.00 null false",
                "7 2 乙账户 STO " + C_50P + " 2026-11-16T10:00:00 120.00 5000.00 null true",
                "6 1 甲账户 STO " + C_50P + " 2026-11-16T10:00:00 118.70 5000.00 null false",
                "5 1 甲账户 BTC " + C_48P + " 2026-11-10T10:00:00 -40.00 -4800.00 60.00 false",
                "4 2 乙账户 STO " + C_45P + " 2026-11-02T10:00:00 60.00 4500.00 null false",
                "3 1 甲账户 STO " + C_48P + " 2026-11-02T10:00:00 100.00 4800.00 null false",
                "2 2 乙账户 STC " + C_55C + " 2026-10-20T10:00:00 80.00 0.00 30.00 false",
                "1 2 乙账户 BTO " + C_55C + " 2026-10-15T10:00:00 -50.00 0.00 null false");
        // accountName is a string, accountId a number (contract)
        JsonNode first = all.get("items").get(0);
        assertThat(first.get("accountId").isIntegralNumber()).isTrue();
        assertThat(first.get("accountName").isTextual()).isTrue();
        // covered lots of #5 (STC) belong to the same account and carry no account fields (rule 2)
        JsonNode stc = all.get("items").get(6);
        assertThat(stc.get("coveredLots").size()).isEqualTo(1);
        JsonNode lot = stc.get("coveredLots").get(0);
        assertThat(lot.get("tradeId").asLong()).isEqualTo(1);
        assertThat(lot.get("netAmount").asText()).isEqualTo("-50.00");
        assertThat(lot.has("accountId")).as("lots have no account fields").isFalse();
        assertThat(lot.has("accountName")).isFalse();
        // Q53: every ALL row = the row of the same account read with its own X-Account-Id
        assertAllIsUnionOfSingles(TRADES, "", List.of(A, B));
    }

    // =========================================================================================== AC 2

    @Test
    @DisplayName("AC2 交易列表筛选 ALL：标的 qrtv → 7 条（无 #11）；合约 QRTV 2026-12-18 50 P → #10、#9；清空后方向 STO → #11 #10 #9 #7 #6；乙账户 + STO → #10、#7")
    void ac2_tradeFilters() {
        scenarioL();
        JsonNode sym = okAs(ALL, TRADES + "?symbol=qrtv");
        assertThat(ids(sym)).containsExactly(7L, 6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(sym.get("totalItems").asInt()).isEqualTo(7);

        JsonNode contract = okAs(ALL, TRADES + "?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT");
        assertThat(ids(contract)).containsExactly(7L, 6L);
        assertThat(accounts(contract)).containsExactly(2L, 1L);
        assertThat(contract.get("totalItems").asInt()).isEqualTo(2);

        JsonNode sto = okAs(ALL, TRADES + "?action=STO");
        assertThat(ids(sto)).containsExactly(8L, 7L, 6L, 4L, 3L);
        assertThat(accounts(sto)).containsExactly(1L, 2L, 1L, 2L, 1L);
        assertThat(sto.get("totalItems").asInt()).isEqualTo(5);

        // switching to 乙 keeps the same query parameters (Q55)
        JsonNode bSto = okAs(B, TRADES + "?action=STO");
        assertThat(ids(bSto)).containsExactly(7L, 4L);
        assertThat(bSto.get("totalItems").asInt()).isEqualTo(2);
        assertThat(accounts(bSto)).containsOnly(2L);

        // filters act on the merged records: each ALL result = union of the single-account results
        for (String query : List.of("symbol=qrtv", "symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT",
                "action=STO", "action=BTC,STC", "startDate=2026-11-01&endDate=2026-11-16", "optionType=CALL")) {
            assertAllIsUnionOfSingles(TRADES, query, List.of(A, B));
        }
    }

    // =========================================================================================== AC 3

    @Test
    @DisplayName("AC3 股票交易 ALL：共 3 条 #13 乙 卖出 QRTV 50 股 +2,450.00（来自 #12 50 股 成本 2,400.00）、#12 乙 买入 −4,800.00、#3 甲 转入 IXQZ 成本 5,880.00；IXQZ → #3；卖出 → #13")
    void ac3_stockTrades() {
        scenarioL();
        JsonNode all = okAs(ALL, STOCK_TRADES);
        assertThat(all.get("totalItems").asInt()).isEqualTo(3);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);
        List<String> rows = new ArrayList<>();
        for (JsonNode s : all.get("items")) {
            rows.add(s.get("id").asLong() + " " + acc(s) + " " + s.get("type").asText() + " "
                    + s.get("symbol").asText() + " " + s.get("shares").asInt() + " " + s.get("amount").asText() + " "
                    + s.get("cashChange").asText() + " " + text(s.get("soldCost")) + " "
                    + text(s.get("realizedPnl")));
        }
        assertThat(rows).containsExactly(
                "3 2 乙账户 SELL QRTV 50 2450.00 2450.00 2400.00 50.00",
                "2 2 乙账户 BUY QRTV 100 4800.00 -4800.00 null null",
                "1 1 甲账户 TRANSFER_IN IXQZ 100 5880.00 0.00 null null");
        JsonNode sold = all.get("items").get(0).get("soldLots");
        assertThat(sold.size()).isEqualTo(1);
        assertThat(sold.get(0).get("tradeId").asLong()).isEqualTo(2);
        assertThat(sold.get(0).get("type").asText()).isEqualTo("BUY");
        assertThat(sold.get(0).get("shares").asInt()).isEqualTo(50);
        assertThat(sold.get(0).get("cost").asText()).isEqualTo("2400.00");
        assertThat(sold.get(0).has("accountId")).as("sold lots carry no account fields (rule 2)").isFalse();
        assertThat(all.get("items").get(2).get("tradeDate").asText()).isEqualTo("2026-10-01");

        JsonNode ixqz = okAs(ALL, STOCK_TRADES + "?symbol=IXQZ");
        assertThat(ids(ixqz)).containsExactly(1L);
        assertThat(ixqz.get("totalItems").asInt()).isEqualTo(1);
        JsonNode sell = okAs(ALL, STOCK_TRADES + "?type=SELL");
        assertThat(ids(sell)).containsExactly(3L);
        assertThat(sell.get("totalItems").asInt()).isEqualTo(1);

        for (String query : List.of("", "symbol=IXQZ", "type=SELL", "type=BUY,TRANSFER_IN",
                "startDate=2026-11-01&endDate=2026-11-30")) {
            assertAllIsUnionOfSingles(STOCK_TRADES, query, List.of(A, B));
        }
    }

    // =========================================================================================== AC 4

    @Test
    @DisplayName("AC4 已平仓 ALL：共 3 笔 +140.00：#13 乙 股票 QRTV +50.00、#8 甲 期权 48 P +60.00、#5 乙 期权 55 C +30.00；股票 1 笔 +50.00；期权 2 笔 +90.00；11 月 #13 #8 2 笔 +110.00")
    void ac4_closedTrades() {
        scenarioL();
        JsonNode all = okAs(ALL, CLOSED);
        assertThat(closedKeys(all)).containsExactly("STOCK-3", "OPTION-5", "OPTION-2");
        assertThat(accounts(all)).containsExactly(2L, 1L, 2L);
        List<String> rows = new ArrayList<>();
        for (JsonNode c : all.get("items")) {
            rows.add(acc(c) + " " + c.get("type").asText() + " " + c.get("symbol").asText() + " "
                    + text(c.get("contractLabel")) + " " + c.get("action").asText() + " "
                    + c.get("openingNetTotal").asText() + " " + c.get("closeNetAmount").asText() + " "
                    + c.get("realizedPnl").asText());
        }
        assertThat(rows).containsExactly(
                "2 乙账户 STOCK QRTV null SELL -2400.00 2450.00 50.00",
                "1 甲账户 OPTION QRTV " + C_48P + " BTC 100.00 -40.00 60.00",
                "2 乙账户 OPTION QRTV " + C_55C + " STC -50.00 80.00 30.00");
        assertThat(all.get("totalItems").asInt()).isEqualTo(3);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);
        assertThat(all.get("filteredCount").asInt()).isEqualTo(3);
        assertThat(all.get("filteredRealizedPnlTotal").asText()).isEqualTo("140.00");
        assertThat(all.get("items").get(1).get("coveredLots").get(0).get("frozen").asText()).isEqualTo("4800.00");
        assertThat(all.get("items").get(0).get("soldLots").get(0).get("netAmount").asText()).isEqualTo("-2400.00");

        assertClosed("type=STOCK", List.of("STOCK-3"), 1, "50.00");
        assertClosed("type=OPTION", List.of("OPTION-5", "OPTION-2"), 2, "90.00");
        assertClosed("startDate=2026-11-01&endDate=2026-11-30", List.of("STOCK-3", "OPTION-5"), 2, "110.00");
        assertClosed("symbol=QRTV", List.of("STOCK-3", "OPTION-5", "OPTION-2"), 3, "140.00");
        assertClosed("startDate=2026-10-01&endDate=2026-10-31", List.of("OPTION-2"), 1, "30.00");
        // Q90: an option-only condition removes the stock rows, under ALL too
        assertClosed("action=BTC,STC", List.of("OPTION-5", "OPTION-2"), 2, "90.00");
        assertClosed("optionType=PUT", List.of("OPTION-5"), 1, "60.00");

        for (String query : List.of("", "type=STOCK", "type=OPTION", "startDate=2026-11-01&endDate=2026-11-30",
                "symbol=QRTV", "action=STC")) {
            assertAllIsUnionOfSingles(CLOSED, query, List.of(A, B));
        }
    }

    void assertClosed(String query, List<String> keys, int count, String total) {
        JsonNode c = okAs(ALL, CLOSED + "?" + query);
        assertThat(closedKeys(c)).as("ALL closed ?%s", query).containsExactlyElementsOf(keys);
        assertThat(c.get("totalItems").asInt()).isEqualTo(keys.size());
        assertThat(c.get("filteredCount").asInt()).as("filteredCount ?%s", query).isEqualTo(count);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).as("total ?%s", query).isEqualTo(total);
    }

    // =========================================================================================== AC 5

    @Test
    @DisplayName("AC5 盈亏汇总 ALL：3 笔 +140.00（期权 2 笔 +90.00、股票 1 笔 +50.00），QRTV 3 笔 +140.00，2026-11 2 笔 +110.00、2026-10 1 笔 +30.00；与已平仓 ALL 合计一致（规则 6）；点 QRTV / 2026-10 的结果")
    void ac5_pnlSummaryMatchesClosed() {
        scenarioL();
        assertThat(okAs(ALL, PNL)).isEqualTo(parse("""
                {
                  "startDate": null,
                  "endDate": null,
                  "closedCount": 3,
                  "realizedPnlTotal": "140.00",
                  "option": { "closedCount": 2, "realizedPnl": "90.00" },
                  "stock": { "closedCount": 1, "realizedPnl": "50.00" },
                  "bySymbol": [
                    { "symbol": "QRTV", "closedCount": 3, "realizedPnl": "140.00",
                      "option": { "closedCount": 2, "realizedPnl": "90.00" }, "stock": { "closedCount": 1, "realizedPnl": "50.00" } }
                  ],
                  "byMonth": [
                    { "month": "2026-11", "closedCount": 2, "realizedPnl": "110.00",
                      "option": { "closedCount": 1, "realizedPnl": "60.00" }, "stock": { "closedCount": 1, "realizedPnl": "50.00" } },
                    { "month": "2026-10", "closedCount": 1, "realizedPnl": "30.00",
                      "option": { "closedCount": 1, "realizedPnl": "30.00" }, "stock": { "closedCount": 0, "realizedPnl": "0.00" } }
                  ]
                }"""));
        // the drill-down links: 点 QRTV → closed symbol=QRTV; 点 2026-10 → that month
        assertClosed("symbol=QRTV", List.of("STOCK-3", "OPTION-5", "OPTION-2"), 3, "140.00");
        assertClosed("startDate=2026-10-01&endDate=2026-10-31", List.of("OPTION-2"), 1, "30.00");

        // rule 6 consistency (restored from FEAT-008 / 040), for ALL and each single account, several ranges
        for (String scope : List.of(ALL, A, B)) {
            assertPnlMatchesClosed(scope, null, null);
            assertPnlMatchesClosed(scope, "2026-11-01", "2026-11-30");
            assertPnlMatchesClosed(scope, "2026-10-01", "2026-10-31");
            assertPnlMatchesClosed(scope, "2026-11-01", "2026-11-23");
            assertPnlMatchesClosed(scope, "2026-10-20", "2026-11-10");
            assertPnlMatchesClosed(scope, "2026-12-01", "2026-12-31");
        }
    }

    // =========================================================================================== AC 6

    @Test
    @DisplayName("AC6 深链接数据 ALL：首页本月（2026-11）+110.00（2 笔）→ 已平仓 2026-11-01 ~ 2026-11-23 #13 #8；#8 合约 → 交易 #8 #6；IXQZ → 股票 #3")
    void ac6_deepLinks() {
        scenarioL();
        JsonNode dash = okAs(ALL, DASHBOARD);
        assertThat(dash.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(dash.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(dash.get("currentMonthRealizedPnl")).isEqualTo(parse("{\"closedCount\": 2, \"total\": \"110.00\"}"));

        assertClosed("startDate=2026-11-01&endDate=2026-11-23", List.of("STOCK-3", "OPTION-5"), 2, "110.00");

        JsonNode c48 = okAs(ALL, TRADES + "?symbol=QRTV&expirationDate=2026-12-18&strike=48&optionType=PUT");
        assertThat(ids(c48)).containsExactly(5L, 3L);
        assertThat(accounts(c48)).containsOnly(1L);

        // 持仓页股票表 IXQZ row (甲) → stock-trades?symbol=IXQZ
        JsonNode stockPos = okAs(ALL, STOCK_POSITIONS);
        assertThat(stockRowOf(stockPos, 1, "IXQZ").get("shares").asInt()).isEqualTo(100);
        JsonNode ixqz = okAs(ALL, STOCK_TRADES + "?symbol=IXQZ");
        assertThat(ids(ixqz)).containsExactly(1L);
        assertThat(acc(ixqz.get("items").get(0))).isEqualTo("1 甲账户");
    }

    // =========================================================================================== AC 7

    @Test
    @DisplayName("AC7 资金流水 ALL：12 条，每行余额为所在账户自己的余额（Q228）：乙 卖出 +2,450.00 余额 17,860.00 … 甲 入金 50,000.00；汇总 68,123.05 / 14,500.00 / 53,623.05（需求原文 70,123.05 / 55,623.05 为加法错误，见契约更正）")
    void ac7_ledger() {
        scenarioL();
        JsonNode all = okAs(ALL, LEDGER);
        assertThat(all.get("page").asInt()).isEqualTo(1);
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(all.get("totalItems").asInt()).isEqualTo(12);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);
        List<String> rows = new ArrayList<>();
        for (JsonNode e : all.get("items")) {
            rows.add(e.get("id").asLong() + " " + acc(e) + " " + e.get("occurredAt").asText() + " "
                    + e.get("sourceType").asText() + " " + e.get("sourceId").asLong() + " " + e.get("amount").asText()
                    + " " + e.get("balanceAfter").asText() + " " + e.get("frozenChange").asText() + " "
                    + text(e.get("remark")) + " " + e.get("hasAuditLog").asBoolean());
        }
        assertThat(rows).containsExactly(
                "12 2 乙账户 2026-11-18T15:00:00Z STOCK_SELL 3 2450.00 17860.00 0.00 null false",
                "11 2 乙账户 2026-11-17T15:00:00Z STOCK_BUY 2 -4800.00 15410.00 0.00 null false",
                "10 1 甲账户 2026-11-16T15:30:00Z OPTION_OPEN 8 84.35 50263.05 0.00 null false",
                "9 2 乙账户 2026-11-16T15:00:00Z OPTION_OPEN 7 120.00 20210.00 5000.00 同价位 false",
                "8 1 甲账户 2026-11-16T15:00:00Z OPTION_OPEN 6 118.70 50178.70 5000.00 null false",
                "7 1 甲账户 2026-11-10T15:00:00Z OPTION_CLOSE 5 -40.00 50060.00 -4800.00 null false",
                "6 2 乙账户 2026-11-02T15:00:00Z OPTION_OPEN 4 60.00 20090.00 4500.00 null false",
                "5 1 甲账户 2026-11-02T15:00:00Z OPTION_OPEN 3 100.00 50100.00 4800.00 null false",
                "4 2 乙账户 2026-10-20T14:00:00Z OPTION_CLOSE 2 80.00 20030.00 0.00 null false",
                "3 2 乙账户 2026-10-15T14:00:00Z OPTION_OPEN 1 -50.00 19950.00 0.00 null false",
                "2 2 乙账户 2026-10-01T13:30:00Z DEPOSIT 2 20000.00 20000.00 0.00 null false",
                "1 1 甲账户 2026-10-01T13:30:00Z DEPOSIT 1 50000.00 50000.00 0.00 null false");
        // Q228: the balance of each row = the same row's balance seen in its own account
        assertAllIsUnionOfSingles(LEDGER, "", List.of(A, B));

        JsonNode cs = okAs(ALL, CASH_SUMMARY);
        assertThat(cs.get("totalCash").asText()).isEqualTo("68123.05");
        assertThat(cs.get("frozenCash").asText()).isEqualTo("14500.00");
        assertThat(cs.get("availableCash").asText()).isEqualTo("53623.05");
        assertThat(okAs(A, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("50263.05");
        assertThat(okAs(B, CASH_SUMMARY).get("totalCash").asText()).isEqualTo("17860.00");
        assertThat(okAs(B, CASH_SUMMARY).get("availableCash").asText()).isEqualTo("8360.00");
    }

    // =========================================================================================== AC 8

    @Test
    @DisplayName("AC8 历史：ALL 交易列表只有 #10（乙）hasAuditLog；用乙账户读审计 OPTION_TRADE #10 → 那次备注修改；甲账户读不到；ALL 读审计仍 NOT_SUPPORTED")
    void ac8_history() {
        scenarioL();
        JsonNode all = okAs(ALL, TRADES);
        List<String> withAudit = new ArrayList<>();
        for (JsonNode t : all.get("items")) {
            if (t.get("hasAuditLog").asBoolean()) {
                withAudit.add(t.get("id").asLong() + " " + acc(t));
            }
        }
        assertThat(withAudit).containsExactly(L.get("#10") + " 2 乙账户");
        for (String p : List.of(STOCK_TRADES, CLOSED, LEDGER)) {
            for (JsonNode r : okAs(ALL, p + "?size=100").get("items")) {
                assertThat(r.get("hasAuditLog").asBoolean()).as("%s %s has no audit", p, rowKey(p, r)).isFalse();
            }
        }

        String auditPath = "/api/audit-log?objectType=OPTION_TRADE&objectId=" + L.get("#10");
        JsonNode audit = okAs(B, auditPath);
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        JsonNode rec = audit.get("items").get(0);
        assertThat(rec.get("operation").asText()).isEqualTo("UPDATE");
        assertThat(rec.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(rec.get("objectId").asLong()).isEqualTo(L.get("#10"));
        assertThat(rec.get("summary").asText()).isEqualTo("STO " + C_50P);
        assertThat(rec.get("before").get("note").isNull()).isTrue();
        assertThat(rec.get("after").get("note").asText()).isEqualTo("同价位");
        List<String> changed = new ArrayList<>();
        rec.get("changes").forEach(c -> changed.add(c.get("field").asText() + "=" + c.get("after").asText()));
        assertThat(changed).containsExactly("note=同价位");

        assertThat(okAs(A, auditPath).get("totalItems").asInt()).as("the audit belongs to 乙 only").isZero();
        assertGuardError(getAs(ALL, auditPath), NOT_SUPPORTED_CODE, "/api/audit-log");
    }

    // =========================================================================================== AC 9

    @Test
    @DisplayName("AC9 切回甲账户：交易 4 条（#11 #9 #8 #6）、已平仓 1 笔 +60.00、流水 5 条首行余额 50,263.05，行上 accountId 1")
    void ac9_singleAccount() {
        scenarioL();
        JsonNode t = okAs(A, TRADES);
        assertThat(ids(t)).containsExactly(8L, 6L, 5L, 3L);
        assertThat(t.get("totalItems").asInt()).isEqualTo(4);
        for (JsonNode r : t.get("items")) {
            assertThat(acc(r)).isEqualTo("1 甲账户");
        }
        JsonNode c = okAs(A, CLOSED);
        assertThat(closedKeys(c)).containsExactly("OPTION-5");
        assertThat(c.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("60.00");
        JsonNode l = okAs(A, LEDGER);
        assertThat(ids(l)).containsExactly(10L, 8L, 7L, 5L, 1L);
        assertThat(l.get("totalItems").asInt()).isEqualTo(5);
        assertThat(l.get("items").get(0).get("balanceAfter").asText()).isEqualTo("50263.05");
        JsonNode s = okAs(A, STOCK_TRADES);
        assertThat(ids(s)).containsExactly(1L);
        assertThat(acc(s.get("items").get(0))).isEqualTo("1 甲账户");
        // 乙 for completeness
        assertThat(ids(okAs(B, TRADES))).containsExactly(7L, 4L, 2L, 1L);
        assertThat(ids(okAs(B, LEDGER))).containsExactly(12L, 11L, 9L, 6L, 4L, 3L, 2L);
        assertThat(okAs(B, CLOSED).get("filteredRealizedPnlTotal").asText()).isEqualTo("80.00");
    }

    // =========================================================================================== AC 10

    @Test
    @DisplayName("AC10 分页：ALL 交易列表 size=5 第 1 页 #11 #10 #9 #8 #7，第 2 页 #6 #5 #4，totalItems 8、totalPages 2；第 3 页 []")
    void ac10_mergedPagination() {
        scenarioL();
        JsonNode p1 = okAs(ALL, TRADES + "?page=1&size=5");
        assertThat(ids(p1)).containsExactly(8L, 7L, 6L, 5L, 4L);
        JsonNode p2 = okAs(ALL, TRADES + "?page=2&size=5");
        assertThat(ids(p2)).containsExactly(3L, 2L, 1L);
        assertThat(accounts(p2)).containsExactly(1L, 2L, 2L);
        for (JsonNode p : List.of(p1, p2)) {
            assertThat(p.get("size").asInt()).isEqualTo(5);
            assertThat(p.get("totalItems").asInt()).isEqualTo(8);
            assertThat(p.get("totalPages").asInt()).isEqualTo(2);
        }
        assertThat(p2.get("page").asInt()).isEqualTo(2);
        JsonNode p3 = okAs(ALL, TRADES + "?page=3&size=5");
        assertThat(p3.get("items").size()).isZero();
        assertThat(p3.get("totalItems").asInt()).isEqualTo(8);
        // page 2 rows are exactly the contract example rows (fields of the single account + account fields)
        JsonNode r3 = p2.get("items").get(0);
        assertThat(r3.get("accountName").asText()).isEqualTo(A_NAME);
        assertThat(r3.get("contractLabel").asText()).isEqualTo(C_48P);
        assertThat(r3.get("ledgerEntryId").asLong()).isEqualTo(5);
        assertThat(r3.get("tradedAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
    }

    @Test
    @DisplayName("AC10 只读：ALL 修改 #10 备注、删除 #13 → 400 ALL_ACCOUNTS_READ_ONLY，数据（含审计）不变；/{id} 与审计带 ALL 仍 NOT_SUPPORTED")
    void ac10_guards() {
        scenarioL();
        Map<String, Object> before = listsWorld();

        accountId = ALL;
        Resp put = editOpeningNote(L.get("#10"), "120.00", "2026-11-16T10:00", "改不了");
        Resp del = exchange(HttpMethod.DELETE, STOCK_TRADES + "/" + L.get("#13"), null);
        accountId = A;
        assertGuardError(put, READ_ONLY_CODE, TRADES + "/" + L.get("#10"));
        assertGuardError(del, READ_ONLY_CODE, STOCK_TRADES + "/" + L.get("#13"));
        assertThat(listsWorld()).as("nothing written by the rejected ALL writes").isEqualTo(before);

        for (String path : List.of(TRADES + "/" + L.get("#10"), STOCK_TRADES + "/" + L.get("#13"), "/api/audit-log",
                "/api/positions/greeks-summary", "/api/positions/underlying-summary")) {
            assertGuardError(getAs(ALL, path), NOT_SUPPORTED_CODE, path);
        }
        // control: the same reads work with the record's own account
        assertThat(okAs(B, TRADES + "/" + L.get("#10")).get("note").asText()).isEqualTo("同价位");
        assertThat(okAs(B, STOCK_TRADES + "/" + L.get("#13")).get("realizedPnl").asText()).isEqualTo("50.00");
    }

    @Test
    @DisplayName("AC10 新增空账户（丙账户）后 4 个列表 ALL 的条数、行、合计都不变")
    void ac10_emptyAccount() {
        scenarioL();
        Map<String, JsonNode> before = new java.util.LinkedHashMap<>();
        for (String p : LISTS) {
            before.put(p, okAs(ALL, p + "?size=100"));
            before.put(p + " p2", okAs(ALL, p + "?page=2&size=2"));
        }
        before.put("closed nov", okAs(ALL, CLOSED + "?startDate=2026-11-01&endDate=2026-11-30"));
        before.put("pnl", okAs(ALL, PNL));
        createAccount("丙账户");
        for (String p : LISTS) {
            assertThat(okAs(ALL, p + "?size=100")).as("%s unchanged by an empty account", p).isEqualTo(before.get(p));
            assertThat(okAs(ALL, p + "?page=2&size=2")).as("%s page 2 unchanged", p).isEqualTo(before.get(p + " p2"));
        }
        assertThat(okAs(ALL, CLOSED + "?startDate=2026-11-01&endDate=2026-11-30")).isEqualTo(before.get("closed nov"));
        assertThat(okAs(ALL, PNL)).isEqualTo(before.get("pnl"));
        assertPnlMatchesClosed(ALL, null, null);
    }
}
