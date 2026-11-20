package com.optionmgr.quote.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.domain.QuoteSource;
import com.optionmgr.quote.domain.RunTrigger;
import com.optionmgr.quote.service.InMemoryQuoteTransport;
import com.optionmgr.quote.service.InMemoryQuoteTransport.Reply;
import com.optionmgr.quote.service.QuoteFetchService;
import com.optionmgr.quote.service.QuoteSettings;
import com.optionmgr.quote.service.YahooSession;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-052 acceptance criteria 1-10 (backend) through the HTTP API and database: Yahoo as option source, r = 0.00 %,
 * the in-memory fake sources (never the real Yahoo / CBOE / Finnhub) and a movable clock. Scenario (requirement):
 * 2026-11-20 (Friday); account A (id 1): P1 STO 1 QRTV 2027-02-19 50P, P2 STO 1 QRTV 2026-12-18 45P; account B: P3
 * BTO 2 QRTV 2027-02-19 50C, 100 IXQZ. Fictional tickers.
 */
@SpringBootTest(properties = {"optionmgr.quotes.transport=in-memory", "optionmgr.quotes.option-source=yahoo",
        "optionmgr.quotes.risk-free-rate-percent=0.00"})
@AutoConfigureMockMvc
@Import(MarketDataControllerTest.ClockConfig.class)
class YahooQuotesControllerTest {

    static final String P1 = "QRTV 2027-02-19 50 P";
    static final String P2 = "QRTV 2026-12-18 45 P";
    static final String P3 = "QRTV 2027-02-19 50 C";
    static final String P4 = "QRTV 2027-02-19 45 P";
    static final LocalDate FEB19 = LocalDate.parse("2027-02-19");
    static final LocalDate DEC18 = LocalDate.parse("2026-12-18");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MarketDataControllerTest.MovableClock clock;
    @Autowired
    InMemoryQuoteTransport fake;
    @Autowired
    QuoteFetchService fetches;
    @Autowired
    QuoteSettings settings;
    @Autowired
    YahooSession session;

    long accountB;
    List<Reply> handshake;
    final Map<String, InMemoryQuoteTransport.YahooSymbol> yahoo = new HashMap<>();
    final Map<String, Reply> finnhub = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        clean();
        at("2026-11-20T09:00:00");
        accountB = scenario();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        for (String t : List.of("audit_record", "stock_trade", "option_close_lot", "option_trade", "cash_ledger_entry",
                "cash_transfer", "underlying_quote", "option_quote", "underlying_quote_close", "option_quote_close")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.update("UPDATE market_data_status SET last_attempt_at = NULL, last_success_at = NULL, last_refresh_at = NULL");
        fake.reset();
        settings.reset();
        session.clear();
        handshake = null;
        yahoo.clear();
        finnhub.clear();
    }

    // ---------------------------------------------------------------- scenario and fake Yahoo

    long scenario() throws Exception {
        deposit(1, "20000.00");
        trade(1, "/api/option-trades", "STO", "2027-02-19", "50", "PUT", 1, "395.00", "2026-11-19T10:00");
        trade(1, "/api/option-trades", "STO", "2026-12-18", "45", "PUT", 1, "65.00", "2026-11-19T10:00");
        long b = newAccount("账户B");
        deposit(b, "20000.00");
        trade(b, "/api/option-trades", "BTO", "2027-02-19", "50", "CALL", 2, "820.00", "2026-11-19T10:00");
        Res r = postJson(b, "/api/stock-trades", stock());
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        tenOClockChains();
        apply();
        return b;
    }

    void tenOClockChains() {
        dated("QRTV", FEB19, ok(chain("50.00", "2026-11-20T10:00:00", List.of(DEC18, FEB19),
                option("QRTV270219C00050000", "4.00", "4.20", "4.10", "0.3000"),
                option("QRTV270219P00050000", "3.90", "4.10", "3.95", "0.3000"))));
        dated("QRTV", DEC18, ok(chain("50.00", "2026-11-20T10:00:00", List.of(DEC18, FEB19),
                option("QRTV261218P00045000", "0.60", "0.70", "0.62", "0.3500"))));
        yahoo.put("IXQZ", new InMemoryQuoteTransport.YahooSymbol(null,
                List.of(ok(chain("61.10", "2026-11-20T10:00:00", List.of())))));
    }

    void dated(String symbol, LocalDate date, Reply... replies) {
        InMemoryQuoteTransport.YahooSymbol s = yahoo.get(symbol);
        Map<LocalDate, List<Reply>> byDate = new HashMap<>(s == null ? Map.of() : s.byDate());
        byDate.put(date, List.of(replies));
        yahoo.put(symbol, new InMemoryQuoteTransport.YahooSymbol(byDate, s == null ? null : s.fallback()));
    }

    void apply() {
        fake.configure(new InMemoryQuoteTransport.Sources(true, Map.of(), finnhub,
                new InMemoryQuoteTransport.Yahoo(handshake, yahoo)));
    }

    static Reply ok(String body) {
        return Reply.of(200, body);
    }

    static String option(String code, String bid, String ask, String last, String iv) {
        return "{\"contractSymbol\":\"" + code + "\",\"bid\":" + bid + ",\"ask\":" + ask + ",\"lastPrice\":" + last
                + ",\"impliedVolatility\":" + iv + ",\"volume\":12,\"openInterest\":340,\"inTheMoney\":false}";
    }

    static String chain(String price, String nyTime, List<LocalDate> expirations, String... options) {
        long t = ny(nyTime).getEpochSecond();
        List<String> calls = new ArrayList<>();
        List<String> puts = new ArrayList<>();
        for (String o : options) {
            (o.contains("C000") ? calls : puts).add(o);
        }
        List<String> exp = expirations.stream()
                .map(d -> String.valueOf(d.atStartOfDay(ZoneOffset.UTC).toEpochSecond())).toList();
        return "{\"optionChain\":{\"error\":null,\"result\":[{\"underlyingSymbol\":\"X\",\"expirationDates\":["
                + String.join(",", exp) + "],\"quote\":{\"regularMarketPrice\":" + price + ",\"regularMarketTime\":" + t
                + ",\"regularMarketPreviousClose\":49.60,\"bid\":49.98,\"ask\":50.02,\"marketState\":\"REGULAR\"},"
                + "\"options\":[{\"calls\":[" + String.join(",", calls) + "],\"puts\":[" + String.join(",", puts)
                + "]}]}]}}";
    }

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    void at(String nyLocal) {
        clock.set(ny(nyLocal));
    }

    List<QuoteFetchService.SymbolOutcome> run(String nyLocal) {
        at(nyLocal);
        QuoteFetchService.TickOutcome t = fetches.tick();
        assertThat(t.ran()).as("tick at %s: %s", nyLocal, t.skipReason()).isTrue();
        return t.results();
    }

    List<String> requested() {
        return fake.requests().stream().map(r -> r.source() + " " + r.kind() + " " + r.symbol() + " "
                + r.expirationDate()).toList();
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_tenOClockQuotes_viaRefresh() throws Exception {
        at("2026-11-20T10:00:00");
        JsonNode r = ok(post("/api/market-data/refresh"));
        assertThat(r.get("performed").asBoolean()).isTrue();
        for (JsonNode s : r.get("results")) {
            assertThat(s.get("result").asText()).isEqualTo("SUCCESS");
            assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
            assertThat(s.get("yahooResult").asText()).isEqualTo("SUCCESS");
            assertThat(s.get("cboeResult").isNull()).isTrue();
            assertThat(s.get("finnhubResult").isNull()).isTrue();
            assertThat(s.get("underlyingPriceSource").asText()).isEqualTo("YAHOO");
            assertThat(s.get("failureDetail").isNull()).isTrue();
        }
        assertThat(r.at("/status/optionSource").asText()).isEqualTo("YAHOO");
        assertThat(r.at("/status/riskFreeRatePercent").asText()).isEqualTo("0.00");

        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/underlyingQuote/price").asText()).isEqualTo("50.00");
        assertThat(p1.at("/underlyingQuote/source").asText()).isEqualTo("YAHOO");
        assertThat(p1.at("/underlyingQuote/quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(p1.at("/optionQuote/bid").asText()).isEqualTo("3.90");
        assertThat(p1.at("/optionQuote/ask").asText()).isEqualTo("4.10");
        assertThat(p1.at("/optionQuote/mark").asText()).isEqualTo("4.00");
        assertThat(p1.at("/optionQuote/markBasis").asText()).isEqualTo("MID");
        assertThat(p1.at("/optionQuote/source").asText()).isEqualTo("YAHOO");
        assertThat(p1.at("/optionQuote/quoteTime").asText()).isEqualTo("2026-11-20T15:00:00Z");
        assertThat(p1.at("/optionQuote/quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(p1.at("/unrealizedStatus").asText()).isEqualTo("OK");
        assertThat(p1.at("/unrealizedPnl").asText()).isEqualTo("-5.00");
        assertThat(position(1, P2).at("/optionQuote/mark").asText()).isEqualTo("0.65");
        assertThat(position(accountB, P3).at("/optionQuote/mark").asText()).isEqualTo("4.10");
        JsonNode stock = ok(get("/api/stock-positions").header(AccountRef.HEADER, String.valueOf(accountB)))
                .at("/positions/0/quote");
        assertThat(stock.get("price").asText()).isEqualTo("61.10");
        assertThat(stock.get("source").asText()).isEqualTo("YAHOO");
    }

    @Test
    void ac2_oneRequestPerSymbolAndExpiration_sessionReused_closedExpirationNotRequested() throws Exception {
        run("2026-11-20T10:00:00");
        assertThat(requested()).containsExactly(
                "YAHOO HANDSHAKE null null",
                "YAHOO OPTIONS IXQZ null",
                "YAHOO OPTIONS QRTV 2026-12-18",
                "YAHOO OPTIONS QRTV 2027-02-19");
        at("2026-11-20T10:10:00");
        trade(1, "/api/option-trades/closes", "BTC", "2026-12-18", "45", "PUT", 1, "70.00", "2026-11-20T10:10");
        fake.clearRequests();
        run("2026-11-20T11:00:00");
        assertThat(requested()).containsExactly("YAHOO OPTIONS IXQZ null", "YAHOO OPTIONS QRTV 2027-02-19");
    }

    @Test
    void ac3_ac4_greeksComputedWithBlackScholes() throws Exception {
        run("2026-11-20T10:00:00");
        JsonNode g1 = position(1, P1).get("greeks");
        assertGreeks(g1, "30.00", "-0.4701", "47.01", "0.0530", "-5.30", "-0.0163", "1.63", "0.0995", "-9.95");
        assertThat(g1.get("source").asText()).isEqualTo("YAHOO");
        assertThat(g1.get("quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(g1.get("manualMarkNote").isNull()).isTrue();
        JsonNode g3 = position(accountB, P3).get("greeks");
        assertGreeks(g3, "30.00", "0.5299", "105.98", "0.0530", "10.61", "-0.0163", "-3.27", "0.0995", "19.89");
        assertGreeks(position(1, P2).get("greeks"), "35.00", "-0.1291", "12.91", "0.0432", "-4.32", "-0.0181", "1.81",
                "0.0293", "-2.93");
        JsonNode q = group(ok(get("/api/positions/greeks-summary").header(AccountRef.HEADER,
                String.valueOf(accountB))), "QRTV");
        assertThat(q.get("optionDelta").asText()).isEqualTo("105.98");
        assertThat(q.get("underlyingPrice").asText()).isEqualTo("50.00");
        Map<String, Object> row = jdbc.queryForMap("SELECT delta, theo, rho, quote_source FROM option_quote "
                + "WHERE expiration_date = DATE '2027-02-19' AND option_type = 'PUT'");
        assertThat((BigDecimal) row.get("delta")).isEqualByComparingTo("-0.47010736");
        assertThat(row.get("theo")).isNull();
        assertThat(row.get("rho")).isNull();
        assertThat(row.get("quote_source")).isEqualTo("YAHOO");
    }

    @Test
    void ac5_rateFivePercent_fromTheNextWrite() throws Exception {
        settings.set(null, new BigDecimal("5.00"));
        run("2026-11-20T10:00:00");
        JsonNode g = position(1, P1).get("greeks");
        assertThat(g.get("deltaPerContract").asText()).isEqualTo("-0.4371");
        assertThat(g.get("deltaPosition").asText()).isEqualTo("43.71");
        assertThat(ok(get("/api/market-data/status")).get("riskFreeRatePercent").asText()).isEqualTo("5.00");
    }

    @Test
    void ac6_afterCloseZeros_keepIntradayQuote_closeSnapshot_lastPriceFallback() throws Exception {
        run("2026-11-20T10:00:00");
        at("2026-11-20T15:00:00");
        trade(1, "/api/option-trades", "STO", "2027-02-19", "45", "PUT", 1, "150.00", "2026-11-20T15:00");
        dated("QRTV", FEB19, ok(chain("50.30", "2026-11-20T16:00:00", List.of(DEC18, FEB19),
                option("QRTV270219C00050000", "0", "0", "3.95", "0.00001"),
                option("QRTV270219P00050000", "0", "0", "3.95", "0.00001"),
                option("QRTV270219P00045000", "0", "0", "1.10", "0"))));
        apply();
        at("2026-11-20T16:20:00");
        QuoteFetchService.TickOutcome close = fetches.tick();
        assertThat(close.trigger()).isEqualTo(RunTrigger.CLOSE);

        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/optionQuote/mark").asText()).isEqualTo("4.00");
        assertThat(p1.at("/optionQuote/quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
        assertThat(p1.at("/optionQuote/fetchFailed").asBoolean()).isFalse();
        assertThat(p1.at("/optionQuote/staleLabel").isNull()).isTrue();
        assertThat(p1.at("/optionQuote/contractStatusLabel").isNull()).isTrue();
        assertThat(p1.at("/underlyingQuote/price").asText()).isEqualTo("50.30");
        assertGreeks(p1.get("greeks"), "30.00", "-0.4701", "47.01", "0.0530", "-5.30", "-0.0163", "1.63", "0.0995",
                "-9.95");
        Map<String, Object> snap = jdbc.queryForMap("SELECT bid, ask, quote_source FROM option_quote_close "
                + "WHERE snapshot_date = DATE '2026-11-20' AND expiration_date = DATE '2027-02-19' AND strike = 50 "
                + "AND option_type = 'PUT'");
        assertThat((BigDecimal) snap.get("bid")).isEqualByComparingTo("3.90");
        assertThat((BigDecimal) snap.get("ask")).isEqualByComparingTo("4.10");
        assertThat(snap.get("quote_source")).isEqualTo("YAHOO");

        JsonNode p4 = position(1, P4);
        assertThat(p4.at("/optionQuote/mark").asText()).isEqualTo("1.10");
        assertThat(p4.at("/optionQuote/markBasis").asText()).isEqualTo("LAST");
        assertThat(p4.at("/optionQuote/quoteTimeLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        JsonNode g4 = p4.get("greeks");
        assertThat(g4.get("status").asText()).isEqualTo("OK");
        assertThat(g4.get("ivPercent").asText()).isEqualTo("0.00");
        for (String f : List.of("deltaPerContract", "deltaPosition", "gammaPerContract", "thetaPerContract",
                "vegaPerContract", "vegaPosition")) {
            assertThat(g4.get(f).isNull()).as(f).isTrue();
        }
    }

    @Test
    void ac6_neverQuotedAndLastZero_noQuote() throws Exception {
        run("2026-11-20T10:00:00");
        at("2026-11-20T15:00:00");
        trade(1, "/api/option-trades", "STO", "2027-02-19", "45", "PUT", 1, "150.00", "2026-11-20T15:00");
        dated("QRTV", FEB19, ok(chain("50.30", "2026-11-20T16:00:00", List.of(DEC18, FEB19),
                option("QRTV270219P00045000", "0", "0", "0", "0"))));
        apply();
        run("2026-11-20T16:20:00");
        JsonNode p4 = position(1, P4);
        assertThat(p4.at("/optionQuote/mark").isNull()).isTrue();
        assertThat(p4.at("/optionQuote/contractStatus").asText()).isEqualTo("NEVER_QUOTED");
        assertThat(p4.at("/greeks/status").asText()).isEqualTo("NO_QUOTE");
        assertThat(p4.at("/greeks/source").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_quote WHERE strike = 45 "
                + "AND expiration_date = DATE '2027-02-19'", Integer.class)).isZero();
    }

    @Test
    void ac7_symbol403AfterReHandshake_finnhubFallback_keepsQuotes() throws Exception {
        run("2026-11-20T10:00:00");
        dated("QRTV", DEC18, Reply.of(403, "{}"));
        finnhub.put("QRTV", ok("{\"c\":50.20,\"pc\":49.60,\"t\":" + ny("2026-11-20T10:59:00").getEpochSecond() + "}"));
        apply();
        fake.clearRequests();
        List<QuoteFetchService.SymbolOutcome> out = run("2026-11-20T11:00:00");
        QuoteFetchService.SymbolOutcome q = out.stream().filter(o -> o.symbol().equals("QRTV")).findFirst().orElseThrow();
        assertThat(q.result().name()).isEqualTo("FALLBACK");
        assertThat(q.yahooResult().name()).isEqualTo("HTTP_ERROR");
        assertThat(q.cboeResult()).isNull();
        assertThat(q.failureDetail()).isEqualTo("Yahoo HTTP 403 (2026-12-18, after re-handshake); Finnhub SUCCESS");
        assertThat(requested()).containsExactly(
                "YAHOO OPTIONS IXQZ null",
                "YAHOO OPTIONS QRTV 2026-12-18",
                "YAHOO HANDSHAKE null null",
                "YAHOO OPTIONS QRTV 2026-12-18",
                "FINNHUB null QRTV null");
        for (String[] p : new String[][] {{"1", P1, "4.00"}, {"1", P2, "0.65"}, {String.valueOf(accountB), P3, "4.10"}}) {
            JsonNode row = position(Long.parseLong(p[0]), p[1]);
            assertThat(row.at("/optionQuote/mark").asText()).isEqualTo(p[2]);
            assertThat(row.at("/optionQuote/staleLabel").asText()).isEqualTo("行情未更新（上次：11-20 10:00）");
            assertThat(row.at("/underlyingQuote/price").asText()).isEqualTo("50.20");
            assertThat(row.at("/underlyingQuote/source").asText()).isEqualTo("FINNHUB");
        }
    }

    @Test
    void ac7_handshakeFailing_warningAfter90Minutes() throws Exception {
        run("2026-11-20T10:00:00");
        handshake = List.of(Reply.of(401, "{}"));
        apply();
        session.clear();
        fake.clearRequests();
        List<QuoteFetchService.SymbolOutcome> out = run("2026-11-20T11:00:00");
        assertThat(out).allSatisfy(o -> assertThat(o.yahooResult().name()).isEqualTo("HANDSHAKE_FAILED"));
        assertThat(out.get(0).failureDetail())
                .isEqualTo("Yahoo handshake failed (getcrumb HTTP 401); Finnhub fake: unreachable");
        assertThat(requested()).containsExactly("YAHOO HANDSHAKE null null", "FINNHUB null IXQZ null",
                "FINNHUB null QRTV null");
        at("2026-11-20T11:30:00");
        assertThat(ok(get("/api/market-data/status")).get("stale").asBoolean()).isFalse();
        at("2026-11-20T11:31:00");
        JsonNode s = ok(get("/api/market-data/status"));
        assertThat(s.get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-20 10:00 美东）");
    }

    @Test
    void reHandshakeThenSuccess_and429NotRetried() throws Exception {
        run("2026-11-20T10:00:00");
        dated("QRTV", DEC18, Reply.of(401, "{}"), ok(chain("50.00", "2026-11-20T11:00:00", List.of(DEC18, FEB19),
                option("QRTV261218P00045000", "0.60", "0.70", "0.62", "0.3500"))));
        apply();
        fake.clearRequests();
        assertThat(run("2026-11-20T11:00:00")).allSatisfy(o -> assertThat(o.result().name()).isEqualTo("SUCCESS"));
        assertThat(requested()).containsExactly("YAHOO OPTIONS IXQZ null", "YAHOO OPTIONS QRTV 2026-12-18",
                "YAHOO HANDSHAKE null null", "YAHOO OPTIONS QRTV 2026-12-18", "YAHOO OPTIONS QRTV 2027-02-19");

        dated("QRTV", DEC18, Reply.of(429, "{}"));
        apply();
        fake.clearRequests();
        QuoteFetchService.SymbolOutcome q = run("2026-11-20T12:00:00").stream()
                .filter(o -> o.symbol().equals("QRTV")).findFirst().orElseThrow();
        assertThat(q.yahooResult().name()).isEqualTo("HTTP_ERROR");
        assertThat(q.failureDetail()).startsWith("Yahoo HTTP 429 (2026-12-18); Finnhub ");
        assertThat(requested()).containsExactly("YAHOO OPTIONS IXQZ null", "YAHOO OPTIONS QRTV 2026-12-18",
                "FINNHUB null QRTV null");
        assertThat(position(1, P1).at("/optionQuote/fetchFailed").asBoolean()).as("whole symbol failed").isTrue();
    }

    @Test
    void ac8_contractOrExpirationMissingFromYahoo() throws Exception {
        run("2026-11-20T10:00:00");
        dated("QRTV", DEC18, ok(chain("50.00", "2026-11-20T11:00:00", List.of(DEC18, FEB19))));
        apply();
        run("2026-11-20T11:00:00");
        JsonNode p2 = position(1, P2).get("optionQuote");
        assertThat(p2.get("mark").asText()).isEqualTo("0.65");
        assertThat(p2.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
        assertThat(p2.get("contractStatusLabel").asText()).isEqualTo("数据源已无该合约（最后报价：11-20 10:00）");
        assertThat(p2.get("fetchFailed").asBoolean()).isFalse();
        // the expiration not in Yahoo's list: Yahoo answered another chain
        dated("QRTV", FEB19, ok(chain("50.00", "2026-11-20T12:00:00", List.of(DEC18),
                option("QRTV270219P00050000", "3.00", "3.20", "3.10", "0.3"))));
        apply();
        run("2026-11-20T12:00:00");
        JsonNode p1 = position(1, P1).get("optionQuote");
        assertThat(p1.get("mark").asText()).isEqualTo("4.00");
        assertThat(p1.get("contractStatus").asText()).isEqualTo("MISSING_FROM_SOURCE");
    }

    @Test
    void ac9_manualMark_greeksUnchanged_yahooNote() throws Exception {
        run("2026-11-20T10:00:00");
        at("2026-11-20T10:05:00");
        ok(put("/api/market-data/overrides/options").contentType(MediaType.APPLICATION_JSON).content(
                "{\"symbol\":\"QRTV\",\"expirationDate\":\"2027-02-19\",\"strike\":\"50\",\"optionType\":\"PUT\","
                        + "\"mark\":\"4.20\"}"));
        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/optionQuote/mark").asText()).isEqualTo("4.20");
        assertThat(p1.at("/optionQuote/manual").asBoolean()).isTrue();
        assertGreeks(p1.get("greeks"), "30.00", "-0.4701", "47.01", "0.0530", "-5.30", "-0.0163", "1.63", "0.0995",
                "-9.95");
        assertThat(p1.at("/greeks/manualMarkNote").asText())
                .isEqualTo("Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整");
        JsonNode s = ok(get("/api/market-data/status"));
        assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
        assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");
        // a later valid quote replaces the manual mark (Q148)
        run("2026-11-20T11:00:00");
        assertThat(position(1, P1).at("/optionQuote/manual").asBoolean()).isFalse();
    }

    @Test
    void ac10_storedCboeQuote_yahooUnreachable_bookkeepingWorks_thenCboeSource() throws Exception {
        jdbc.update("INSERT INTO option_quote (symbol, expiration_date, strike, option_type, occ_code, bid, ask, theo, "
                + "iv, delta, gamma, theta, vega, quote_time, fetched_at, quote_source) VALUES ('QRTV', DATE "
                + "'2027-02-19', 50, 'PUT', 'QRTV270219P00050000', 3.40, 3.60, 3.50, 0.31, -0.45, 0.05, -0.02, 0.10, "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', "
                + "'CBOE')");
        handshake = List.of(Reply.unreachableReply());
        apply();
        run("2026-11-20T10:00:00");
        JsonNode p1 = position(1, P1);
        assertThat(p1.at("/optionQuote/mark").asText()).isEqualTo("3.50");
        assertThat(p1.at("/optionQuote/source").asText()).isEqualTo("CBOE");
        assertThat(p1.at("/optionQuote/staleLabel").asText()).isEqualTo("行情未更新（从未成功）");
        assertThat(p1.at("/greeks/source").asText()).isEqualTo("CBOE");
        assertThat(p1.at("/greeks/deltaPerContract").asText()).isEqualTo("-0.4500");
        at("2026-11-20T10:05:00");
        trade(1, "/api/option-trades", "STO", "2027-02-19", "45", "PUT", 1, "150.00", "2026-11-20T10:05");

        settings.set(QuoteSource.CBOE, null);
        fake.clearRequests();
        run("2026-11-20T11:00:00");
        assertThat(fake.requests()).extracting(InMemoryQuoteTransport.Request::source).containsOnly("CBOE", "FINNHUB");
        assertThat(ok(get("/api/market-data/status")).get("optionSource").asText()).isEqualTo("CBOE");
    }

    // ---------------------------------------------------------------- helpers

    static void assertGreeks(JsonNode g, String iv, String d, String dp, String gm, String gp, String t, String tp,
                             String v, String vp) {
        assertThat(g.get("status").asText()).isEqualTo("OK");
        assertThat(List.of(g.get("ivPercent").asText(), g.get("deltaPerContract").asText(),
                g.get("deltaPosition").asText(), g.get("gammaPerContract").asText(), g.get("gammaPosition").asText(),
                g.get("thetaPerContract").asText(), g.get("thetaPosition").asText(), g.get("vegaPerContract").asText(),
                g.get("vegaPosition").asText())).containsExactly(iv, d, dp, gm, gp, t, tp, v, vp);
    }

    static JsonNode group(JsonNode s, String symbol) {
        for (JsonNode g : s.get("groups")) {
            if (g.get("symbol").asText().equals(symbol)) {
                return g;
            }
        }
        throw new AssertionError("no group " + symbol + " in " + s);
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
        n.put("occurredAtLocal", "2026-11-18T09:00");
        Res r = postJson(account, "/api/cash/transfers", n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    ObjectNode stock() {
        ObjectNode n = json.createObjectNode();
        n.put("type", "BUY");
        n.put("symbol", "IXQZ");
        n.put("shares", 100);
        n.put("amount", "6000.00");
        n.put("tradedAtLocal", "2026-11-19T09:45");
        return n;
    }

    void trade(long account, String url, String action, String exp, String strike, String type, int contracts,
               String net, String time) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "QRTV");
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
}
