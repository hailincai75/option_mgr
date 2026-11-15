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
import com.optionmgr.quote.service.InMemoryQuoteTransport;
import com.optionmgr.quote.service.InMemoryQuoteTransport.Reply;
import com.optionmgr.quote.service.QuoteFetchService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
 * FEAT-035 acceptance 1-10 (backend) with scenario S of the requirement: today 2026-11-23 (Monday) 10:00 New York,
 * quotes fetched at 10:00 from the in-memory fake sources. Account 甲账户 (id 1) and 乙账户 (id 2); fictional tickers.
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
class AllAccountsControllerTest {

    static final String A1 = "QRTV 2026-12-18 50 P";
    static final String A4 = "IXQZ 2026-11-27 65 C";
    static final String B4 = "QRTV 2026-11-20 45 P";

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static final class MovableClock extends Clock {
        private volatile Instant now = ny("2026-11-23T09:00:00");

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
        MovableClock allAccountsTestClock() {
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
    @Autowired
    InMemoryQuoteTransport fake;
    @Autowired
    QuoteFetchService fetches;

    long b4Id;

    @BeforeEach
    void setUp() {
        clean();
        clock.set(ny("2026-11-23T09:00:00"));
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
        jdbc.update("DELETE FROM adhoc_closure");
        jdbc.update("DELETE FROM underlying_quote");
        jdbc.update("DELETE FROM option_quote");
        jdbc.update("DELETE FROM underlying_quote_close");
        jdbc.update("DELETE FROM option_quote_close");
        jdbc.update("UPDATE market_data_status SET last_attempt_at = NULL, last_success_at = NULL, last_refresh_at = NULL");
        fake.reset();
    }

    // ---------------------------------------------------------------- scenario S

    void scenarioS() throws Exception {
        rename(1, "甲账户");
        assertThat(newAccount("乙账户")).isEqualTo(2);
        // 甲账户
        deposit(1, "50000.00", "2026-10-01T09:30");
        ObjectNode transfer = json.createObjectNode();
        transfer.put("type", "TRANSFER_IN");
        transfer.put("symbol", "IXQZ");
        transfer.put("shares", 100);
        transfer.put("amount", "5880.00");
        transfer.put("tradeDate", "2026-10-01");
        expect(201, post("/api/stock-trades"), "1", transfer);
        trade(1, "STO", "QRTV", "2026-12-18", "50", "PUT", "118.70", "2026-11-02T10:00");
        trade(1, "STO", "QRTV", "2026-12-18", "48", "PUT", "100.00", "2026-11-03T10:00");
        trade(1, "BTC", "QRTV", "2026-12-18", "48", "PUT", "40.00", "2026-11-10T10:00");
        trade(1, "STO", "IXQZ", "2026-11-27", "65", "CALL", "84.35", "2026-11-16T10:00");
        // 乙账户
        deposit(2, "20000.00", "2026-10-01T09:30");
        trade(2, "BTO", "QRTV", "2026-12-18", "55", "CALL", "50.00", "2026-10-15T10:00");
        trade(2, "STC", "QRTV", "2026-12-18", "55", "CALL", "80.00", "2026-10-20T10:00");
        trade(2, "STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-02T10:00");
        b4Id = trade(2, "STO", "QRTV", "2026-11-20", "45", "PUT", "60.00", "2026-11-02T10:00");
        // quotes fetched at 10:00 (QRTV 50P mark 1.25, IXQZ 65C mark 0.85, IXQZ 61.10)
        Map<String, Reply> cboe = new HashMap<>();
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", option("QRTV261218P00050000", "1.20", "1.30"))));
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", option("IXQZ261127C00065000", "0.80", "0.90"))));
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
        clock.set(ny("2026-11-23T10:00:00"));
        assertThat(fetches.tick().ran()).isTrue();
    }

    static String option(String code, String bid, String ask) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask
                + ",\"iv\":0.3512,\"delta\":-0.3021,\"gamma\":0.0512,\"theta\":-0.0456,\"vega\":0.0711,\"rho\":-0.0123,"
                + "\"last_trade_price\":1.22,\"volume\":120,\"open_interest\":3400}";
    }

    static String chain(String symbol, String price, String options) {
        return "{\"timestamp\":\"2026-11-23 09:45:00\",\"data\":{\"symbol\":\"" + symbol + "\",\"current_price\":"
                + price + ",\"bid\":0,\"ask\":0,\"prev_day_close\":50.00,\"options\":[" + options + "]}}";
    }

    // ---------------------------------------------------------------- AC 1: single account unchanged + account fields

    @Test
    void ac1_singleAccountHasOnlyItsRowsWithAccountFields() throws Exception {
        scenarioS();
        JsonNode r = ok("/api/option-positions", "1");
        assertThat(labels(r.get("positions"))).containsExactly(A4, A1);
        for (JsonNode p : r.get("positions")) {
            assertThat(p.get("accountId").asLong()).isEqualTo(1);
            assertThat(p.get("accountName").asText()).isEqualTo("甲账户");
        }
        assertThat(r.get("positionCount").asInt()).isEqualTo(2);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("5000.00");
        JsonNode s = ok("/api/stock-positions", "1").get("positions").get(0);
        assertThat(s.get("accountId").asLong()).isEqualTo(1);
        assertThat(s.get("accountName").asText()).isEqualTo("甲账户");
        assertThat(ok("/api/stock-positions", "2").get("positionCount").asInt()).isZero();
    }

    // ---------------------------------------------------------------- AC 2: dashboard

    @Test
    void ac2_dashboardIsTheSumOfAllAccounts() throws Exception {
        scenarioS();
        JsonNode d = ok("/api/dashboard", "ALL");
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(text(d.get("cash"), "totalCash", "frozenCash", "availableCash"))
                .containsExactly("70473.05", "14500.00", "55973.05");
        assertThat(d.at("/realizedPnl/closedCount").asInt()).isEqualTo(2);
        assertThat(d.at("/realizedPnl/total").asText()).isEqualTo("90.00");
        assertThat(d.at("/currentMonthRealizedPnl/closedCount").asInt()).isEqualTo(1);
        assertThat(d.at("/currentMonthRealizedPnl/total").asText()).isEqualTo("60.00");
        assertThat(d.at("/positions/positionCount").asInt()).isEqualTo(4);
        assertThat(d.at("/positions/frozenTotal").asText()).isEqualTo("14500.00");
        assertThat(d.at("/positions/expiringWithin7DaysCount").asInt()).isEqualTo(1);
        assertThat(d.at("/positions/expiredUnprocessedCount").asInt()).isEqualTo(1);
        assertThat(d.at("/stockPositions/positionCount").asInt()).isEqualTo(1);
        // single account unchanged
        JsonNode b = ok("/api/dashboard", "2");
        assertThat(text(b.get("cash"), "totalCash", "frozenCash", "availableCash"))
                .containsExactly("20210.00", "9500.00", "10710.00");
        assertThat(b.at("/positions/positionCount").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- AC 3: cash summary

    @Test
    void ac3_cashSummaryHasOneRowPerAccountAndTheTotal() throws Exception {
        scenarioS();
        JsonNode c = ok("/api/cash/summary", "ALL");
        assertThat(text(c, "totalCash", "frozenCash", "availableCash"))
                .containsExactly("70473.05", "14500.00", "55973.05");
        assertThat(c.get("accounts")).hasSize(2);
        assertThat(accountCash(c.get("accounts").get(0))).isEqualTo("1 甲账户 50263.05 5000.00 45263.05");
        assertThat(accountCash(c.get("accounts").get(1))).isEqualTo("2 乙账户 20210.00 9500.00 10710.00");
        JsonNode single = ok("/api/cash/summary", "2");
        assertThat(text(single, "totalCash", "frozenCash", "availableCash"))
                .containsExactly("20210.00", "9500.00", "10710.00");
        assertThat(single.get("accounts")).hasSize(1);
        assertThat(accountCash(single.get("accounts").get(0))).isEqualTo("2 乙账户 20210.00 9500.00 10710.00");
    }

    @Test
    void newAccountIsIncludedAtOnceWithZeroCash_andRenameIsReflected() throws Exception {
        scenarioS();
        assertThat(newAccount("丙账户")).isEqualTo(3);
        rename(2, "乙账户改名");
        JsonNode c = ok("/api/cash/summary", "ALL");
        assertThat(c.get("accounts")).hasSize(3);
        assertThat(accountCash(c.get("accounts").get(1))).isEqualTo("2 乙账户改名 20210.00 9500.00 10710.00");
        assertThat(accountCash(c.get("accounts").get(2))).isEqualTo("3 丙账户 0.00 0.00 0.00");
        assertThat(c.get("totalCash").asText()).isEqualTo("70473.05");
        assertThat(ok("/api/option-positions", "ALL").get("positions").get(0).get("accountName").asText())
                .isEqualTo("乙账户改名");
    }

    // ---------------------------------------------------------------- AC 4 / 6: option rows

    @Test
    void ac4_optionRowsOfAllAccountsInDisplayOrderThenAccountId() throws Exception {
        scenarioS();
        JsonNode r = ok("/api/option-positions", "ALL");
        assertThat(r.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(r.get("positionCount").asInt()).isEqualTo(4);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("14500.00");
        List<String> rows = new ArrayList<>();
        for (JsonNode p : r.get("positions")) {
            rows.add(p.get("accountId").asText() + " " + p.get("accountName").asText() + " "
                    + p.get("contractLabel").asText() + " " + p.get("openingNetTotal").asText() + " "
                    + p.get("frozenCash").asText() + " " + p.get("dte").asText() + " "
                    + p.get("expirationStatus").asText() + " " + p.get("daysPastExpiration").asText());
        }
        assertThat(rows).containsExactly(
                "2 乙账户 QRTV 2026-11-20 45 P 60.00 4500.00 -3 EXPIRED 3",
                "1 甲账户 IXQZ 2026-11-27 65 C 84.35 0.00 4 ACTIVE 0",
                "1 甲账户 QRTV 2026-12-18 50 P 118.70 5000.00 25 ACTIVE 0",
                "2 乙账户 QRTV 2026-12-18 50 P 120.00 5000.00 25 ACTIVE 0");
        JsonNode a4 = r.get("positions").get(1);
        assertThat(a4.get("coveredShares").asLong()).isEqualTo(100);
        assertThat(a4.get("underlyingShares").asLong()).isEqualTo(100);
        // each row equals the row of its own account (Q53)
        assertThat(withoutAccount(r.get("positions").get(0)))
                .isEqualTo(withoutAccount(ok("/api/option-positions", "2").get("positions").get(0)));
        assertThat(withoutAccount(r.get("positions").get(2)))
                .isEqualTo(withoutAccount(ok("/api/option-positions", "1").get("positions").get(1)));
        assertThat(r.get("positions").get(3).at("/lots/0/tradeId").asLong()).isGreaterThan(0);
    }

    // ---------------------------------------------------------------- AC 5: stocks and unrealized totals

    @Test
    void ac5_stockRowsAndUnrealizedTotals() throws Exception {
        scenarioS();
        JsonNode s = ok("/api/stock-positions", "ALL");
        assertThat(s.get("positionCount").asInt()).isEqualTo(1);
        JsonNode ixqz = s.get("positions").get(0);
        assertThat(ixqz.get("accountId").asLong()).isEqualTo(1);
        assertThat(ixqz.get("accountName").asText()).isEqualTo("甲账户");
        assertThat(text(ixqz, "symbol", "shares", "coveredShares", "availableShares", "totalCost", "marketValue",
                "unrealizedPnl")).containsExactly("IXQZ", "100", "100", "0", "5880.00", "6110.00", "230.00");

        Map<String, JsonNode> rows = byLabel(ok("/api/option-positions", "ALL").get("positions"));
        assertThat(rows.get("1 " + A1).get("unrealizedPnl").asText()).isEqualTo("-6.30");
        assertThat(rows.get("2 " + A1).get("unrealizedPnl").asText()).isEqualTo("-5.00");
        assertThat(rows.get("2 " + A1).get("unrealizedPnlPercent").asText()).isEqualTo("-4.17");
        assertThat(rows.get("1 " + A4).get("unrealizedPnl").asText()).isEqualTo("-0.65");
        assertThat(rows.get("2 " + B4).get("unrealizedStatus").asText()).isEqualTo("EXPIRED_PENDING");
        assertThat(rows.get("1 " + A1).at("/optionQuote/mark").asText())
                .isEqualTo(rows.get("2 " + A1).at("/optionQuote/mark").asText());

        JsonNode u = ok("/api/positions/unrealized-summary", "ALL");
        assertThat(u.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(group(u.get("option"))).isEqualTo("-335.00 -11.95 3 0 1");
        assertThat(group(u.get("stock"))).isEqualTo("6110.00 230.00 1 0 0");
        assertThat(group(u.get("total"))).isEqualTo("5775.00 218.05 4 0 1");
        assertThat(u.get("excludedLabel").asText()).isEqualTo("1 个持仓已到期待处理，未计入");
        // single account unchanged
        assertThat(group(ok("/api/positions/unrealized-summary", "2").get("total"))).isEqualTo("-125.00 -5.00 1 0 1");
    }

    @Test
    void stockSymbolCountIsDistinctSymbolsAcrossAccounts_rowsStaySplit() throws Exception {
        scenarioS();
        // 甲 holds IXQZ (scenario S) and QRTV; 乙 holds QRTV -> 3 rows, 2 symbols
        transferIn(1, "QRTV", 100, "5000.00", "2026-11-02");
        transferIn(2, "QRTV", 50, "2600.00", "2026-11-02");

        JsonNode s = ok("/api/stock-positions", "ALL");
        List<String> rows = new ArrayList<>();
        s.get("positions").forEach(p -> rows.add(p.get("accountId").asText() + " " + p.get("symbol").asText()));
        assertThat(rows).containsExactly("1 IXQZ", "1 QRTV", "2 QRTV");
        assertThat(s.get("positionCount").asInt()).isEqualTo(2);
        assertThat(ok("/api/dashboard", "ALL").at("/stockPositions/positionCount").asInt()).isEqualTo(2);
        // other totals unchanged: option rows and unrealized included rows are still counted per row
        assertThat(ok("/api/dashboard", "ALL").at("/positions/positionCount").asInt()).isEqualTo(4);
        assertThat(ok("/api/positions/unrealized-summary", "ALL").at("/stock/includedCount").asInt()).isEqualTo(3);
        // single account unchanged (one row per symbol)
        assertThat(ok("/api/stock-positions", "1").get("positionCount").asInt()).isEqualTo(2);
        assertThat(ok("/api/dashboard", "1").at("/stockPositions/positionCount").asInt()).isEqualTo(2);
        assertThat(ok("/api/stock-positions", "2").get("positionCount").asInt()).isEqualTo(1);
        assertThat(ok("/api/dashboard", "2").at("/stockPositions/positionCount").asInt()).isEqualTo(1);
    }

    void transferIn(long account, String symbol, int shares, String amount, String date) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", date);
        expect(201, post("/api/stock-trades"), String.valueOf(account), n);
    }

    // ---------------------------------------------------------------- AC 7: P&L summary

    @Test
    void ac7_pnlSummaryMergesSymbolsAndMonthsAcrossAccounts() throws Exception {
        scenarioS();
        JsonNode all = ok("/api/pnl-summary", "ALL");
        assertThat(all.get("startDate").isNull()).isTrue();
        assertThat(all.get("closedCount").asInt()).isEqualTo(2);
        assertThat(all.get("realizedPnlTotal").asText()).isEqualTo("90.00");
        assertThat(all.at("/option/realizedPnl").asText()).isEqualTo("90.00");
        assertThat(all.at("/stock/realizedPnl").asText()).isEqualTo("0.00");
        assertThat(all.get("bySymbol")).hasSize(1);
        assertThat(row(all.get("bySymbol").get(0), "symbol")).isEqualTo("QRTV 2 90.00 90.00 0.00");
        assertThat(all.get("byMonth")).hasSize(2);
        assertThat(row(all.get("byMonth").get(0), "month")).isEqualTo("2026-11 1 60.00 60.00 0.00");
        assertThat(row(all.get("byMonth").get(1), "month")).isEqualTo("2026-10 1 30.00 30.00 0.00");

        JsonNode nov = ok("/api/pnl-summary?startDate=2026-11-01&endDate=2026-11-30", "ALL");
        assertThat(nov.get("closedCount").asInt()).isEqualTo(1);
        assertThat(nov.get("realizedPnlTotal").asText()).isEqualTo("60.00");
        assertThat(nov.get("bySymbol")).hasSize(1);
        assertThat(row(nov.get("bySymbol").get(0), "symbol")).isEqualTo("QRTV 1 60.00 60.00 0.00");
        assertThat(nov.get("byMonth")).hasSize(1);

        JsonNode b = ok("/api/pnl-summary", "2");
        assertThat(row(b.get("bySymbol").get(0), "symbol")).isEqualTo("QRTV 1 30.00 30.00 0.00");

        // same validation as before
        JsonNode bad = call(get("/api/pnl-summary?startDate=x"), "ALL", null, 400);
        assertThat(bad.at("/details/0/field").asText()).isEqualTo("startDate");
    }

    // ---------------------------------------------------------------- AC 8: ad-hoc closure alerts

    @Test
    void ac8_alertsCountAllAccountsWithTheSplitByAccount() throws Exception {
        scenarioS();
        jdbc.update("INSERT INTO adhoc_closure (closure_date, event_name, detected_on, status, revoked_on) "
                + "VALUES (DATE '2026-12-18', 'Test Closure', DATE '2026-11-23', 'ACTIVE', NULL)");
        JsonNode alerts = ok("/api/adhoc-closure-alerts", "ALL").get("alerts");
        assertThat(alerts).hasSize(1);
        JsonNode a = alerts.get(0);
        assertThat(a.get("date").asText()).isEqualTo("2026-12-18");
        assertThat(a.get("eventName").asText()).isEqualTo("Test Closure");
        assertThat(a.get("affectedPositionCount").asInt()).isEqualTo(2);
        assertThat(byAccount(a)).containsExactly("1 甲账户 1", "2 乙账户 1");
        assertThat(a.get("message").asText()).isEqualTo("2026-12-18 临时休市（Test Closure），你有 2 个持仓在该日到期"
                + "（甲账户 1 个、乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日");
        assertThat(fieldNames(a)).containsExactly("date", "eventName", "affectedPositionCount", "byAccount", "message");

        JsonNode b = ok("/api/adhoc-closure-alerts", "2").get("alerts").get(0);
        assertThat(b.get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(byAccount(b)).containsExactly("2 乙账户 1");
        assertThat(b.get("message").asText()).isEqualTo("2026-12-18 临时休市（Test Closure），你有 1 个持仓在该日到期，"
                + "期权通常改为前一个交易日到期，请核对并调整到期日");
        Map<String, JsonNode> rows = byLabel(ok("/api/option-positions", "2").get("positions"));
        assertThat(rows.get("2 " + A1).get("expiresOnAdhocClosure").asBoolean()).isTrue();
    }

    @Test
    void alertWithOneAffectedAccountStillListsItInAllMode() throws Exception {
        scenarioS();
        jdbc.update("INSERT INTO adhoc_closure (closure_date, event_name, detected_on, status, revoked_on) "
                + "VALUES (DATE '2026-11-27', 'Test Closure', DATE '2026-11-23', 'ACTIVE', NULL)");
        JsonNode a = ok("/api/adhoc-closure-alerts", "ALL").get("alerts").get(0);
        assertThat(byAccount(a)).containsExactly("1 甲账户 1");
        assertThat(a.get("message").asText()).contains("你有 1 个持仓在该日到期（甲账户 1 个），");
    }

    // ---------------------------------------------------------------- AC 9: writes rejected

    @Test
    void ac9_everyWriteWithAllIsRejectedAndChangesNothing() throws Exception {
        scenarioS();
        long transferId = jdbc.queryForObject("SELECT MIN(id) FROM cash_transfer", Long.class);
        long stockId = jdbc.queryForObject("SELECT MIN(id) FROM stock_trade", Long.class);
        String cashBefore = ok("/api/cash/summary", "ALL").toString();
        String positionsBefore = ok("/api/option-positions", "ALL").toString();
        long auditBefore = count("audit_record");

        ObjectNode deposit = json.createObjectNode();
        deposit.put("type", "DEPOSIT");
        deposit.put("amount", "100.00");
        deposit.put("occurredAtLocal", "2026-11-23T09:30");
        deposit.putNull("remark");
        JsonNode e = call(post("/api/cash/transfers"), "ALL", deposit, 400);
        assertThat(e.get("code").asText()).isEqualTo("ALL_ACCOUNTS_READ_ONLY");
        assertThat(e.get("message").asText()).isEqualTo("全部账户模式只读：录入、修改、删除请先选择一个账户");
        assertThat(e.get("status").asInt()).isEqualTo(400);
        assertThat(e.get("path").asText()).isEqualTo("/api/cash/transfers");
        assertThat(e.at("/details/0/field").asText()).isEqualTo("X-Account-Id");
        assertThat(e.at("/details/0/message").asText()).isEqualTo("请选择一个账户");
        // without the header: FEAT-033 ACCOUNT_REQUIRED
        assertThat(call(post("/api/cash/transfers"), null, deposit, 400).get("code").asText())
                .isEqualTo("ACCOUNT_REQUIRED");

        ObjectNode empty = json.createObjectNode(); // the account check comes before body validation
        List<MockHttpServletRequestBuilder> writes = List.of(
                put("/api/cash/transfers/" + transferId), delete("/api/cash/transfers/" + transferId),
                post("/api/option-trades"), post("/api/option-trades/closes"),
                post("/api/option-trades/expirations"), post("/api/option-trades/expirations/batch"),
                post("/api/option-trades/assignments"), post("/api/option-trades/exercises"),
                post("/api/option-trades/expiration-adjustments"),
                put("/api/option-trades/" + b4Id), delete("/api/option-trades/" + b4Id),
                post("/api/stock-trades"), put("/api/stock-trades/" + stockId), delete("/api/stock-trades/" + stockId));
        for (MockHttpServletRequestBuilder w : writes) {
            JsonNode r = call(w, " all ", empty, 400);
            assertThat(r.get("code").asText()).as(r.get("path").asText()).isEqualTo("ALL_ACCOUNTS_READ_ONLY");
        }
        assertThat(ok("/api/cash/summary", "ALL").toString()).isEqualTo(cashBefore);
        assertThat(ok("/api/option-positions", "ALL").toString()).isEqualTo(positionsBefore);
        assertThat(count("audit_record")).isEqualTo(auditBefore);
    }

    // ---------------------------------------------------------------- AC 10: reads not supported (FEAT-046: the 4 lists now are)

    @Test
    void ac10_unsupportedReadsAreRejected() throws Exception {
        scenarioS();
        long tradeId = b4Id;
        long stockId = jdbc.queryForObject("SELECT MIN(id) FROM stock_trade", Long.class);
        for (String url : new String[] {"/api/option-trades/" + tradeId,
                "/api/stock-trades/" + stockId,
                "/api/positions/greeks-summary", "/api/positions/underlying-summary", "/api/audit-log"}) {
            JsonNode r = call(get(url), "ALL", null, 400);
            assertThat(r.get("code").asText()).as(url).isEqualTo("ALL_ACCOUNTS_NOT_SUPPORTED");
            assertThat(r.get("message").asText()).isEqualTo("此接口暂不支持全部账户，请选择一个账户");
            assertThat(r.at("/details/0/field").asText()).isEqualTo("X-Account-Id");
            assertThat(r.at("/details/0/message").asText()).isEqualTo("请选择一个账户");
        }
        // per-row Greeks are on the ALL rows
        JsonNode a1 = byLabel(ok("/api/option-positions", "ALL").get("positions")).get("1 " + A1);
        assertThat(a1.at("/greeks/status").asText()).isEqualTo("OK");
    }

    // ---------------------------------------------------------------- header parsing, endpoints without account

    @Test
    void allIsCaseInsensitiveAndStripped_otherValuesKeepFeat033Errors() throws Exception {
        scenarioS();
        for (String v : new String[] {"ALL", "all", " ALL ", "All"}) {
            assertThat(ok("/api/option-positions", v).get("positionCount").asInt()).as(v).isEqualTo(4);
        }
        for (String url : new String[] {"/api/dashboard", "/api/cash/summary", "/api/option-positions",
                "/api/stock-positions", "/api/positions/unrealized-summary", "/api/pnl-summary",
                "/api/adhoc-closure-alerts"}) {
            assertThat(call(get(url), null, null, 400).get("code").asText()).as(url).isEqualTo("ACCOUNT_REQUIRED");
            assertThat(call(get(url), "  ", null, 400).get("code").asText()).as(url).isEqualTo("ACCOUNT_REQUIRED");
            JsonNode bad = call(get(url), "ALLX", null, 400);
            assertThat(bad.get("code").asText()).as(url).isEqualTo("BAD_REQUEST");
            assertThat(bad.at("/details/0/message").asText()).isEqualTo("账户 id 必须是整数");
            assertThat(call(get(url), "99", null, 404).get("code").asText()).as(url).isEqualTo("ACCOUNT_NOT_FOUND");
            call(get(url), "ALL", null, 200);
        }
    }

    @Test
    void marketDataAndAccountEndpointsIgnoreAll() throws Exception {
        scenarioS();
        call(get("/api/accounts"), "ALL", null, 200);
        call(get("/api/health"), "ALL", null, 200);
        call(get("/api/market-data/status"), "ALL", null, 200);
        call(post("/api/market-data/refresh"), "ALL", null, 200);
        ObjectNode mark = json.createObjectNode();
        mark.put("symbol", "QRTV");
        mark.put("expirationDate", "2026-12-18");
        mark.put("strike", "50");
        mark.put("optionType", "PUT");
        mark.put("mark", "1.30");
        call(put("/api/market-data/overrides/options"), "ALL", mark, 200);
        Map<String, JsonNode> rows = byLabel(ok("/api/option-positions", "ALL").get("positions"));
        assertThat(rows.get("1 " + A1).at("/optionQuote/mark").asText()).isEqualTo("1.30");
        assertThat(rows.get("2 " + A1).at("/optionQuote/mark").asText()).isEqualTo("1.30");
    }

    // ---------------------------------------------------------------- helpers

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

    JsonNode expect(int status, MockHttpServletRequestBuilder req, String account, ObjectNode body) throws Exception {
        return call(req, account, body, status);
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

    long trade(long account, String action, String symbol, String exp, String strike, String type, String net,
               String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        return call(post(url), String.valueOf(account), n, 201).get("id").asLong();
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    static List<String> text(JsonNode n, String... fields) {
        List<String> out = new ArrayList<>();
        for (String f : fields) {
            out.add(n.get(f).asText());
        }
        return out;
    }

    static List<String> labels(JsonNode positions) {
        List<String> out = new ArrayList<>();
        positions.forEach(p -> out.add(p.get("contractLabel").asText()));
        return out;
    }

    /** "accountId contractLabel" to row. */
    static Map<String, JsonNode> byLabel(JsonNode positions) {
        Map<String, JsonNode> out = new HashMap<>();
        positions.forEach(p -> out.put(p.get("accountId").asText() + " " + p.get("contractLabel").asText(), p));
        return out;
    }

    static String accountCash(JsonNode a) {
        return a.get("accountId").asText() + " " + a.get("accountName").asText() + " " + a.get("totalCash").asText()
                + " " + a.get("frozenCash").asText() + " " + a.get("availableCash").asText();
    }

    static String group(JsonNode g) {
        return g.get("marketValue").asText() + " " + g.get("unrealizedPnl").asText() + " "
                + g.get("includedCount").asText() + " " + g.get("noQuoteCount").asText() + " "
                + g.get("expiredPendingCount").asText();
    }

    static String row(JsonNode r, String key) {
        return r.get(key).asText() + " " + r.get("closedCount").asText() + " " + r.get("realizedPnl").asText() + " "
                + r.at("/option/realizedPnl").asText() + " " + r.at("/stock/realizedPnl").asText();
    }

    static List<String> byAccount(JsonNode alert) {
        List<String> out = new ArrayList<>();
        alert.get("byAccount").forEach(c -> out.add(c.get("accountId").asText() + " "
                + c.get("accountName").asText() + " " + c.get("affectedPositionCount").asText()));
        return out;
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    ObjectNode withoutAccount(JsonNode row) {
        ObjectNode copy = row.deepCopy();
        copy.remove("accountId");
        copy.remove("accountName");
        return copy;
    }
}
