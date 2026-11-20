package com.optionmgr.valuation.web;

import static org.assertj.core.api.Assertions.assertThat;
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
 * FEAT-017 acceptance criteria 1–10 (backend) through the HTTP API with the in-memory fake quote sources and a
 * movable clock. Scenario (requirement): 2026-11-23 (Monday) 10:00 fetch; account A (id 1): P1 STO 1 QRTV
 * 2026-12-18 50P 118.70, P2 BTO 2 QRTV 55C 301.30, P3 STO 1 IXQZ 65C 84.35, P4 STO 1 QRTV 47.5P 45.00 (never in the
 * chain), 100 IXQZ cost 5,880.00 at 61.10; account B: P5 STO 1 QRTV 50P 120.00. Fictional tickers.
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
class UnrealizedPnlControllerTest {

    static final String P1 = "QRTV 2026-12-18 50 P";
    static final String P2 = "QRTV 2026-12-18 55 C";
    static final String P3 = "IXQZ 2026-12-18 65 C";
    static final String P4 = "QRTV 2026-12-18 47.5 P";
    static final String P6 = "QRTV 2026-12-18 60 C";
    static final String EXPIRED = "QRTV 2026-11-20 45 P";

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
        MovableClock valuationTestClock() {
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
    final Map<String, Reply> cboe = new HashMap<>();
    final Map<String, String> qrtvExtra = new HashMap<>();

    @BeforeEach
    void setUp() {
        clean();
        at("2026-11-20T09:00:00");
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
        cboe.clear();
        qrtvExtra.clear();
    }

    // ---------------------------------------------------------------- scenario and fake data

    void scenario() throws Exception {
        deposit(1, "30000.00");
        Res r = postJson(1, "/api/stock-trades", stock("BUY", "IXQZ", 100, "5880.00"));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "50", "PUT", 1, "118.70", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "BTO", "QRTV", "2026-12-18", "55", "CALL", 2, "301.30", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "IXQZ", "2026-12-18", "65", "CALL", 1, "84.35", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-12-18", "47.5", "PUT", 1, "45.00", "2026-11-19T10:00");
        accountB = newAccount("账户B");
        deposit(accountB, "20000.00");
        trade(accountB, "/api/option-trades", "STO", "QRTV", "2026-12-18", "50", "PUT", 1, "120.00",
                "2026-11-19T10:00");
        qrtv();
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", "2026-11-23 09:45:00",
                option("IXQZ261218C00065000", "0.80", "0.90", null))));
        apply();
    }

    void qrtv() {
        StringBuilder opts = new StringBuilder(option("QRTV261218P00050000", "1.20", "1.30", null) + ","
                + option("QRTV261218C00055000", "1.80", "1.90", null));
        qrtvExtra.values().forEach(o -> opts.append(',').append(o));
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:45:00", opts.toString())));
    }

    void apply() {
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
    }

    static String option(String code, String bid, String ask, String theo) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask
                + (theo == null ? "" : ",\"theo\":" + theo)
                + ",\"iv\":0.3512,\"delta\":-0.3021,\"gamma\":0.0512,\"theta\":-0.0456,\"vega\":0.0711,\"rho\":-0.0123,"
                + "\"last_trade_price\":1.22,\"volume\":120,\"open_interest\":3400}";
    }

    static String chain(String symbol, String price, String timestamp, String options) {
        return "{\"timestamp\":\"" + timestamp + "\",\"data\":{\"symbol\":\"" + symbol + "\",\"current_price\":" + price
                + ",\"bid\":0,\"ask\":0,\"prev_day_close\":50.00,\"options\":[" + options + "]}}";
    }

    void at(String nyLocal) {
        clock.set(ny(nyLocal));
    }

    void run(String nyLocal) {
        at(nyLocal);
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick at %s: %s", nyLocal, t.skipReason()).isTrue();
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_ac2_ac3_rowsAndTotalsOfAccountA() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        assertOption(1, P1, "-125.00", "-6.30", "-5.31");
        assertOption(1, P2, "370.00", "68.70", "22.80");
        assertOption(1, P3, "-85.00", "-0.65", "-0.77");
        JsonNode p4 = position(1, P4);
        assertThat(p4.get("unrealizedStatus").asText()).isEqualTo("NO_QUOTE");
        assertThat(p4.get("unrealizedLabel").asText()).isEqualTo("无报价");
        assertThat(p4.get("marketValue").isNull()).isTrue();
        assertThat(p4.get("unrealizedPnl").isNull()).isTrue();
        assertThat(p4.get("unrealizedPnlPercent").isNull()).isTrue();

        JsonNode s = stockRow(1, "IXQZ");
        assertThat(s.get("marketValue").asText()).isEqualTo("6110.00");
        assertThat(s.get("unrealizedPnl").asText()).isEqualTo("230.00");
        assertThat(s.get("unrealizedPnlPercent").asText()).isEqualTo("3.91");
        assertThat(s.get("unrealizedStatus").asText()).isEqualTo("OK");
        assertThat(s.get("unrealizedLabel").isNull()).isTrue();

        JsonNode sum = summary(1);
        assertThat(sum.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertGroup(sum.get("option"), "160.00", "61.75", 3, 1, 0);
        assertGroup(sum.get("stock"), "6110.00", "230.00", 1, 0, 0);
        assertGroup(sum.get("total"), "6270.00", "291.75", 4, 1, 0);
        assertThat(sum.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
    }

    @Test
    void ac4_accountB() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        assertOption(accountB, P1, "-125.00", "-5.00", "-4.17");
        JsonNode sum = summary(accountB);
        assertGroup(sum.get("option"), "-125.00", "-5.00", 1, 0, 0);
        assertGroup(sum.get("stock"), "0.00", "0.00", 0, 0, 0);
        assertGroup(sum.get("total"), "-125.00", "-5.00", 1, 0, 0);
        assertThat(sum.get("excludedLabel").isNull()).isTrue();
    }

    @Test
    void ac5_manualMarks() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        at("2026-11-23T10:05:00");
        overrideMark("50", "PUT", "1.40");
        assertOption(1, P1, "-140.00", "-21.30", "-17.94");
        assertThat(summary(1).at("/option/unrealizedPnl").asText()).isEqualTo("46.75");
        assertOption(accountB, P1, "-140.00", "-20.00", "-16.67");
        overrideMark("47.5", "PUT", "0.30");
        assertOption(1, P4, "-30.00", "15.00", "33.33");
        assertThat(summary(1).get("excludedLabel").isNull()).isTrue();
    }

    @Test
    void ac6_thirdDecimalMarkThenTheo() throws Exception {
        scenario();
        trade(1, "/api/option-trades", "BTO", "QRTV", "2026-12-18", "60", "CALL", 1, "15.65", "2026-11-19T10:00");
        qrtvExtra.put("60C", option("QRTV261218C00060000", "0.10", "0.15", null));
        qrtv();
        apply();
        run("2026-11-23T10:00:00");
        assertOption(1, P6, "12.50", "-3.15", "-20.13");
        JsonNode sum = summary(1);
        assertGroup(sum.get("option"), "172.50", "58.60", 4, 1, 0);
        assertGroup(sum.get("total"), "6282.50", "288.60", 5, 1, 0);

        qrtvExtra.put("60C", option("QRTV261218C00060000", "0", "0.05", "0.03"));
        qrtv();
        apply();
        run("2026-11-23T10:15:00");
        assertOption(1, P6, "3.00", "-12.65", "-80.83");
        sum = summary(1);
        assertGroup(sum.get("option"), "163.00", "49.10", 4, 1, 0);
        assertGroup(sum.get("total"), "6273.00", "279.10", 5, 1, 0);
    }

    @Test
    void ac7_fetchFailureKeepsLastMarks() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        cboe.put("QRTV", Reply.of(500, "boom"));
        apply();
        run("2026-11-23T10:15:00");
        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/optionQuote/staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:00）");
        assertOption(1, P1, "-125.00", "-6.30", "-5.31");
        assertOption(1, P2, "370.00", "68.70", "22.80");
        assertGroup(summary(1).get("option"), "160.00", "61.75", 3, 1, 0);
    }

    @Test
    void ac8_expiredPendingThenExpired() throws Exception {
        // the regular scenario plus the 11-20 45P, quoted on 11-20 (last mark 0.05)
        scenario();
        trade(1, "/api/option-trades", "STO", "QRTV", "2026-11-20", "45", "PUT", 1, "60.00", "2026-11-19T10:00");
        qrtvExtra.put("45P", option("QRTV261120P00045000", "0.04", "0.06", null));
        qrtv();
        apply();
        run("2026-11-20T10:00:00");
        qrtvExtra.clear();
        qrtv();
        apply();
        run("2026-11-23T10:00:00");

        JsonNode e = position(1, EXPIRED);
        assertThat(e.at("/optionQuote/mark").asText()).isEqualTo("0.05");
        assertThat(e.get("unrealizedStatus").asText()).isEqualTo("EXPIRED_PENDING");
        assertThat(e.get("unrealizedLabel").asText()).isEqualTo("已到期，待处理");
        assertThat(e.get("marketValue").isNull()).isTrue();
        JsonNode sum = summary(1);
        assertGroup(sum.get("option"), "160.00", "61.75", 3, 1, 1);
        assertGroup(sum.get("total"), "6270.00", "291.75", 4, 1, 1);
        assertThat(sum.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");

        ObjectNode n = json.createObjectNode();
        n.put("symbol", "QRTV");
        n.put("expirationDate", "2026-11-20");
        n.put("strike", "45");
        n.put("optionType", "PUT");
        n.put("contracts", 1);
        Res r = postJson(1, "/api/option-trades/expirations", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        assertThat(summary(1).get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
    }

    @Test
    void ac9_closingP2() throws Exception {
        scenario();
        run("2026-11-23T10:00:00");
        at("2026-11-23T10:10:00");
        trade(1, "/api/option-trades/closes", "STC", "QRTV", "2026-12-18", "55", "CALL", 2, "368.70",
                "2026-11-23T10:10");
        JsonNode sum = summary(1);
        assertGroup(sum.get("option"), "-210.00", "-6.95", 2, 1, 0);
        assertGroup(sum.get("total"), "5900.00", "223.05", 3, 1, 0);
    }

    @Test
    void ac10_emptyAccount() throws Exception {
        long empty = newAccount("空账户");
        JsonNode sum = summary(empty);
        for (String g : new String[] {"option", "stock", "total"}) {
            assertGroup(sum.get(g), "0.00", "0.00", 0, 0, 0);
        }
        assertThat(sum.get("excludedLabel").isNull()).isTrue();
    }

    @Test
    void stockWithoutPrice_noQuote() throws Exception {
        deposit(1, "1000.00");
        Res r = postJson(1, "/api/stock-trades", stock("BUY", "IXQZ", 10, "500.00"));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        JsonNode s = stockRow(1, "IXQZ");
        assertThat(s.get("unrealizedStatus").asText()).isEqualTo("NO_QUOTE");
        assertThat(s.get("unrealizedLabel").asText()).isEqualTo("无报价");
        assertThat(s.get("marketValue").isNull()).isTrue();
        JsonNode sum = summary(1);
        assertGroup(sum.get("stock"), "0.00", "0.00", 0, 1, 0);
        assertThat(sum.get("excludedLabel").asText()).isEqualTo("1 个持仓无报价，未计入");
    }

    @Test
    void summaryRequiresAccount() throws Exception {
        Res r = call(get("/api/positions/unrealized-summary"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
        r = call(get("/api/positions/unrealized-summary").header(AccountRef.HEADER, "999"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        r = call(post("/api/positions/unrealized-summary").header(AccountRef.HEADER, "1"));
        assertThat(r.status()).isEqualTo(405);
    }

    // ---------------------------------------------------------------- helpers

    void assertOption(long account, String label, String mv, String pnl, String pct) throws Exception {
        JsonNode p = position(account, label);
        assertThat(p.get("unrealizedStatus").asText()).as(label).isEqualTo("OK");
        assertThat(p.get("unrealizedLabel").isNull()).isTrue();
        assertThat(p.get("marketValue").asText()).as(label).isEqualTo(mv);
        assertThat(p.get("unrealizedPnl").asText()).as(label).isEqualTo(pnl);
        assertThat(p.get("unrealizedPnlPercent").asText()).as(label).isEqualTo(pct);
    }

    static void assertGroup(JsonNode g, String mv, String pnl, int included, int noQuote, int expired) {
        assertThat(g.get("marketValue").asText()).isEqualTo(mv);
        assertThat(g.get("unrealizedPnl").asText()).isEqualTo(pnl);
        assertThat(g.get("includedCount").asInt()).isEqualTo(included);
        assertThat(g.get("noQuoteCount").asInt()).isEqualTo(noQuote);
        assertThat(g.get("expiredPendingCount").asInt()).isEqualTo(expired);
    }

    void overrideMark(String strike, String type, String mark) throws Exception {
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"" + strike
                        + "\",\"optionType\":\"" + type + "\",\"mark\":\"" + mark + "\"}"));
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
        return ok(get("/api/positions/unrealized-summary").header(AccountRef.HEADER, String.valueOf(account)));
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
        n.put("occurredAtLocal", "2026-11-19T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    ObjectNode stock(String type, String symbol, int shares, String amount) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", "2026-11-19T09:45");
        return n;
    }

    void trade(long account, String url, String action, String symbol, String exp, String strike, String type,
               int contracts, String net, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", time);
        Res r = postJson(account, url, n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    JsonNode position(long account, String label) throws Exception {
        for (JsonNode p : ok(get("/api/option-positions").header(AccountRef.HEADER, String.valueOf(account)))
                .get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    JsonNode stockRow(long account, String symbol) throws Exception {
        for (JsonNode p : ok(get("/api/stock-positions").header(AccountRef.HEADER, String.valueOf(account)))
                .get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        throw new AssertionError("no stock position " + symbol);
    }
}
