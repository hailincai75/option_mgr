package com.optionmgr.quote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.optionmgr.quote.domain.CboeChain;
import com.optionmgr.quote.domain.FinnhubQuote;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** FEAT-016: CBOE / Finnhub response parsing (exact decimals, timestamp in New York, malformed responses). */
class QuoteParsersTest {

    final QuoteParsers parsers = new QuoteParsers();

    static final String CBOE = """
            {"timestamp":"2026-11-23 09:45:00","data":{"symbol":"QRTV","current_price":52.40,"bid":52.38,"ask":52.42,
             "prev_day_close":51.9,"iv30":31.2,"last_trade_time":"2026-11-23T09:44:59","options":[
              {"option":"QRTV261218P00050000","bid":1.2,"ask":1.30,"theo":1.24,"iv":0.3512,"delta":-0.3021,
               "theta":-0.0456,"last_trade_price":1.22,"volume":120.0,"open_interest":3400},
              {"option":"bogus","bid":1},
              {"option":"QRTV261218C00060000","bid":"x","ask":null}
             ]}}""";

    @Test
    void cboe_exactDecimals_timestampIsNewYorkLocal() throws Exception {
        CboeChain c = parsers.parseCboe(CBOE);
        assertThat(c.timestamp()).isEqualTo(Instant.parse("2026-11-23T14:45:00Z"));
        assertThat(c.currentPrice()).hasToString("52.40");
        assertThat(c.prevClose()).isEqualByComparingTo("51.9");
        assertThat(c.options()).hasSize(2);
        var p = c.options().get(0);
        assertThat(p.bid()).hasToString("1.2");
        assertThat(p.iv()).hasToString("0.3512");
        assertThat(p.delta()).hasToString("-0.3021");
        assertThat(p.volume()).isEqualTo(120L);
        assertThat(p.openInterest()).isEqualTo(3400L);
        assertThat(c.options().get(1).bid()).isNull();
    }

    @Test
    void cboe_summerTimeAndIsoOffset() throws Exception {
        assertThat(QuoteParsers.timestamp(new com.fasterxml.jackson.databind.node.TextNode("2026-07-01 10:00:00")))
                .isEqualTo(Instant.parse("2026-07-01T14:00:00Z"));
        assertThat(QuoteParsers.timestamp(new com.fasterxml.jackson.databind.node.TextNode("2026-07-01T10:00:00-04:00")))
                .isEqualTo(Instant.parse("2026-07-01T14:00:00Z"));
    }

    @Test
    void cboe_malformed() {
        assertThatThrownBy(() -> parsers.parseCboe("<html>")).hasMessage("not JSON");
        assertThatThrownBy(() -> parsers.parseCboe("{\"timestamp\":\"2026-11-23 09:45:00\"}")).hasMessage("missing data object");
        assertThatThrownBy(() -> parsers.parseCboe("{\"timestamp\":\"2026-11-23 09:45:00\",\"data\":{}}"))
                .hasMessage("missing data.options array");
        assertThatThrownBy(() -> parsers.parseCboe("{\"data\":{\"options\":[]}}")).hasMessage("missing timestamp");
        assertThatThrownBy(() -> parsers.parseCboe("{\"timestamp\":\"yesterday\",\"data\":{\"options\":[]}}"))
                .hasMessage("invalid timestamp");
        assertThatThrownBy(() -> parsers.parseCboe("")).hasMessage("empty body");
    }

    @Test
    void finnhub() throws Exception {
        FinnhubQuote q = parsers.parseFinnhub("{\"c\":61.05,\"pc\":60.8,\"t\":1795445998}");
        assertThat(q.price()).hasToString("61.05");
        assertThat(q.time()).isEqualTo(Instant.parse("2026-11-23T14:59:58Z"));
        assertThat(parsers.parseFinnhub("{\"c\":61.05}").time()).isNull();
        assertThatThrownBy(() -> parsers.parseFinnhub("{\"c\":0,\"pc\":0,\"t\":0}")).hasMessage("no price");
    }

    // ---------------------------------------------------------------- FEAT-052 Yahoo

    static final String YAHOO = """
            {"optionChain":{"error":null,"result":[{"underlyingSymbol":"QRTV",
             "expirationDates":[1797552000,1802995200],"strikes":[45,50],
             "quote":{"symbol":"QRTV","regularMarketPrice":50.00,"regularMarketTime":1795186800,
                      "regularMarketPreviousClose":49.60,"bid":49.98,"ask":50.02,"marketState":"REGULAR"},
             "options":[{"expirationDate":1802995200,
               "calls":[{"contractSymbol":"QRTV270219C00050000","strike":50,"bid":4.00,"ask":4.20,"lastPrice":4.10,
                         "impliedVolatility":1.0E-5,"volume":12.5,"openInterest":-3}],
               "puts":[{"contractSymbol":"QRTV270219P00050000","bid":3.90,"ask":"4.10","lastPrice":3.95,
                        "impliedVolatility":0.3000,"volume":20,"openInterest":510.0},
                       {"contractSymbol":"bogus","bid":1},
                       {"contractSymbol":"QRTV270219P00045000","bid":"x","ask":null}]}]}]}}""";

    @Test
    void yahoo_quoteContractsAndExpirations() throws Exception {
        com.optionmgr.quote.domain.YahooChain c = parsers.parseYahoo(YAHOO);
        assertThat(c.time()).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));
        assertThat(c.price()).hasToString("50.00");
        assertThat(c.bid()).hasToString("49.98");
        assertThat(c.prevClose()).hasToString("49.60");
        assertThat(c.expirationDates()).containsExactly(java.time.LocalDate.parse("2026-12-18"),
                java.time.LocalDate.parse("2027-02-19"));
        assertThat(c.options()).hasSize(3);
        var call = c.options().get(0);
        assertThat(call.code().code()).isEqualTo("QRTV270219C00050000");
        assertThat(call.iv()).as("scientific notation").isEqualByComparingTo("0.00001");
        assertThat(call.volume()).as("non-integer volume").isNull();
        assertThat(call.openInterest()).as("negative OI").isNull();
        var put = c.options().get(1);
        assertThat(put.ask()).hasToString("4.10");
        assertThat(put.iv()).hasToString("0.3000");
        assertThat(put.openInterest()).isEqualTo(510L);
        var bad = c.options().get(2);
        assertThat(bad.bid()).isNull();
        assertThat(bad.ask()).isNull();
    }

    @Test
    void yahoo_invalidResponses() {
        assertThatThrownBy(() -> parsers.parseYahoo("<html>")).hasMessage("not JSON");
        assertThatThrownBy(() -> parsers.parseYahoo("{}")).hasMessage("missing optionChain object");
        assertThatThrownBy(() -> parsers.parseYahoo("{\"optionChain\":{\"result\":[],\"error\":null}}"))
                .hasMessage("empty optionChain.result");
        assertThatThrownBy(() -> parsers.parseYahoo(
                "{\"optionChain\":{\"result\":[{\"quote\":{}}],\"error\":{\"code\":\"Unauthorized\"}}}"))
                .hasMessage("optionChain.error");
        assertThatThrownBy(() -> parsers.parseYahoo("{\"optionChain\":{\"result\":[{\"options\":[]}]}}"))
                .hasMessage("missing quote object");
        assertThatThrownBy(() -> parsers.parseYahoo(
                "{\"optionChain\":{\"result\":[{\"quote\":{\"regularMarketPrice\":5,\"regularMarketTime\":0}}]}}"))
                .hasMessage("missing quote.regularMarketTime");
    }

    @Test
    void yahoo_stockOnlyResponseWithoutOptions() throws Exception {
        var c = parsers.parseYahoo("{\"optionChain\":{\"result\":[{\"quote\":{\"regularMarketPrice\":61.10,"
                + "\"regularMarketTime\":1795186800}}]}}");
        assertThat(c.price()).hasToString("61.10");
        assertThat(c.options()).isEmpty();
        assertThat(c.expirationDates()).isEmpty();
    }

    @Test
    void yahoo_crumb() {
        assertThat(QuoteParsers.yahooCrumb(" AbCdEfGhIjK\n")).contains("AbCdEfGhIjK");
        assertThat(QuoteParsers.yahooCrumb("a/b.c")).contains("a/b.c");
        assertThat(QuoteParsers.yahooCrumb("")).isEmpty();
        assertThat(QuoteParsers.yahooCrumb("<html>")).isEmpty();
        assertThat(QuoteParsers.yahooCrumb("{\"x\":1}")).isEmpty();
        assertThat(QuoteParsers.yahooCrumb("two words")).isEmpty();
        assertThat(QuoteParsers.yahooCrumb("x".repeat(65))).isEmpty();
        assertThat(QuoteParsers.yahooCrumb(null)).isEmpty();
    }
}
