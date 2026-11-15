package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.time.MarketTime;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-046 acceptance 1-10 (backend) with scenario L of the requirement: today 2026-11-23 (Monday) 10:00 New York;
 * 甲账户 (id 1) and 乙账户 (id 2); fictional tickers QRTV / IXQZ. The trade list, stock trades, closed trades and the
 * cash ledger accept {@code X-Account-Id: ALL}; every row carries its account.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllAccountsListsControllerTest {

    static final String TRADES = "/api/option-trades";
    static final String STOCKS = "/api/stock-trades";
    static final String CLOSED = "/api/closed-trades";
    static final String LEDGER = "/api/cash/ledger";
    static final String PNL = "/api/pnl-summary";

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static final class MovableClock extends Clock {
        private volatile Instant now = ny("2026-11-23T10:00:00");

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MovableClock allAccountsListsTestClock() {
            return new MovableClock();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MovableClock clock;

    /** Scenario number (#n) by option trade id / stock record id / ledger entry id. */
    final Map<Long, String> optionNo = new HashMap<>();
    final Map<Long, String> stockNo = new HashMap<>();
    final Map<String, Long> idOf = new HashMap<>();

    @BeforeEach
    void setUp() {
        clean();
        clock.set(ny("2026-11-23T10:00:00"));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM expiration_adjustment");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("UPDATE account SET name = '默认账户', name_key = '默认账户', broker_note = NULL WHERE id = 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
    }

    // ---------------------------------------------------------------- scenario L

    void scenarioL() throws Exception {
        rename(1, "甲账户");
        assertThat(newAccount("乙账户")).isEqualTo(2);
        deposit(1, "50000.00", "2026-10-01T09:30");                                              // #1
        deposit(2, "20000.00", "2026-10-01T09:30");                                              // #2
        stock("#3", 1, "TRANSFER_IN", "IXQZ", 100, "5880.00", null, "2026-10-01");               // #3
        trade("#4", 2, "BTO", "QRTV", "2026-12-18", "55", "CALL", "50.00", "2026-10-15T10:00");  // #4
        trade("#5", 2, "STC", "QRTV", "2026-12-18", "55", "CALL", "80.00", "2026-10-20T10:00");  // #5
        trade("#6", 1, "STO", "QRTV", "2026-12-18", "48", "PUT", "100.00", "2026-11-02T10:00");  // #6
        trade("#7", 2, "STO", "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-02T10:00");   // #7
        trade("#8", 1, "BTC", "QRTV", "2026-12-18", "48", "PUT", "40.00", "2026-11-10T10:00");   // #8
        trade("#9", 1, "STO", "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-16T10:00");  // #9
        trade("#10", 2, "STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-16T10:00"); // #10
        trade("#11", 1, "STO", "IXQZ", "2026-11-27", "65", "CALL", "84.35", "2026-11-16T10:30"); // #11
        stock("#12", 2, "BUY", "QRTV", 100, "4800.00", "2026-11-17T10:00", null);                // #12
        stock("#13", 2, "SELL", "QRTV", 50, "2450.00", "2026-11-18T10:00", null);                // #13
        // 乙账户: #10's note becomes "同价位" (#10 has an audit record, no other record has)
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "120.00");
        edit.put("tradedAtLocal", "2026-11-16T10:00");
        edit.put("note", "同价位");
        call(put(TRADES + "/" + idOf.get("#10")), "2", edit, 200);
    }

    // ---------------------------------------------------------------- AC 1: trade list

    @Test
    void ac1_tradeListOfAllAccounts() throws Exception {
        scenarioL();
        JsonNode p = ok(TRADES, "ALL");
        assertThat(p.get("totalItems").asLong()).isEqualTo(8);
        assertThat(p.get("totalPages").asInt()).isEqualTo(1);
        assertThat(tradeRows(p)).containsExactly(
                "#11 1 甲账户 STO 84.35 0.00",
                "#10 2 乙账户 STO 120.00 5000.00",
                "#9 1 甲账户 STO 118.70 5000.00",
                "#8 1 甲账户 BTC -40.00 -4800.00",
                "#7 2 乙账户 STO 60.00 4500.00",
                "#6 1 甲账户 STO 100.00 4800.00",
                "#5 2 乙账户 STC 80.00 0.00",
                "#4 2 乙账户 BTO -50.00 0.00");
        // each row = the same row in its own account (Q53), plus the account fields
        for (JsonNode item : p.get("items")) {
            String account = item.get("accountId").asText();
            JsonNode own = byId(ok(TRADES + "?size=100", account)).get(item.get("id").asLong());
            assertThat(item).isEqualTo(own);
            assertThat(item).isEqualTo(ok(TRADES + "/" + item.get("id").asLong(), account));
        }
        assertThat(item(p, "#5").get("realizedPnl").asText()).isEqualTo("30.00");
        assertThat(item(p, "#5").at("/coveredLots/0/tradeId").asLong()).isEqualTo(idOf.get("#4"));
        // account fields are the first two fields
        List<String> names = new ArrayList<>();
        p.get("items").get(0).fieldNames().forEachRemaining(names::add);
        assertThat(names.subList(0, 3)).containsExactly("accountId", "accountName", "id");
    }

    // ---------------------------------------------------------------- AC 2: filters; same filter, other account

    @Test
    void ac2_filtersApplyToAllAccounts() throws Exception {
        scenarioL();
        JsonNode qrtv = ok(TRADES + "?symbol=qrtv", "ALL");
        assertThat(qrtv.get("totalItems").asLong()).isEqualTo(7);
        assertThat(nos(qrtv)).doesNotContain("#11");
        JsonNode contract = ok(TRADES + "?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT", "ALL");
        assertThat(nos(contract)).containsExactly("#10", "#9");
        // cleared filters, then action = STO only
        JsonNode sto = ok(TRADES + "?action=STO", "ALL");
        assertThat(nos(sto)).containsExactly("#11", "#10", "#9", "#7", "#6");
        assertThat(sto.get("totalItems").asLong()).isEqualTo(5);
        JsonNode b = ok(TRADES + "?action=STO", "2");
        assertThat(nos(b)).containsExactly("#10", "#7");
        b.get("items").forEach(i -> assertThat(i.get("accountName").asText()).isEqualTo("乙账户"));
        // invalid parameters: the same error as for one account
        for (String bad : List.of("?strike=abc", "?action=XYZ", "?page=0", "?size=101", "?startDate=2026-13-01")) {
            JsonNode all = call(get(TRADES + bad), "ALL", null, 400);
            assertThat(all).as(bad).isEqualTo(call(get(TRADES + bad), "1", null, 400));
        }
    }

    // ---------------------------------------------------------------- AC 3: stock trades

    @Test
    void ac3_stockTradesOfAllAccounts() throws Exception {
        scenarioL();
        JsonNode p = ok(STOCKS, "ALL");
        assertThat(p.get("totalItems").asLong()).isEqualTo(3);
        List<String> rows = new ArrayList<>();
        p.get("items").forEach(i -> rows.add(stockNo.get(i.get("id").asLong()) + " " + i.get("accountName").asText()
                + " " + i.get("type").asText() + " " + i.get("symbol").asText() + " " + i.get("cashChange").asText()
                + " " + i.get("amount").asText()));
        assertThat(rows).containsExactly(
                "#13 乙账户 SELL QRTV 2450.00 2450.00",
                "#12 乙账户 BUY QRTV -4800.00 4800.00",
                "#3 甲账户 TRANSFER_IN IXQZ 0.00 5880.00");
        JsonNode sell = p.get("items").get(0);
        assertThat(sell.get("accountId").asLong()).isEqualTo(2);
        assertThat(sell.at("/soldLots/0/tradeId").asLong()).isEqualTo(idOf.get("#12"));
        assertThat(sell.at("/soldLots/0/shares").asInt()).isEqualTo(50);
        assertThat(sell.at("/soldLots/0/cost").asText()).isEqualTo("2400.00");
        assertThat(sell.get("realizedPnl").asText()).isEqualTo("50.00");
        for (JsonNode item : p.get("items")) {
            String account = item.get("accountId").asText();
            assertThat(item).isEqualTo(ok(STOCKS + "/" + item.get("id").asLong(), account));
        }
        assertThat(stockNos(ok(STOCKS + "?symbol=IXQZ", "ALL"))).containsExactly("#3");
        assertThat(stockNos(ok(STOCKS + "?type=SELL", "ALL"))).containsExactly("#13");
        assertThat(call(get(STOCKS + "?type=FOO"), "ALL", null, 400))
                .isEqualTo(call(get(STOCKS + "?type=FOO"), "1", null, 400));
    }

    // ---------------------------------------------------------------- AC 4: closed trades

    @Test
    void ac4_closedTradesOfAllAccounts() throws Exception {
        scenarioL();
        JsonNode p = ok(CLOSED, "ALL");
        assertThat(p.get("filteredCount").asLong()).isEqualTo(3);
        assertThat(p.get("totalItems").asLong()).isEqualTo(3);
        assertThat(p.get("filteredRealizedPnlTotal").asText()).isEqualTo("140.00");
        assertThat(closedRows(p)).containsExactly("STOCK #13 乙账户 50.00", "OPTION #8 甲账户 60.00",
                "OPTION #5 乙账户 30.00");
        assertThat(totals(ok(CLOSED + "?type=STOCK", "ALL"))).isEqualTo("1 50.00");
        assertThat(totals(ok(CLOSED + "?type=OPTION", "ALL"))).isEqualTo("2 90.00");
        JsonNode nov = ok(CLOSED + "?startDate=2026-11-01&endDate=2026-11-30", "ALL");
        assertThat(totals(nov)).isEqualTo("2 110.00");
        assertThat(closedRows(nov)).containsExactly("STOCK #13 乙账户 50.00", "OPTION #8 甲账户 60.00");
        // each row = the same row in its own account
        for (JsonNode item : p.get("items")) {
            JsonNode own = ok(CLOSED, item.get("accountId").asText());
            boolean found = false;
            for (JsonNode o : own.get("items")) {
                if (o.get("type").equals(item.get("type")) && o.get("id").equals(item.get("id"))) {
                    assertThat(o).isEqualTo(item);
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
        assertThat(call(get(CLOSED + "?type=BOND"), "ALL", null, 400))
                .isEqualTo(call(get(CLOSED + "?type=BOND"), "1", null, 400));
    }

    // ---------------------------------------------------------------- AC 5 / 6: P&L summary and deep links

    @Test
    void ac5_pnlSummaryEqualsClosedTradesTotals_allAndSingle() throws Exception {
        scenarioL();
        for (String scope : List.of("ALL", "1", "2")) {
            for (String range : List.of("", "startDate=2026-11-01&endDate=2026-11-30",
                    "startDate=2026-10-01&endDate=2026-10-31", "startDate=2026-11-01&endDate=2026-11-23")) {
                String q = range.isEmpty() ? "" : "?" + range;
                String and = range.isEmpty() ? "?" : "?" + range + "&";
                JsonNode s = ok(PNL + q, scope);
                String ctx = scope + " " + range;
                assertThat(totals(ok(CLOSED + q, scope))).as(ctx)
                        .isEqualTo(s.get("closedCount").asText() + " " + s.get("realizedPnlTotal").asText());
                assertThat(totals(ok(CLOSED + and + "type=OPTION", scope))).as(ctx)
                        .isEqualTo(s.at("/option/closedCount").asText() + " " + s.at("/option/realizedPnl").asText());
                assertThat(totals(ok(CLOSED + and + "type=STOCK", scope))).as(ctx)
                        .isEqualTo(s.at("/stock/closedCount").asText() + " " + s.at("/stock/realizedPnl").asText());
                for (JsonNode row : s.get("bySymbol")) {
                    assertThat(totals(ok(CLOSED + and + "symbol=" + row.get("symbol").asText(), scope))).as(ctx)
                            .isEqualTo(row.get("closedCount").asText() + " " + row.get("realizedPnl").asText());
                }
            }
        }
        JsonNode all = ok(PNL, "ALL");
        assertThat(all.get("closedCount").asInt()).isEqualTo(3);
        assertThat(all.get("realizedPnlTotal").asText()).isEqualTo("140.00");
        JsonNode oct = ok(CLOSED + "?startDate=2026-10-01&endDate=2026-10-31", "ALL");
        assertThat(closedRows(oct)).containsExactly("OPTION #5 乙账户 30.00");
        assertThat(totals(ok(CLOSED + "?symbol=QRTV", "ALL"))).isEqualTo("3 140.00");
    }

    @Test
    void ac6_deepLinksResolveUnderAll() throws Exception {
        scenarioL();
        JsonNode d = ok("/api/dashboard", "ALL");
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(d.at("/currentMonthRealizedPnl/total").asText()).isEqualTo("110.00");
        JsonNode month = ok(CLOSED + "?startDate=2026-11-01&endDate=2026-11-23", "ALL");
        assertThat(totals(month)).isEqualTo(d.at("/currentMonthRealizedPnl/closedCount").asText() + " 110.00");
        assertThat(closedRows(month)).containsExactly("STOCK #13 乙账户 50.00", "OPTION #8 甲账户 60.00");
        assertThat(nos(ok(TRADES + "?symbol=QRTV&expirationDate=2026-12-18&strike=48&optionType=PUT", "ALL")))
                .containsExactly("#8", "#6");
        assertThat(stockNos(ok(STOCKS + "?symbol=IXQZ", "ALL"))).containsExactly("#3");
    }

    // ---------------------------------------------------------------- AC 7: cash ledger

    @Test
    void ac7_ledgerOfAllAccounts_ownAccountBalance() throws Exception {
        scenarioL();
        JsonNode p = ok(LEDGER, "ALL");
        assertThat(p.get("totalItems").asLong()).isEqualTo(12);
        assertThat(p.get("totalPages").asInt()).isEqualTo(1);
        List<String> rows = new ArrayList<>();
        p.get("items").forEach(i -> rows.add(i.get("accountName").asText() + " " + i.get("sourceType").asText() + " "
                + i.get("amount").asText() + " " + i.get("balanceAfter").asText()));
        assertThat(rows).containsExactly(
                "乙账户 STOCK_SELL 2450.00 17860.00",
                "乙账户 STOCK_BUY -4800.00 15410.00",
                "甲账户 OPTION_OPEN 84.35 50263.05",
                "乙账户 OPTION_OPEN 120.00 20210.00",
                "甲账户 OPTION_OPEN 118.70 50178.70",
                "甲账户 OPTION_CLOSE -40.00 50060.00",
                "乙账户 OPTION_OPEN 60.00 20090.00",
                "甲账户 OPTION_OPEN 100.00 50100.00",
                "乙账户 OPTION_CLOSE 80.00 20030.00",
                "乙账户 OPTION_OPEN -50.00 19950.00",
                "乙账户 DEPOSIT 20000.00 20000.00",
                "甲账户 DEPOSIT 50000.00 50000.00");
        assertThat(p.at("/items/3/remark").asText()).isEqualTo("同价位");
        assertThat(p.at("/items/3/hasAuditLog").asBoolean()).isFalse();
        // every row = the same row in its own account (Q228)
        for (JsonNode item : p.get("items")) {
            JsonNode own = ok(LEDGER + "?size=100", item.get("accountId").asText());
            boolean found = false;
            for (JsonNode o : own.get("items")) {
                if (o.get("id").equals(item.get("id"))) {
                    assertThat(o).isEqualTo(item);
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
        // 50,263.05 + 17,860.00 = 68,123.05; available 45,263.05 + 8,360.00 = 53,623.05 (the requirement's AC7 /
        // scenario L total "70,123.05 / 55,623.05" is an addition error, reported to PM; the account rows match it)
        JsonNode cash = ok("/api/cash/summary", "ALL");
        assertThat(cash.get("totalCash").asText() + " " + cash.get("frozenCash").asText() + " "
                + cash.get("availableCash").asText()).isEqualTo("68123.05 14500.00 53623.05");
        assertThat(cash.at("/accounts/0/totalCash").asText() + " " + cash.at("/accounts/0/availableCash").asText())
                .isEqualTo("50263.05 45263.05");
        assertThat(cash.at("/accounts/1/totalCash").asText() + " " + cash.at("/accounts/1/frozenCash").asText() + " "
                + cash.at("/accounts/1/availableCash").asText()).isEqualTo("17860.00 9500.00 8360.00");
        assertThat(call(get(LEDGER + "?size=0"), "ALL", null, 400))
                .isEqualTo(call(get(LEDGER + "?size=0"), "1", null, 400));
    }

    @Test
    void ac7_editedDepositIsFlaggedInItsOwnAccountUnderAll() throws Exception {
        scenarioL();
        long transferId = jdbc.queryForObject(
                "SELECT id FROM cash_transfer WHERE account_id = 2", Long.class);
        ObjectNode edit = json.createObjectNode();
        edit.put("type", "DEPOSIT");
        edit.put("amount", "20000.00");
        edit.put("occurredAtLocal", "2026-10-01T09:30");
        edit.put("remark", "初始资金");
        call(put("/api/cash/transfers/" + transferId), "2", edit, 200);
        JsonNode items = ok(LEDGER, "ALL").get("items");
        assertThat(items.get(10).get("accountName").asText()).isEqualTo("乙账户");
        assertThat(items.get(10).get("hasAuditLog").asBoolean()).isTrue();
        assertThat(items.get(11).get("accountName").asText()).isEqualTo("甲账户");
        assertThat(items.get(11).get("hasAuditLog").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- AC 8: history entry

    @Test
    void ac8_onlyTheEditedTradeHasAuditLog_auditStillPerAccount() throws Exception {
        scenarioL();
        List<String> flagged = new ArrayList<>();
        for (JsonNode i : ok(TRADES, "ALL").get("items")) {
            if (i.get("hasAuditLog").asBoolean()) {
                flagged.add(optionNo.get(i.get("id").asLong()) + " " + i.get("accountId").asText());
            }
        }
        assertThat(flagged).containsExactly("#10 2");
        JsonNode audit = ok("/api/audit-log?objectType=OPTION_TRADE&objectId=" + idOf.get("#10"), "2");
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        assertThat(call(get("/api/audit-log"), "ALL", null, 400).get("code").asText())
                .isEqualTo("ALL_ACCOUNTS_NOT_SUPPORTED");
    }

    // ---------------------------------------------------------------- AC 9: single account

    @Test
    void ac9_singleAccountUnchangedWithAccountFields() throws Exception {
        scenarioL();
        JsonNode t = ok(TRADES, "1");
        assertThat(nos(t)).containsExactly("#11", "#9", "#8", "#6");
        t.get("items").forEach(i -> assertThat(i.get("accountId").asLong() + " " + i.get("accountName").asText())
                .isEqualTo("1 甲账户"));
        assertThat(totals(ok(CLOSED, "1"))).isEqualTo("1 60.00");
        JsonNode l = ok(LEDGER, "1");
        assertThat(l.get("totalItems").asLong()).isEqualTo(5);
        assertThat(l.at("/items/0/balanceAfter").asText()).isEqualTo("50263.05");
        assertThat(l.at("/items/0/accountName").asText()).isEqualTo("甲账户");
        assertThat(ok(STOCKS, "1").at("/items/0/accountName").asText()).isEqualTo("甲账户");
    }

    @Test
    void writeResponsesCarryTheAccount() throws Exception {
        scenarioL();
        ObjectNode open = json.createObjectNode();
        open.put("symbol", "QRTV");
        open.put("expirationDate", "2026-12-18");
        open.put("strike", "40");
        open.put("optionType", "PUT");
        open.put("action", "STO");
        open.put("contracts", 1);
        open.put("netAmount", "30.00");
        open.put("tradedAtLocal", "2026-11-23T09:45");
        JsonNode created = call(post(TRADES), "2", open, 201);
        assertThat(created.get("accountId").asLong()).isEqualTo(2);
        assertThat(created.get("accountName").asText()).isEqualTo("乙账户");
        assertThat(created).isEqualTo(ok(TRADES + "/" + created.get("id").asLong(), "2"));
        ObjectNode buy = json.createObjectNode();
        buy.put("type", "BUY");
        buy.put("symbol", "IXQZ");
        buy.put("shares", 10);
        buy.put("amount", "600.00");
        buy.put("tradedAtLocal", "2026-11-23T09:50");
        JsonNode stock = call(post(STOCKS), "1", buy, 201);
        assertThat(stock.get("accountId").asLong() + " " + stock.get("accountName").asText()).isEqualTo("1 甲账户");
        // renamed account: rows show the current name
        rename(2, "乙账户2");
        assertThat(ok(TRADES + "/" + created.get("id").asLong(), "2").get("accountName").asText())
                .isEqualTo("乙账户2");
        for (JsonNode l : ok(LEDGER + "?size=100", "ALL").get("items")) {
            assertThat(l.get("accountName").asText()).isEqualTo(l.get("accountId").asLong() == 2 ? "乙账户2" : "甲账户");
        }
    }

    // ---------------------------------------------------------------- AC 10: paging, guards, empty account

    @Test
    void ac10_pagingOfTheMergedList() throws Exception {
        scenarioL();
        JsonNode p2 = ok(TRADES + "?page=2&size=5", "ALL");
        assertThat(nos(p2)).containsExactly("#6", "#5", "#4");
        assertThat(p2.get("totalItems").asLong()).isEqualTo(8);
        assertThat(p2.get("totalPages").asInt()).isEqualTo(2);
        assertThat(nos(ok(TRADES + "?page=1&size=5", "ALL"))).containsExactly("#11", "#10", "#9", "#8", "#7");
        JsonNode beyond = ok(TRADES + "?page=3&size=5", "ALL");
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("totalItems").asLong()).isEqualTo(8);
    }

    @Test
    void ac10_pagesStitchToTheUnpagedList_allFourLists() throws Exception {
        scenarioL();
        for (String url : List.of(TRADES, STOCKS, CLOSED, LEDGER)) {
            JsonNode whole = ok(url + "?size=100", "ALL");
            List<JsonNode> expected = new ArrayList<>();
            whole.get("items").forEach(expected::add);
            for (int size = 1; size <= 3; size++) {
                List<JsonNode> stitched = new ArrayList<>();
                JsonNode first = ok(url + "?page=1&size=" + size, "ALL");
                int pages = first.get("totalPages").asInt();
                assertThat(pages).as(url).isEqualTo((expected.size() + size - 1) / size);
                for (int page = 1; page <= pages; page++) {
                    JsonNode p = ok(url + "?page=" + page + "&size=" + size, "ALL");
                    assertThat(p.get("totalItems").asLong()).as(url).isEqualTo(expected.size());
                    if (url.equals(CLOSED)) {
                        assertThat(totals(p)).isEqualTo("3 140.00");
                    }
                    p.get("items").forEach(stitched::add);
                }
                assertThat(stitched).as("%s size %d", url, size).isEqualTo(expected);
            }
        }
    }

    @Test
    void ac10_writesWithAllAreRejectedAndChangeNothing() throws Exception {
        scenarioL();
        Map<String, String> before = snapshot();
        long auditBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class);
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "120.00");
        edit.put("tradedAtLocal", "2026-11-16T10:00");
        edit.put("note", "改");
        JsonNode r = call(put(TRADES + "/" + idOf.get("#10")), "ALL", edit, 400);
        assertThat(r.get("code").asText()).isEqualTo("ALL_ACCOUNTS_READ_ONLY");
        r = call(delete(STOCKS + "/" + idOf.get("#13")), "ALL", null, 400);
        assertThat(r.get("code").asText()).isEqualTo("ALL_ACCOUNTS_READ_ONLY");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isEqualTo(auditBefore);
    }

    @Test
    void ac10_byIdAndAuditStillRejectAll() throws Exception {
        scenarioL();
        // FEAT-047: greeks-summary / underlying-summary accept ALL now
        for (String url : List.of(TRADES + "/" + idOf.get("#10"), STOCKS + "/" + idOf.get("#13"), "/api/audit-log")) {
            JsonNode r = call(get(url), "ALL", null, 400);
            assertThat(r.get("code").asText()).as(url).isEqualTo("ALL_ACCOUNTS_NOT_SUPPORTED");
        }
    }

    @Test
    void ac10_emptyAccountChangesNothing() throws Exception {
        scenarioL();
        Map<String, String> before = snapshot();
        assertThat(newAccount("丙账户")).isEqualTo(3);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ok(TRADES, "3").get("totalItems").asLong()).isZero();
    }

    @Test
    void onlyOneAccount_allEqualsThatAccount() throws Exception {
        rename(1, "甲账户");
        deposit(1, "1000.00", "2026-10-01T09:30");
        trade("#a", 1, "STO", "QRTV", "2026-12-18", "5", "PUT", "10.00", "2026-11-02T10:00");
        for (String url : List.of(TRADES, STOCKS, CLOSED, LEDGER)) {
            assertThat(ok(url, "ALL")).as(url).isEqualTo(ok(url, "1"));
        }
    }

    // ---------------------------------------------------------------- helpers

    Map<String, String> snapshot() throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String url : List.of(TRADES + "?size=100", STOCKS + "?size=100", CLOSED + "?size=100",
                LEDGER + "?size=100", "/api/cash/summary", PNL)) {
            JsonNode n = ok(url, "ALL");
            if (n.has("accounts")) {
                ((ObjectNode) n).remove("accounts"); // the cash summary lists every account, also an empty one
            }
            m.put(url, n.toString());
        }

        return m;
    }

    List<String> tradeRows(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(optionNo.get(i.get("id").asLong()) + " " + i.get("accountId").asText()
                + " " + i.get("accountName").asText() + " " + i.get("action").asText() + " "
                + i.get("cashChange").asText() + " " + i.get("frozenChange").asText()));
        return out;
    }

    List<String> nos(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(optionNo.get(i.get("id").asLong())));
        return out;
    }

    List<String> stockNos(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(stockNo.get(i.get("id").asLong())));
        return out;
    }

    List<String> closedRows(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> {
            long id = i.get("id").asLong();
            String no = "STOCK".equals(i.get("type").asText()) ? stockNo.get(id) : optionNo.get(id);
            out.add(i.get("type").asText() + " " + no + " " + i.get("accountName").asText() + " "
                    + i.get("realizedPnl").asText());
        });
        return out;
    }

    static String totals(JsonNode closed) {
        return closed.get("filteredCount").asText() + " " + closed.get("filteredRealizedPnlTotal").asText();
    }

    JsonNode item(JsonNode page, String no) {
        for (JsonNode i : page.get("items")) {
            if (no.equals(optionNo.get(i.get("id").asLong()))) {
                return i;
            }
        }
        throw new AssertionError(no + " not on the page");
    }

    static Map<Long, JsonNode> byId(JsonNode page) {
        Map<Long, JsonNode> out = new HashMap<>();
        page.get("items").forEach(i -> out.put(i.get("id").asLong(), i));
        return out;
    }

    JsonNode call(MockHttpServletRequestBuilder req, String account, ObjectNode body, int expected) throws Exception {
        if (account != null) {
            req.header(AccountRef.HEADER, account);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expected);
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    JsonNode ok(String url, String account) throws Exception {
        return call(get(url), account, null, 200);
    }

    long newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        return call(post("/api/accounts"), null, n, 201).get("id").asLong();
    }

    void rename(long id, String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        call(put("/api/accounts/" + id), null, n, 200);
    }

    void deposit(long account, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        call(post("/api/cash/transfers"), String.valueOf(account), n, 201);
    }

    void trade(String no, long account, String action, String symbol, String exp, String strike, String type,
               String net, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? TRADES + "/closes" : TRADES;
        JsonNode r = call(post(url), String.valueOf(account), n, 201);
        assertThat(r.get("accountId").asLong()).isEqualTo(account);
        long id = r.get("id").asLong();
        optionNo.put(id, no);
        idOf.put(no, id);
    }

    void stock(String no, long account, String type, String symbol, int shares, String amount, String nyTime,
               String tradeDate) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        if (nyTime != null) {
            n.put("tradedAtLocal", nyTime);
        }
        if (tradeDate != null) {
            n.put("tradeDate", tradeDate);
        }
        long id = call(post(STOCKS), String.valueOf(account), n, 201).get("id").asLong();
        stockNo.put(id, no);
        idOf.put(no, id);
    }
}
