package com.optionmgr.quote.web;

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
import com.optionmgr.quote.domain.RunTrigger;
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
 * FEAT-016 acceptance criteria (backend; FEAT-052 Q270 run times and 90-minute warning) through the HTTP API and database, with the in-memory fake sources (never
 * the real CBOE / Finnhub) and a movable clock. Scenario (requirement): 2026-11-23 (Monday); account A (id 1) STO 1
 * QRTV 2026-12-18 50P (P1); account B: 100 IXQZ, STO 1 IXQZ 2026-12-18 60C (P2), STO 1 QRTV 2026-12-18 50P (P3).
 * Fictional tickers.
 */
@SpringBootTest(properties = "optionmgr.quotes.transport=in-memory")
@AutoConfigureMockMvc
class MarketDataControllerTest {

    static final String P1 = "QRTV 2026-12-18 50 P";
    static final String P2 = "IXQZ 2026-12-18 60 C";
    static final String P4 = "QRTV 2026-12-18 47.5 P";

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
        MovableClock quoteTestClock() {
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
    final Map<String, Reply> finnhub = new HashMap<>();
    boolean finnhubKey = true;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        at("2026-11-23T09:00:00");
        accountB = scenario();
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
        finnhub.clear();
        finnhubKey = true;
    }

    // ---------------------------------------------------------------- scenario and fake data

    long scenario() throws Exception {
        deposit(1, "20000.00");
        open(1, "STO", "QRTV", "2026-12-18", "50", "PUT", "150.00");
        long b = newAccount("账户B");
        deposit(b, "20000.00");
        Res r = postJson(b, "/api/stock-trades", stock("BUY", "IXQZ", 100, "6000.00"));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        open(b, "STO", "IXQZ", "2026-12-18", "60", "CALL", "200.00");
        open(b, "STO", "QRTV", "2026-12-18", "50", "PUT", "150.00");
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:45:00",
                option("QRTV261218P00050000", "1.20", "1.30", "1.24"), option("QRTV261218P00055000", "3.10", "3.30", null))));
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", "2026-11-23 09:45:00",
                option("IXQZ261218C00060000", "2.10", "2.30", null))));
        apply();
        return b;
    }

    void apply() {
        fake.configure(new InMemoryQuoteTransport.Sources(finnhubKey, cboe, finnhub));
    }

    static String option(String code, String bid, String ask, String theo) {
        return "{\"option\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask
                + (theo == null ? "" : ",\"theo\":" + theo)
                + ",\"iv\":0.3512,\"delta\":-0.3021,\"gamma\":0.0512,\"theta\":-0.0456,\"vega\":0.0711,\"rho\":-0.0123,"
                + "\"last_trade_price\":1.22,\"volume\":120,\"open_interest\":3400}";
    }

    static String chain(String symbol, String price, String timestamp, String... options) {
        return "{\"timestamp\":\"" + timestamp + "\",\"data\":{\"symbol\":\"" + symbol + "\",\"current_price\":" + price
                + ",\"bid\":0,\"ask\":0,\"prev_day_close\":50.00,\"options\":[" + String.join(",", options) + "]}}";
    }

    void at(String nyLocal) {
        clock.set(ny(nyLocal));
    }

    /** The scheduler at that time (the test-support endpoint only exists in e2e; same service call). */
    void run(String nyLocal) {
        at(nyLocal);
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick at %s: %s", nyLocal, t.skipReason()).isTrue();
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_tenOClockFetchShowsQuotesInBothAccounts() throws Exception {
        run("2026-11-23T10:00:00");
        for (long acc : new long[] {1, accountB}) {
            JsonNode p = position(acc, P1);
            assertThat(p.at("/underlyingQuote/price").asText()).isEqualTo("52.40");
            assertThat(p.at("/underlyingQuote/source").asText()).isEqualTo("CBOE");
            assertThat(p.at("/optionQuote/bid").asText()).isEqualTo("1.20");
            assertThat(p.at("/optionQuote/ask").asText()).isEqualTo("1.30");
            assertThat(p.at("/optionQuote/mark").asText()).isEqualTo("1.25");
            assertThat(p.at("/optionQuote/markBasis").asText()).isEqualTo("MID");
            assertThat(p.at("/optionQuote/quoteTime").asText()).isEqualTo("2026-11-23T14:45:00Z");
            assertThat(p.at("/optionQuote/quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
            assertThat(p.at("/optionQuote/contractStatus").asText()).isEqualTo("QUOTED");
            assertThat(p.at("/optionQuote/source").asText()).isEqualTo("CBOE");
            assertThat(p.at("/optionQuote/fetchFailed").asBoolean()).isFalse();
            assertThat(p.at("/optionQuote/staleLabel").isNull()).isTrue();
            assertThat(p.at("/optionQuote/lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        }
        JsonNode p2 = position(accountB, P2);
        assertThat(p2.at("/underlyingQuote/price").asText()).isEqualTo("61.10");
        assertThat(p2.at("/optionQuote/bid").asText()).isEqualTo("2.10");
        assertThat(p2.at("/optionQuote/ask").asText()).isEqualTo("2.30");
        assertThat(p2.at("/optionQuote/mark").asText()).isEqualTo("2.20");
        JsonNode stock = ok(get("/api/stock-positions").header(AccountRef.HEADER, accountB)).at("/positions/0/quote");
        assertThat(stock.get("price").asText()).isEqualTo("61.10");
        assertThat(stock.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
        assertThat(stock.get("manual").asBoolean()).isFalse();
    }

    @Test
    void ac2_onlyHeldSymbolsAndContracts_greeksStored_closedSymbolNoLongerFetched() throws Exception {
        List<String> many = new ArrayList<>();
        many.add(option("QRTV261218P00050000", "1.20", "1.30", "1.24"));
        for (int i = 1; i < 3400; i++) {
            many.add(option(String.format("QRTV270115C%08d", i * 10), "1", "2", null));
        }
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:45:00", many.toArray(String[]::new))));
        apply();
        run("2026-11-23T10:00:00");
        assertThat(requested()).containsExactly("CBOE IXQZ", "CBOE QRTV");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_quote", Integer.class)).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT iv, delta, theta, theo FROM option_quote WHERE symbol = 'QRTV' AND strike = 50");
        assertThat((java.math.BigDecimal) row.get("iv")).isEqualByComparingTo("0.3512");
        assertThat((java.math.BigDecimal) row.get("delta")).isEqualByComparingTo("-0.3021");
        assertThat((java.math.BigDecimal) row.get("theta")).isEqualByComparingTo("-0.0456");
        assertThat((java.math.BigDecimal) row.get("theo")).isEqualByComparingTo("1.24");

        at("2026-11-23T10:10:00");
        close(1, "QRTV", "2026-12-18", "50", "PUT");
        close(accountB, "QRTV", "2026-12-18", "50", "PUT");
        fake.clearRequests();
        run("2026-11-23T11:00:00");
        assertThat(requested()).containsExactly("CBOE IXQZ");
    }

    @Test
    void ac3_markFromTheoThenNoQuote() throws Exception {
        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", "2026-11-23 09:45:00",
                option("IXQZ261218C00060000", "0", "0.05", "0.03"))));
        apply();
        run("2026-11-23T10:00:00");
        assertThat(position(accountB, P2).at("/optionQuote/mark").asText()).isEqualTo("0.03");
        assertThat(position(accountB, P2).at("/optionQuote/markBasis").asText()).isEqualTo("THEO");

        cboe.put("IXQZ", Reply.of(200, chain("IXQZ", "61.10", "2026-11-23 09:45:00",
                option("IXQZ261218C00060000", "0", "0.05", null))));
        apply();
        run("2026-11-23T11:00:00");
        JsonNode q = position(accountB, P2).get("optionQuote");
        assertThat(q.get("mark").isNull()).isTrue();
        assertThat(q.get("bid").asText()).isEqualTo("0.00");
        assertThat(q.get("contractStatus").asText()).isEqualTo("QUOTED");
    }

    @Test
    void ac4_cboeFailsFirstTime_finnhubPrice_optionNeverQuoted() throws Exception {
        cboe.put("IXQZ", Reply.of(500, "oops"));
        finnhub.put("IXQZ", Reply.of(200, "{\"c\":61.05,\"pc\":60.80,\"t\":1795445998}"));
        apply();
        run("2026-11-23T10:00:00");
        JsonNode stock = ok(get("/api/stock-positions").header(AccountRef.HEADER, accountB)).at("/positions/0/quote");
        assertThat(stock.get("price").asText()).isEqualTo("61.05");
        assertThat(stock.get("source").asText()).isEqualTo("FINNHUB");
        assertThat(stock.get("quoteTime").asText()).isEqualTo("2026-11-23T14:59:58Z");
        assertThat(stock.get("fetchFailed").asBoolean()).isFalse();
        JsonNode p2 = position(accountB, P2).get("optionQuote");
        assertThat(p2.get("mark").isNull()).isTrue();
        assertThat(p2.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(p2.get("fetchFailed").asBoolean()).isTrue();
        assertThat(p2.get("staleLabel").asText()).isEqualTo("行情未更新（从未成功）");
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("1.25");
        assertThat(position(accountB, P1).at("/optionQuote/mark").asText()).isEqualTo("1.25");
        assertThat(requested()).containsExactly("CBOE IXQZ", "FINNHUB IXQZ", "CBOE QRTV");
    }

    @Test
    void ac5_missingFromSourceKeepsLastQuote_neverQuotedShowsNoQuote() throws Exception {
        open(1, "STO", "QRTV", "2026-12-18", "47.5", "PUT", "100.00");
        run("2026-11-23T10:00:00");
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.50", "2026-11-23 10:00:00",
                option("QRTV261218P00055000", "3.10", "3.30", null))));
        apply();
        run("2026-11-23T11:00:00");
        JsonNode p4 = position(1, P4).get("optionQuote");
        assertThat(p4.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(p4.get("mark").isNull()).isTrue();
        JsonNode p1 = position(1, P1).get("optionQuote");
        assertThat(p1.get("mark").asText()).isEqualTo("1.25");
        assertThat(p1.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(p1.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-23 09:45）");
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
    }

    @Test
    void ac6_symbolFailureLabelsOnlyItsRows_noWarning() throws Exception {
        run("2026-11-23T10:00:00");
        cboe.put("IXQZ", Reply.unreachableReply());
        finnhub.put("IXQZ", Reply.of(502, ""));
        apply();
        run("2026-11-23T11:00:00");
        JsonNode p2 = position(accountB, P2);
        assertThat(p2.at("/optionQuote/staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:00）");
        assertThat(p2.at("/optionQuote/mark").asText()).isEqualTo("2.20");
        assertThat(p2.at("/underlyingQuote/price").asText()).isEqualTo("61.10");
        assertThat(p2.at("/underlyingQuote/staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:00）");
        JsonNode stock = ok(get("/api/stock-positions").header(AccountRef.HEADER, accountB)).at("/positions/0/quote");
        assertThat(stock.get("price").asText()).isEqualTo("61.10");
        assertThat(stock.get("staleLabel").asText()).isEqualTo("行情未更新（上次：11-23 10:00）");
        assertThat(position(1, P1).at("/optionQuote/staleLabel").isNull()).isTrue();
        at("2026-11-23T12:30:00");
        assertThat(ok(get("/api/market-data/status")).get("stale").asBoolean()).as("QRTV succeeded at 11:00 (Q270: 90 min)").isFalse();
    }

    @Test
    void ac6_allSymbolsFailing_warnsAfter90Minutes_untilNextSuccess() throws Exception {
        run("2026-11-23T10:00:00");
        cboe.put("IXQZ", Reply.unreachableReply());
        cboe.put("QRTV", Reply.of(500, ""));
        apply();
        run("2026-11-23T11:00:00");
        at("2026-11-23T11:30:00");
        assertThat(ok(get("/api/market-data/status")).get("stale").asBoolean()).isFalse();
        at("2026-11-23T11:31:00");
        JsonNode status = ok(get("/api/market-data/status"));
        assertThat(status.get("stale").asBoolean()).isTrue();
        assertThat(status.get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-23 10:00 美东）");
        assertThat(status.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-23T10:00:00");
        assertThat(status.get("lastAttemptAtLocal").asText()).isEqualTo("2026-11-23T11:00:00");
        assertThat(status.get("trackedSymbolCount").asInt()).isEqualTo(2);
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("1.25");

        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 10:30:00",
                option("QRTV261218P00050000", "1.20", "1.30", "1.24"))));
        apply();
        run("2026-11-23T12:00:00");
        assertThat(ok(get("/api/market-data/status")).get("warning").isNull()).isTrue();
    }

    @Test
    void ac7_closeSnapshot_noRunsAfterwards_noneOnThanksgiving() throws Exception {
        run("2026-11-23T10:00:00");
        at("2026-11-23T16:20:00");
        QuoteFetchService.TickOutcome close = fetches.tick();
        assertThat(close.trigger()).isEqualTo(RunTrigger.CLOSE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM underlying_quote_close WHERE snapshot_date = DATE '2026-11-23'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_quote_close WHERE snapshot_date = DATE '2026-11-23'",
                Integer.class)).isEqualTo(2);
        fake.clearRequests();
        for (String t : List.of("2026-11-23T16:25:00", "2026-11-23T20:00:00", "2026-11-24T09:15:00",
                "2026-11-24T09:30:00", "2026-11-24T10:15:00")) {
            at(t);
            assertThat(fetches.tick().skipReason()).isEqualTo("NOT_A_SLOT");
            assertThat(ok(get("/api/market-data/status")).get("stale").asBoolean()).as(t).isFalse();
        }
        at("2026-11-26T10:00:00");
        assertThat(fetches.tick().skipReason()).isEqualTo("NON_TRADING_DAY");
        assertThat(ok(get("/api/market-data/status")).get("stale").asBoolean()).isFalse();
        assertThat(requested()).isEmpty();
    }

    @Test
    void ac8_manualOverrides_sharedAcrossAccounts_replacedByNextSuccess() throws Exception {
        run("2026-11-23T10:00:00");
        at("2026-11-23T10:05:00");
        JsonNode o = ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"50.0\",\"optionType\":\"PUT\",\"mark\":\"1.4\"}"));
        assertThat(o.get("mark").asText()).isEqualTo("1.40");
        assertThat(o.get("automaticMark").asText()).isEqualTo("1.25");
        JsonNode u = ok(put("/api/market-data/overrides/underlyings/QRTV").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"52.00\"}"));
        assertThat(u.get("price").asText()).isEqualTo("52.00");
        assertThat(u.get("manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");
        for (long acc : new long[] {1, accountB}) {
            JsonNode p = position(acc, P1);
            assertThat(p.at("/optionQuote/mark").asText()).isEqualTo("1.40");
            assertThat(p.at("/optionQuote/manual").asBoolean()).isTrue();
            assertThat(p.at("/optionQuote/manualEnteredAtLocal").asText()).isEqualTo("2026-11-23T10:05:00");
            assertThat(p.at("/underlyingQuote/price").asText()).isEqualTo("52.00");
            assertThat(p.at("/underlyingQuote/automaticPrice").asText()).isEqualTo("52.40");
        }
        cboe.put("QRTV", Reply.of(500, ""));
        apply();
        run("2026-11-23T11:00:00");
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("1.40");
        assertThat(position(1, P1).at("/underlyingQuote/price").asText()).isEqualTo("52.00");
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 10:15:00",
                option("QRTV261218P00050000", "1.20", "1.30", "1.24"))));
        apply();
        run("2026-11-23T12:00:00");
        JsonNode p = position(accountB, P1);
        assertThat(p.at("/optionQuote/mark").asText()).isEqualTo("1.25");
        assertThat(p.at("/optionQuote/manual").asBoolean()).isFalse();
        assertThat(p.at("/underlyingQuote/price").asText()).isEqualTo("52.40");
        assertThat(p.at("/underlyingQuote/manual").asBoolean()).isFalse();
    }

    @Test
    void ac8_clearAndValidation() throws Exception {
        run("2026-11-23T10:00:00");
        ok(put("/api/market-data/overrides/underlyings/qrtv").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"52.00\"}"));
        assertThat(call(delete("/api/market-data/overrides/underlyings/QRTV")).status()).isEqualTo(204);
        assertThat(call(delete("/api/market-data/overrides/underlyings/QRTV")).status()).isEqualTo(204);
        assertThat(position(1, P1).at("/underlyingQuote/manual").asBoolean()).isFalse();
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"50\",\"optionType\":\"PUT\",\"mark\":\"1.40\"}"));
        assertThat(call(delete("/api/market-data/overrides/options?symbol=QRTV&expirationDate=2026-12-18&strike=50.00&optionType=PUT"))
                .status()).isEqualTo(204);
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("1.25");

        Res bad = call(put("/api/market-data/overrides/underlyings/QRTV").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"0\"}"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().at("/details/0/message").asText()).isEqualTo("价格必须大于 0");
        bad = call(put("/api/market-data/overrides/underlyings/QRTV").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"1.23456\"}"));
        assertThat(bad.body().at("/details/0/message").asText()).isEqualTo("价格最多 4 位小数");
        bad = call(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("details")).hasSize(5);
        Res notHeld = call(put("/api/market-data/overrides/underlyings/ZZZQ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"5\"}"));
        assertThat(notHeld.status()).isEqualTo(422);
        assertThat(notHeld.body().get("code").asText()).isEqualTo("QUOTE_TARGET_NOT_HELD");
        assertThat(notHeld.body().get("message").asText()).isEqualTo("当前没有 ZZZQ 的持仓，不能设置手动价格");
        notHeld = call(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"45\",\"optionType\":\"PUT\",\"mark\":\"1\"}"));
        assertThat(notHeld.body().get("message").asText()).isEqualTo("当前没有 QRTV 2026-12-18 45 P 的持仓，不能设置手动价格");
    }

    @Test
    void ac9_refreshAndCooldown() throws Exception {
        run("2026-11-23T10:00:00");
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:47:00",
                option("QRTV261218P00050000", "1.30", "1.40", "1.24"))));
        apply();
        fake.clearRequests();
        at("2026-11-23T10:02:00");
        JsonNode r = ok(post("/api/market-data/refresh"));
        assertThat(r.get("performed").asBoolean()).isTrue();
        assertThat(r.get("results")).hasSize(2);
        assertThat(r.at("/results/1/symbol").asText()).isEqualTo("QRTV");
        assertThat(r.at("/results/1/result").asText()).isEqualTo("SUCCESS");
        assertThat(r.at("/status/refreshAllowed").asBoolean()).isFalse();
        assertThat(r.at("/status/refreshAvailableAt").asText()).isEqualTo("2026-11-23T15:03:00Z");
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("1.35");
        fake.clearRequests();
        at("2026-11-23T10:02:30");
        JsonNode again = ok(post("/api/market-data/refresh"));
        assertThat(again.get("performed").asBoolean()).isFalse();
        assertThat(again.get("reason").asText()).isEqualTo("COOLDOWN");
        assertThat(requested()).isEmpty();
        at("2026-11-23T10:03:00");
        assertThat(ok(get("/api/market-data/status")).get("refreshAllowed").asBoolean()).isTrue();
        assertThat(ok(post("/api/market-data/refresh")).get("performed").asBoolean()).isTrue();
    }

    @Test
    void ac10_nothingReachable_noQuotes_neverSucceededWarning_bookkeepingWorks() throws Exception {
        cboe.clear();
        finnhubKey = false;
        apply();
        at("2026-11-28T11:00:00");
        JsonNode r = ok(post("/api/market-data/refresh"));
        assertThat(r.at("/results/0/result").asText()).isEqualTo("FAILED");
        assertThat(r.at("/results/0/finnhubResult").asText()).isEqualTo("NO_KEY");
        for (String label : List.of(P1, P2)) {
            JsonNode q = position(label.equals(P2) ? accountB : 1, label).get("optionQuote");
            assertThat(q.get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
            assertThat(q.get("mark").isNull()).isTrue();
        }
        JsonNode status = ok(get("/api/market-data/status"));
        assertThat(status.get("warning").asText()).isEqualTo("行情未更新（从未成功获取）");
        open(1, "STO", "QRTV", "2027-01-15", "45", "PUT", "120.00");
    }

    @Test
    void q151_indexSymbolsNeverFetched_overrideAllowed() throws Exception {
        deposit(1, "1000.00");
        open(1, "BTO", "SPX", "2026-12-18", "6000", "CALL", "300.00");
        run("2026-11-23T10:00:00");
        assertThat(requested()).containsExactly("CBOE IXQZ", "CBOE QRTV");
        JsonNode spx = position(1, "SPX 2026-12-18 6000 C");
        assertThat(spx.at("/optionQuote/contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(spx.at("/optionQuote/fetchFailed").asBoolean()).isFalse();
        assertThat(spx.at("/underlyingQuote/price").isNull()).isTrue();
        assertThat(ok(get("/api/market-data/status")).get("trackedSymbolCount").asInt()).isEqualTo(2);
        JsonNode o = ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"SPX\",\"expirationDate\":\"2026-12-18\",\"strike\":\"6000\",\"optionType\":\"CALL\",\"mark\":\"25.5\"}"));
        assertThat(o.get("mark").asText()).isEqualTo("25.50");
    }

    @Test
    void q150_q151_expiredContractsNoLongerFetched_expiredLabel() throws Exception {
        run("2026-11-23T10:00:00");
        fake.clearRequests();
        run("2026-12-21T10:00:00");
        // QRTV only has expired positions left; IXQZ still has shares
        assertThat(requested()).containsExactly("CBOE IXQZ");
        JsonNode p1 = position(1, P1).get("optionQuote");
        assertThat(p1.get("mark").asText()).isEqualTo("1.25");
        assertThat(p1.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(p1.get("contractStatusLabel").asText()).isEqualTo("已到期（最后报价：11-23 09:45）");
        assertThat(p1.get("fetchFailed").asBoolean()).isFalse();
        assertThat(p1.get("staleLabel").isNull()).isTrue();
        assertThat(ok(get("/api/market-data/status")).get("trackedSymbolCount").asInt()).isEqualTo(1);
        // still overridable
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2026-12-18\",\"strike\":\"50\",\"optionType\":\"PUT\",\"mark\":\"0.01\"}"));
        assertThat(position(1, P1).at("/optionQuote/mark").asText()).isEqualTo("0.01");
    }

    @Test
    void ambiguousCodesAreNotGuessed() throws Exception {
        cboe.put("QRTV", Reply.of(200, chain("QRTV", "52.40", "2026-11-23 09:45:00",
                option("QRTV261218P00050000", "1.20", "1.30", "1.24"), option("B1QRTV261218P00050000", "9", "9.5", null))));
        apply();
        JsonNode r = ok(post("/api/market-data/refresh"));
        assertThat(r.at("/results/1/result").asText()).isEqualTo("SUCCESS");
        assertThat(r.at("/results/1/failureDetail").asText())
                .isEqualTo("ambiguous: QRTV261218P00050000,B1QRTV261218P00050000");
        assertThat(position(1, P1).at("/optionQuote/contractStatus").asText()).isEqualTo("NEVER_QUOTED");
    }

    // ---------------------------------------------------------------- HTTP helpers

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
        n.put("occurredAtLocal", "2026-11-20T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    ObjectNode stock(String type, String symbol, int shares, String amount) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", "2026-11-20T09:45");
        return n;
    }

    void open(long account, String action, String symbol, String exp, String strike, String type, String net)
            throws Exception {
        trade(account, "/api/option-trades", action, symbol, exp, strike, type, net, "2026-11-20T10:00");
    }

    void close(long account, String symbol, String exp, String strike, String type) throws Exception {
        trade(account, "/api/option-trades/closes", "BTC", symbol, exp, strike, type, "100.00", "2026-11-23T10:10");
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

    JsonNode position(long account, String label) throws Exception {
        for (JsonNode p : ok(get("/api/option-positions").header(AccountRef.HEADER, String.valueOf(account)))
                .get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    List<String> requested() {
        return fake.requests().stream().map(r -> r.source() + " " + r.symbol()).toList();
    }
}
