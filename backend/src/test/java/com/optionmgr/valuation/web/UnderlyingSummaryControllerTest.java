package com.optionmgr.valuation.web;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
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
 * FEAT-023 acceptance criteria 1, 3, 5–8 (backend) through {@code GET /api/positions/underlying-summary} with the
 * in-memory fake quote sources and a movable clock. Requirement scenario, fictional tickers QRTV / IXQZ / ZWLK / QFZ:
 * today 2026-11-23 (Monday), 10:00 fetch, multiplier 100.
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
class UnderlyingSummaryControllerTest {

    static final String URL = "/api/positions/underlying-summary";

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
        MovableClock underlyingSummaryTestClock() {
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

    long accountB;
    long h7;

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
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("DELETE FROM underlying_quote");
        jdbc.update("DELETE FROM option_quote");
        jdbc.update("DELETE FROM underlying_quote_close");
        jdbc.update("DELETE FROM option_quote_close");
        jdbc.update("UPDATE market_data_status SET last_attempt_at = NULL, last_success_at = NULL, last_refresh_at = NULL");
        fake.reset();
    }

    // ---------------------------------------------------------------- scenario

    void scenario() throws Exception {
        deposit(1, "50000.00");
        stock(1, "TRANSFER_IN", "QRTV", 100, "4800.00", "2026-10-01T09:45");
        stock(1, "TRANSFER_IN", "ZWLK", 50, "1500.00", "2026-10-01T09:45");
        // history
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-11-20", "50", "CALL", "60.35", "2026-10-20T10:00");
        trade(1, "/api/option-trades/closes", "BTC", "QRTV", "2026-11-20", "50", "CALL", "20.65", "2026-10-30T10:00");
        trade(1, "/api/option-trades", "STO", "IXQZ", "2026-11-13", "55", "PUT", "80.00", "2026-10-27T10:00");
        expire(1, "IXQZ", "2026-11-13", "55", "PUT");
        trade(1, "/api/option-trades", "STO", "QFZ", "2026-11-20", "20", "PUT", "50.00", "2026-10-28T10:00");
        trade(1, "/api/option-trades/closes", "BTC", "QFZ", "2026-11-20", "20", "PUT", "10.65", "2026-11-06T10:00");
        h7 = stock(1, "SELL", "ZWLK", 20, "700.00", "2026-11-12T10:00");
        // positions
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "55", "CALL", "85.35", "2026-11-02T10:00");
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "45", "PUT", "120.35", "2026-11-03T10:00");
        trade(1, "/api/option-trades", "BTO", "QRTV", "2026-12-18", "40", "PUT", "40.65", "2026-11-03T10:00");
        trade(1, "/api/option-trades", "STO", "IXQZ", "2026-11-20", "58", "PUT", "90.00", "2026-11-02T10:00");
        trade(1, "/api/option-trades", "STO", "IXQZ", "2026-12-18", "60", "PUT", "200.00", "2026-11-05T10:00");
        // account B
        accountB = newAccount("账户B");
        deposit(accountB, "10000.00");
        trade(accountB, "/api/option-trades", "STO", "QRTV", "2026-12-18", "45", "PUT", "115.00", "2026-11-05T11:00");

        Map<String, Reply> cboe = new HashMap<>();
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "50.00",
                option("QRTV261218C00055000", "0.55", "0.65") + ","
                        + option("QRTV261218P00045000", "1.05", "1.15") + ","
                        + option("QRTV261218P00040000", "0.25", "0.35"))));
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.00", option("IXQZ261218P00060000", "2.45", "2.55"))));
        // ZWLK: unreachable, no price
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
        clock.set(ny("2026-11-23T10:00:00"));
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick: %s", t.skipReason()).isTrue();
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

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_accountA() throws Exception {
        scenario();
        JsonNode s = summary(1);
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        JsonNode groups = s.get("groups");
        assertThat(groups).hasSize(3);
        assertGroup(groups.get(0), "IXQZ", "-250.00", "-50.00", "30.00", "290.00", 2, false, 1, 0, 1,
                "1 个持仓已到期待处理，未计入");
        assertRealized(groups.get(0).get("realized"), 1, "80.00", 1, "80.00", 0, "0.00");
        assertGroup(groups.get(1), "QRTV", "4860.00", "225.05", "264.75", "165.05", 3, true, 4, 0, 0, null);
        assertRealized(groups.get(1).get("realized"), 1, "39.70", 1, "39.70", 0, "0.00");
        assertGroup(groups.get(2), "ZWLK", null, null, "100.00", null, 0, true, 0, 1, 0, "1 个持仓无报价，未计入");
        assertRealized(groups.get(2).get("realized"), 1, "100.00", 0, "0.00", 1, "100.00");
        // amounts are JSON strings
        assertThat(groups.get(1).get("totalPnl").isTextual()).isTrue();
        assertThat(groups.get(1).at("/realized/realizedPnl").isTextual()).isTrue();
    }

    @Test
    void ac3_consistentWithUnrealizedSummaryAndPnlSummary() throws Exception {
        scenario();
        JsonNode s = summary(1);
        JsonNode total = ok(get("/api/positions/unrealized-summary").header(AccountRef.HEADER, "1")).get("total");
        BigDecimal mv = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        int included = 0;
        int noQuote = 0;
        int expired = 0;
        for (JsonNode g : s.get("groups")) {
            mv = g.get("marketValue").isNull() ? mv : mv.add(new BigDecimal(g.get("marketValue").asText()));
            pnl = g.get("unrealizedPnl").isNull() ? pnl : pnl.add(new BigDecimal(g.get("unrealizedPnl").asText()));
            included += g.get("includedCount").asInt();
            noQuote += g.get("noQuoteCount").asInt();
            expired += g.get("expiredPendingCount").asInt();
        }
        assertThat(mv.toPlainString()).isEqualTo(total.get("marketValue").asText()).isEqualTo("4610.00");
        assertThat(pnl.toPlainString()).isEqualTo(total.get("unrealizedPnl").asText()).isEqualTo("175.05");
        assertThat(included).isEqualTo(total.get("includedCount").asInt());
        assertThat(noQuote).isEqualTo(total.get("noQuoteCount").asInt()).isEqualTo(1);
        assertThat(expired).isEqualTo(total.get("expiredPendingCount").asInt()).isEqualTo(1);

        JsonNode bySymbol = ok(get("/api/pnl-summary").header(AccountRef.HEADER, "1")).get("bySymbol");
        Map<String, JsonNode> rows = new HashMap<>();
        bySymbol.forEach(r -> rows.put(r.get("symbol").asText(), r));
        assertThat(rows).containsKey("QFZ");
        assertThat(rows.get("QFZ").get("realizedPnl").asText()).isEqualTo("39.35");
        for (JsonNode g : s.get("groups")) {
            ObjectNode row = rows.get(g.get("symbol").asText()).deepCopy();
            row.remove("symbol");
            assertThat(g.get("realized")).isEqualTo(row);
        }

        // netPremium = sum of openingNetTotal of the option-position rows of the symbol
        Map<String, BigDecimal> premium = new HashMap<>();
        for (JsonNode p : ok(get("/api/option-positions").header(AccountRef.HEADER, "1")).get("positions")) {
            premium.merge(p.get("symbol").asText(), new BigDecimal(p.get("openingNetTotal").asText()),
                    BigDecimal::add);
        }
        for (JsonNode g : s.get("groups")) {
            BigDecimal expected = premium.get(g.get("symbol").asText());
            if (expected == null) {
                assertThat(g.get("netPremium").isNull()).isTrue();
            } else {
                assertThat(g.get("netPremium").asText()).isEqualTo(expected.toPlainString());
            }
        }
    }

    @Test
    void ac5_closingThe45Put() throws Exception {
        scenario();
        clock.set(ny("2026-11-23T10:30:00"));
        trade(1, "/api/option-trades/closes", "BTC", "QRTV", "2026-12-18", "45", "PUT", "60.65", "2026-11-23T10:30");
        JsonNode g = group(summary(1), "QRTV");
        assertGroup(g, "QRTV", "4970.00", "214.70", "314.10", "44.70", 2, true, 3, 0, 0, null);
        assertRealized(g.get("realized"), 2, "99.40", 2, "99.40", 0, "0.00");
    }

    @Test
    void ac6_expireThe58PutThenDeleteTheExpiry() throws Exception {
        scenario();
        long id = expire(1, "IXQZ", "2026-11-20", "58", "PUT");
        JsonNode g = group(summary(1), "IXQZ");
        assertGroup(g, "IXQZ", "-250.00", "-50.00", "120.00", "200.00", 1, false, 1, 0, 0, null);
        assertRealized(g.get("realized"), 2, "170.00", 2, "170.00", 0, "0.00");

        Res r = call(delete("/api/option-trades/" + id).header(AccountRef.HEADER, "1"));
        assertThat(r.status()).as(r.raw()).isEqualTo(204);
        g = group(summary(1), "IXQZ");
        assertGroup(g, "IXQZ", "-250.00", "-50.00", "30.00", "290.00", 2, false, 1, 0, 1,
                "1 个持仓已到期待处理，未计入");
        assertRealized(g.get("realized"), 1, "80.00", 1, "80.00", 0, "0.00");
    }

    @Test
    void ac7_zwlkManualPriceEditedSaleClearedPrice() throws Exception {
        scenario();
        clock.set(ny("2026-11-23T10:05:00"));
        ok(put("/api/market-data/overrides/underlyings/ZWLK").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"32.00\"}"));
        JsonNode g = group(summary(1), "ZWLK");
        assertGroup(g, "ZWLK", "960.00", "60.00", "160.00", null, 0, true, 1, 0, 0, null);

        ObjectNode edit = json.createObjectNode();
        edit.put("shares", 20);
        edit.put("amount", "650.00");
        edit.put("tradedAtLocal", "2026-11-12T10:00");
        edit.put("symbol", "ZWLK");
        edit.put("type", "SELL");
        Res r = call(put("/api/stock-trades/" + h7).header(AccountRef.HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        g = group(summary(1), "ZWLK");
        assertGroup(g, "ZWLK", "960.00", "60.00", "110.00", null, 0, true, 1, 0, 0, null);
        assertRealized(g.get("realized"), 1, "50.00", 0, "0.00", 1, "50.00");

        r = call(delete("/api/market-data/overrides/underlyings/ZWLK"));
        assertThat(r.status()).as(r.raw()).isEqualTo(204);
        g = group(summary(1), "ZWLK");
        assertGroup(g, "ZWLK", null, null, "50.00", null, 0, true, 0, 1, 0, "1 个持仓无报价，未计入");
    }

    @Test
    void ac8_accountB_andEmptyAccount() throws Exception {
        scenario();
        JsonNode s = summary(accountB);
        assertThat(s.get("groups")).hasSize(1);
        JsonNode g = s.get("groups").get(0);
        assertGroup(g, "QRTV", "-110.00", "5.00", "5.00", "115.00", 1, false, 1, 0, 0, null);
        assertRealized(g.get("realized"), 0, "0.00", 0, "0.00", 0, "0.00");

        long empty = newAccount("空账户");
        s = summary(empty);
        assertThat(s.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(s.get("groups").isArray()).isTrue();
        assertThat(s.get("groups")).isEmpty();
    }

    @Test
    void errors() throws Exception {
        Res r = call(get(URL));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
        r = call(get(URL).header(AccountRef.HEADER, "abc"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("BAD_REQUEST");
        r = call(get(URL).header(AccountRef.HEADER, "999"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        r = call(post(URL).header(AccountRef.HEADER, "1"));
        assertThat(r.status()).isEqualTo(405);
        assertThat(r.body().get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        // extra query parameters are ignored
        assertThat(ok(get(URL).param("foo", "bar").header(AccountRef.HEADER, "1")).get("groups")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    static void assertGroup(JsonNode g, String symbol, String mv, String pnl, String total, String premium,
                            int options, boolean stock, int included, int noQuote, int expired, String label) {
        assertThat(g.get("symbol").asText()).isEqualTo(symbol);
        assertNullable(g.get("marketValue"), mv, symbol + " marketValue");
        assertNullable(g.get("unrealizedPnl"), pnl, symbol + " unrealizedPnl");
        assertThat(g.get("totalPnl").asText()).as(symbol + " totalPnl").isEqualTo(total);
        assertNullable(g.get("netPremium"), premium, symbol + " netPremium");
        assertThat(g.get("optionPositionCount").asInt()).isEqualTo(options);
        assertThat(g.get("hasStockPosition").asBoolean()).isEqualTo(stock);
        assertThat(g.get("includedCount").asInt()).isEqualTo(included);
        assertThat(g.get("noQuoteCount").asInt()).isEqualTo(noQuote);
        assertThat(g.get("expiredPendingCount").asInt()).isEqualTo(expired);
        assertNullable(g.get("excludedLabel"), label, symbol + " excludedLabel");
    }

    static void assertNullable(JsonNode n, String expected, String what) {
        assertThat(n).as(what).isNotNull();
        if (expected == null) {
            assertThat(n.isNull()).as(what + " = " + n).isTrue();
        } else {
            assertThat(n.asText()).as(what).isEqualTo(expected);
        }
    }

    static void assertRealized(JsonNode r, int count, String pnl, int optCount, String opt, int stkCount, String stk) {
        assertThat(r.get("closedCount").asInt()).isEqualTo(count);
        assertThat(r.get("realizedPnl").asText()).isEqualTo(pnl);
        assertThat(r.at("/option/closedCount").asInt()).isEqualTo(optCount);
        assertThat(r.at("/option/realizedPnl").asText()).isEqualTo(opt);
        assertThat(r.at("/stock/closedCount").asInt()).isEqualTo(stkCount);
        assertThat(r.at("/stock/realizedPnl").asText()).isEqualTo(stk);
    }

    JsonNode group(JsonNode summary, String symbol) {
        for (JsonNode g : summary.get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return g;
            }
        }
        throw new AssertionError("no group " + symbol + " in " + summary);
    }

    record Res(int status, JsonNode body, String raw) {
    }

    Res call(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Res(r.getResponse().getStatus(), raw.isEmpty() ? null : json.readTree(raw), raw);
    }

    JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        Res r = call(req);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r.body();
    }

    JsonNode summary(long account) throws Exception {
        return ok(get(URL).header(AccountRef.HEADER, String.valueOf(account)));
    }

    Res postJson(long account, String url, ObjectNode body) throws Exception {
        return call(post(url).header(AccountRef.HEADER, String.valueOf(account)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    long newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Res r = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(n)));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void deposit(long account, String amount) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", "2026-09-30T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    long stock(long account, String type, String symbol, int shares, String amount, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        if (type.equals("TRANSFER_IN")) {
            n.put("tradeDate", time.substring(0, 10)); // a transfer-in has a date only
        } else {
            n.put("tradedAtLocal", time);
        }
        Res r = postJson(account, "/api/stock-trades", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void trade(long account, String url, String action, String symbol, String exp, String strike, String type,
               String net, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", time);
        Res r = postJson(account, url, n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    long expire(long account, String symbol, String exp, String strike, String type) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", 1);
        Res r = postJson(account, "/api/option-trades/expirations", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }
}
