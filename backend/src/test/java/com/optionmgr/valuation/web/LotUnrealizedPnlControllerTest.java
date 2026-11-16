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
 * FEAT-048 acceptance criteria 1–9 (backend) through the HTTP API with the in-memory fake quote sources and a movable
 * clock. Scenario (requirement): 2026-11-16 (Monday) 10:00 fetch, fictional ZZQ. Account 1: C = short 9C (A STO 1
 * 18.34, B STO 1 37.34), P = long 8P (L1 BTO 1 25.65, L2 BTO 2 41.30), stock S1 100 @ 950.00 and S2 100 @ 780.65.
 * Account B: S3 101 @ 880.00, S4 99 @ 860.00. S1 is a plain buy here (the lot type does not change the math).
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
class LotUnrealizedPnlControllerTest {

    static final String C = "ZZQ 2026-12-18 9 C";
    static final String P = "ZZQ 2026-12-18 8 P";
    static final String P7 = "ZZQ 2026-12-18 7 P";
    static final String EXPIRED = "ZZQ 2026-11-13 8 P";

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static final class MovableClock extends Clock {
        private volatile Instant now = ny("2026-11-16T09:00:00");

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
        MovableClock lotValuationTestClock() {
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

    @BeforeEach
    void setUp() {
        clean();
        at("2026-11-16T09:00:00");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
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
    }

    // ---------------------------------------------------------------- scenario and fake data

    void scenario() throws Exception {
        deposit(1, "20000.00");
        stockTrade(1, "BUY", 100, "950.00", "2026-10-01T10:00");
        stockTrade(1, "BUY", 100, "780.65", "2026-11-03T10:00");
        trade(1, "/api/option-trades", "STO", "2026-12-18", "9", "CALL", 1, "18.34", "2026-11-10T10:00");
        trade(1, "/api/option-trades", "STO", "2026-12-18", "9", "CALL", 1, "37.34", "2026-11-11T10:00");
        trade(1, "/api/option-trades", "BTO", "2026-12-18", "8", "PUT", 1, "25.65", "2026-11-12T10:00");
        trade(1, "/api/option-trades", "BTO", "2026-12-18", "8", "PUT", 2, "41.30", "2026-11-13T10:00");
        accountB = newAccount("乙账户");
        deposit(accountB, "20000.00");
        stockTrade(accountB, "BUY", 101, "880.00", "2026-11-04T10:00");
        stockTrade(accountB, "BUY", 99, "860.00", "2026-11-05T10:00");
        zzq("8.65");
    }

    void zzq(String price) {
        cboe.put("ZZQ", Reply.of(200, chain(price, option("ZZQ261218C00009000", "0.10", "0.30") + ","
                + option("ZZQ261218P00008000", "0.15", "0.19"))));
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
    }

    static String option(String code, String bid, String ask) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask
                + ",\"iv\":0.3512,\"delta\":-0.3021,\"gamma\":0.0512,\"theta\":-0.0456,\"vega\":0.0711,\"rho\":-0.0123,"
                + "\"last_trade_price\":0.20,\"volume\":120,\"open_interest\":3400}";
    }

    static String chain(String price, String options) {
        return "{\"timestamp\":\"2026-11-16 09:45:00\",\"data\":{\"symbol\":\"ZZQ\",\"current_price\":" + price
                + ",\"bid\":0,\"ask\":0,\"prev_day_close\":8.60,\"options\":[" + options + "]}}";
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
    void ac1_ac2_ac3_lotsOfAccountA() throws Exception {
        scenario();
        run("2026-11-16T10:00:00");

        JsonNode c = position("1", 1, C);
        assertRow(c, "-40.00", "15.68", "28.16");
        assertThat(c.get("lots")).hasSize(2);
        assertLot(c.get("lots").get(0), "-20.00", "-1.66", "-9.05");
        assertLot(c.get("lots").get(1), "-20.00", "17.34", "46.44");

        JsonNode p = position("1", 1, P);
        assertRow(p, "51.00", "-15.95", "-23.82");
        assertLot(p.get("lots").get(0), "17.00", "-8.65", "-33.72");
        assertLot(p.get("lots").get(1), "34.00", "-7.30", "-17.68");

        JsonNode s = stockRow("1", 1);
        assertRow(s, "1730.00", "-0.65", "-0.04");
        assertLot(s.get("lots").get(0), "865.00", "-85.00", "-8.95");
        assertLot(s.get("lots").get(1), "865.00", "84.35", "10.81");
    }

    @Test
    void ac4_fifoCloseLeavesLotB() throws Exception {
        scenario();
        run("2026-11-16T10:00:00");
        at("2026-11-16T10:10:00");
        trade(1, "/api/option-trades/closes", "BTC", "2026-12-18", "9", "CALL", 1, "20.65", "2026-11-16T10:10");
        JsonNode c = position("1", 1, C);
        assertThat(c.get("contracts").asInt()).isEqualTo(1);
        assertRow(c, "-20.00", "17.34", "46.44");
        assertThat(c.get("lots")).hasSize(1);
        assertThat(c.at("/lots/0/remainingNetAmount").asText()).isEqualTo("37.34");
        assertLot(c.get("lots").get(0), "-20.00", "17.34", "46.44");
    }

    @Test
    void ac5_manualMarkThenCleared() throws Exception {
        scenario();
        run("2026-11-16T10:00:00");
        at("2026-11-16T10:05:00");
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"ZZQ\",\"expirationDate\":\"2026-12-18\",\"strike\":\"9\",\"optionType\":\"CALL\","
                        + "\"mark\":\"0.35\"}"));
        JsonNode c = position("1", 1, C);
        assertRow(c, "-70.00", "-14.32", "-25.72");
        assertLot(c.get("lots").get(0), "-35.00", "-16.66", "-90.84");
        assertLot(c.get("lots").get(1), "-35.00", "2.34", "6.27");

        Res r = call(delete("/api/market-data/overrides/options").param("symbol", "ZZQ")
                .param("expirationDate", "2026-12-18").param("strike", "9").param("optionType", "CALL"));
        assertThat(r.status()).as(r.raw()).isEqualTo(204);
        c = position("1", 1, C);
        assertLot(c.get("lots").get(0), "-20.00", "-1.66", "-9.05");
        assertLot(c.get("lots").get(1), "-20.00", "17.34", "46.44");
    }

    @Test
    void ac6_noQuoteExpiredPendingAndStaleQuote() throws Exception {
        scenario();
        trade(1, "/api/option-trades", "STO", "2026-12-18", "7", "PUT", 1, "12.00", "2026-11-12T10:00");
        trade(1, "/api/option-trades", "STO", "2026-11-13", "8", "PUT", 1, "15.00", "2026-11-10T10:00");
        run("2026-11-16T10:00:00");

        JsonNode p7 = position("1", 1, P7);
        assertThat(p7.get("unrealizedStatus").asText()).isEqualTo("NO_QUOTE");
        assertUnavailable(p7.at("/lots/0"), "NO_QUOTE", "无报价");
        JsonNode e = position("1", 1, EXPIRED);
        assertThat(e.get("unrealizedStatus").asText()).isEqualTo("EXPIRED_PENDING");
        assertUnavailable(e.at("/lots/0"), "EXPIRED_PENDING", "已到期，待处理");

        cboe.put("ZZQ", Reply.of(500, "boom"));
        fake.configure(new InMemoryQuoteTransport.Sources(true, cboe, new HashMap<>()));
        run("2026-11-16T10:15:00");
        JsonNode c = position("1", 1, C);
        assertThat(c.at("/optionQuote/fetchFailed").asBoolean()).isTrue();
        assertThat(c.at("/optionQuote/mark").asText()).isEqualTo("0.20");
        assertLot(c.get("lots").get(0), "-20.00", "-1.66", "-9.05");
        assertLot(c.get("lots").get(1), "-20.00", "17.34", "46.44");
    }

    @Test
    void ac7_lastLotTakesTheRemainder() throws Exception {
        scenario();
        zzq("8.655");
        run("2026-11-16T10:00:00");
        JsonNode s = stockRow(String.valueOf(accountB), accountB);
        assertThat(s.at("/quote/price").asText()).isEqualTo("8.655");
        assertRow(s, "1731.00", "-9.00", "-0.52");
        assertLot(s.get("lots").get(0), "874.16", "-5.84", "-0.66");
        assertLot(s.get("lots").get(1), "856.84", "-3.16", "-0.37");
    }

    @Test
    void ac8_partialStockSale() throws Exception {
        scenario();
        run("2026-11-16T10:00:00");
        at("2026-11-16T10:10:00");
        trade(1, "/api/option-trades/closes", "BTC", "2026-12-18", "9", "CALL", 1, "20.65", "2026-11-16T10:10");
        at("2026-11-16T10:20:00");
        stockTrade(1, "SELL", 50, "440.00", "2026-11-16T10:20");
        JsonNode s = stockRow("1", 1);
        assertThat(s.get("shares").asLong()).isEqualTo(150);
        assertRow(s, "1297.50", "41.85", "3.33");
        assertThat(s.at("/lots/0/remainingShares").asInt()).isEqualTo(50);
        assertThat(s.at("/lots/0/remainingCost").asText()).isEqualTo("475.00");
        assertLot(s.get("lots").get(0), "432.50", "-42.50", "-8.95");
        assertLot(s.get("lots").get(1), "865.00", "84.35", "10.81");
    }

    @Test
    void ac9_allAccounts() throws Exception {
        scenario();
        run("2026-11-16T10:00:00");
        JsonNode c = position("ALL", 1, C);
        assertLot(c.get("lots").get(0), "-20.00", "-1.66", "-9.05");
        assertLot(c.get("lots").get(1), "-20.00", "17.34", "46.44");
        JsonNode s = stockRow("ALL", accountB);
        assertRow(s, "1730.00", "-10.00", "-0.57");
        assertLot(s.get("lots").get(0), "873.65", "-6.35", "-0.72");
        assertLot(s.get("lots").get(1), "856.35", "-3.65", "-0.42");
        assertThat(stockRow("ALL", 1).get("lots")).isEqualTo(stockRow("1", 1).get("lots"));
        assertThat(stockRow("ALL", accountB).get("lots"))
                .isEqualTo(stockRow(String.valueOf(accountB), accountB).get("lots"));
    }

    @Test
    void stockWithoutPrice_lotsNoQuote() throws Exception {
        deposit(1, "1000.00");
        stockTrade(1, "BUY", 10, "500.00", "2026-11-13T10:00");
        JsonNode s = stockRow("1", 1);
        assertThat(s.get("unrealizedStatus").asText()).isEqualTo("NO_QUOTE");
        assertUnavailable(s.at("/lots/0"), "NO_QUOTE", "无报价");
    }

    // ---------------------------------------------------------------- helpers

    static void assertRow(JsonNode n, String mv, String pnl, String pct) {
        assertThat(n.get("unrealizedStatus").asText()).isEqualTo("OK");
        assertThat(n.get("marketValue").asText()).isEqualTo(mv);
        assertThat(n.get("unrealizedPnl").asText()).isEqualTo(pnl);
        assertThat(n.get("unrealizedPnlPercent").asText()).isEqualTo(pct);
    }

    static void assertLot(JsonNode n, String mv, String pnl, String pct) {
        assertRow(n, mv, pnl, pct);
        assertThat(n.get("unrealizedLabel").isNull()).isTrue();
        // amounts travel as JSON strings
        assertThat(n.get("marketValue").isTextual()).isTrue();
        assertThat(n.get("unrealizedPnl").isTextual()).isTrue();
        assertThat(n.get("unrealizedPnlPercent").isTextual()).isTrue();
    }

    static void assertUnavailable(JsonNode n, String status, String label) {
        assertThat(n.get("unrealizedStatus").asText()).isEqualTo(status);
        assertThat(n.get("unrealizedLabel").asText()).isEqualTo(label);
        assertThat(n.get("marketValue").isNull()).isTrue();
        assertThat(n.get("unrealizedPnl").isNull()).isTrue();
        assertThat(n.get("unrealizedPnlPercent").isNull()).isTrue();
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
        n.put("occurredAtLocal", "2026-09-01T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    void stockTrade(long account, String type, int shares, String amount, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", "ZZQ");
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", time);
        Res r = postJson(account, "/api/stock-trades", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    void trade(long account, String url, String action, String exp, String strike, String type, int contracts,
               String net, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQ");
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

    JsonNode position(String header, long account, String label) throws Exception {
        for (JsonNode p : ok(get("/api/option-positions").header(AccountRef.HEADER, header)).get("positions")) {
            if (p.get("contractLabel").asText().equals(label) && p.get("accountId").asLong() == account) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    JsonNode stockRow(String header, long account) throws Exception {
        for (JsonNode p : ok(get("/api/stock-positions").header(AccountRef.HEADER, header)).get("positions")) {
            if (p.get("symbol").asText().equals("ZZQ") && p.get("accountId").asLong() == account) {
                return p;
            }
        }
        throw new AssertionError("no stock position ZZQ in account " + account);
    }
}
