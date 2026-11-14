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
}
