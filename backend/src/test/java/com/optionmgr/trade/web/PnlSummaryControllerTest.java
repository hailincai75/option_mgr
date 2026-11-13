package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/** FEAT-008 acceptance criteria (backend part). "Now" defaults to 2026-11-12 10:00 New York (15:00Z). */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class PnlSummaryControllerTest {

    static final Instant DEFAULT_NOW = Instant.parse("2026-11-12T15:00:00Z");

    static final class MovableClock extends Clock {
        private volatile Instant now = DEFAULT_NOW;

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
        MovableClock pnlSummaryTestClock() {
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

    @BeforeEach
    void setUp() {
        clock.set(DEFAULT_NOW);
        emptyDatabase();
    }

    @AfterEach
    void tearDown() {
        emptyDatabase();
        clock.set(DEFAULT_NOW);
    }

    void emptyDatabase() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, ObjectNode body, int expectedStatus) throws Exception {
        return call(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expectedStatus);
    }

    private JsonNode getJson(String url) throws Exception {
        return call(get(URI.create(url)), 200);
    }

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers"), n, 201);
    }

    private long trade(String action, String symbol, String exp, String strike, String type, int contracts,
                       Integer multiplier, String net, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        return send(post(url), n, 201).get("id").asLong();
    }

    private long o4;
    private long c4;

    private void octoberTrades() throws Exception {
        deposit("100000.00", "2026-10-26T10:00");
        trade("BTO", "TSLA", "2026-12-18", "300", "CALL", 1, 100, "500.00", "2026-10-27T10:00");
        trade("STO", "AAPL", "2026-11-20", "180", "PUT", 1, 100, "150.00", "2026-10-28T10:00");
        trade("STC", "TSLA", "2026-12-18", "300", "CALL", 1, null, "620.00", "2026-10-30T10:00");
        o4 = trade("BTC", "AAPL", "2026-11-20", "180", "PUT", 1, null, "200.00", "2026-10-31T23:30");
    }

    private void scenarioQ() throws Exception {
        octoberTrades();
        trade("STO", "AAPL", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-02T10:30");
        trade("BTO", "MSFT", "2027-01-15", "450", "CALL", 1, 100, "900.00", "2026-11-02T14:00");
        trade("STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-11-02T15:00");
        trade("BTO", "AAPL", "2026-12-18", "210", "CALL", 2, 100, "1000.00", "2026-11-03T09:45");
        trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-03T11:00");
        trade("BTC", "AAPL", "2026-12-18", "200", "PUT", 3, null, "400.00", "2026-11-05T10:00");
        trade("BTC", "XYZ", "2026-12-18", "200", "PUT", 1, null, "50.00", "2026-11-06T10:00");
        trade("STC", "MSFT", "2027-01-15", "450", "CALL", 1, null, "1250.00", "2026-11-06T23:30");
        c4 = trade("STC", "AAPL", "2026-12-18", "210", "CALL", 2, null, "640.00", "2026-11-07T10:00");
        JsonNode s = getJson("/api/cash/summary");
        assertThat(s.get("totalCash").asText()).isEqualTo("100703.05");
        assertThat(s.get("frozenCash").asText()).isEqualTo("0.00");
    }

    private static String pnl(JsonNode part) {
        return part.get("total").asText() + " (" + part.get("closedCount").asInt() + ")";
    }

    private static List<String> groups(JsonNode array, String key) {
        List<String> out = new ArrayList<>();
        array.forEach(g -> out.add(g.get(key).asText() + " " + g.get("closedCount").asInt() + " "
                + g.get("realizedPnl").asText()));
        return out;
    }

    private JsonNode summary(String query) throws Exception {
        return getJson("/api/pnl-summary" + (query.isEmpty() ? "" : "?" + query));
    }

    /** total of the summary and of /api/closed-trades for the same range must agree. */
    private void assertCrossCheck(String query) throws Exception {
        JsonNode s = summary(query);
        JsonNode c = getJson("/api/closed-trades" + (query.isEmpty() ? "" : "?" + query));
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo(c.get("filteredRealizedPnlTotal").asText());
        assertThat(s.get("closedCount").asLong()).isEqualTo(c.get("filteredCount").asLong());
        java.math.BigDecimal bySymbol = java.math.BigDecimal.ZERO;
        for (JsonNode g : s.get("bySymbol")) {
            bySymbol = bySymbol.add(new java.math.BigDecimal(g.get("realizedPnl").asText()));
        }
        java.math.BigDecimal byMonth = java.math.BigDecimal.ZERO;
        for (JsonNode g : s.get("byMonth")) {
            byMonth = byMonth.add(new java.math.BigDecimal(g.get("realizedPnl").asText()));
        }
        assertThat(bySymbol).isEqualByComparingTo(s.get("realizedPnlTotal").asText());
        assertThat(byMonth).isEqualByComparingTo(s.get("realizedPnlTotal").asText());
    }

    // ---------------------------------------------------------------- AC 1 dashboard

    @Test
    void ac1_dashboard() throws Exception {
        scenarioQ();
        trade("STO", "TSLA", "2026-11-06", "250", "PUT", 1, 100, "300.00", "2026-11-02T09:00");
        trade("BTO", "SPY", "2026-11-13", "600", "CALL", 1, 100, "80.00", "2026-11-09T10:00");
        trade("STO", "AAPL", "2026-11-20", "190", "PUT", 1, 100, "120.00", "2026-11-09T10:00");

        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-12");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(List.of(d.get("cash").get("totalCash").asText(), d.get("cash").get("frozenCash").asText(),
                d.get("cash").get("availableCash").asText())).containsExactly("101043.05", "44000.00", "57043.05");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("703.05 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("633.05 (4)");
        JsonNode p = d.get("positions");
        assertThat(p.get("positionCount").asInt()).isEqualTo(3);
        assertThat(p.get("frozenTotal").asText()).isEqualTo("44000.00");
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isEqualTo(1);
        assertThat(p.get("expiredUnprocessedCount").asInt()).isEqualTo(1);

        // cross-check with the other endpoints
        assertThat(p.get("frozenTotal").asText()).isEqualTo(getJson("/api/option-positions").get("frozenTotal").asText());
        assertThat(d.get("realizedPnl").get("total").asText())
                .isEqualTo(getJson("/api/closed-trades").get("filteredRealizedPnlTotal").asText());
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo(
                getJson("/api/closed-trades?startDate=2026-11-01&endDate=2026-11-12").get("filteredRealizedPnlTotal")
                        .asText());
    }

    @Test
    void expiringWindowIncludesTodayAndDay7() throws Exception {
        deposit("100000.00", "2026-10-26T10:00");
        trade("BTO", "SPY", "2026-11-12", "600", "CALL", 1, 100, "1.00", "2026-11-09T10:00");   // DTE 0
        trade("BTO", "QQQ", "2026-11-19", "500", "CALL", 1, 100, "1.00", "2026-11-09T10:00");   // DTE 7
        trade("BTO", "IWM", "2026-11-20", "200", "CALL", 1, 100, "1.00", "2026-11-09T10:00");   // DTE 8
        trade("BTO", "DIA", "2026-11-11", "400", "CALL", 1, 100, "1.00", "2026-11-09T10:00");   // DTE -1
        JsonNode p = getJson("/api/dashboard").get("positions");
        assertThat(p.get("positionCount").asInt()).isEqualTo(4);
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isEqualTo(2);
        assertThat(p.get("expiredUnprocessedCount").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC 2 month boundaries

    @Test
    void ac2_currentMonthFollowsNewYorkMidnight() throws Exception {
        scenarioQ();
        clock.set(Instant.parse("2026-12-01T04:30:00Z"));   // 2026-11-30 23:30 New York
        JsonNode late = getJson("/api/dashboard");
        assertThat(late.get("asOfDate").asText()).isEqualTo("2026-11-30");
        assertThat(late.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(pnl(late.get("currentMonthRealizedPnl"))).isEqualTo("633.05 (4)");

        clock.set(Instant.parse("2026-12-01T05:30:00Z"));   // 2026-12-01 00:30 New York
        JsonNode dec = getJson("/api/dashboard");
        assertThat(dec.get("currentMonth").asText()).isEqualTo("2026-12");
        assertThat(pnl(dec.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(dec.get("realizedPnl"))).isEqualTo("703.05 (6)");
    }

    @Test
    void ac2_lateOctoberCloseBelongsToOctober() throws Exception {
        clock.set(Instant.parse("2026-11-01T03:45:00Z"));   // 2026-10-31 23:45 New York
        octoberTrades();
        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-10");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("70.00 (2)");
    }

    // ---------------------------------------------------------------- AC 3-4 summary

    @Test
    void ac3_summaryAllTime() throws Exception {
        scenarioQ();
        JsonNode s = summary("");
        assertThat(s.get("startDate").isNull()).isTrue();
        assertThat(s.get("endDate").isNull()).isTrue();
        assertThat(s.get("closedCount").asInt()).isEqualTo(6);
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo("703.05");
        assertThat(groups(s.get("bySymbol"), "symbol")).containsExactly(
                "AAPL 3 248.70", "MSFT 1 350.00", "TSLA 1 120.00", "XYZ 1 -15.65");
        assertThat(groups(s.get("byMonth"), "month")).containsExactly("2026-11 4 633.05", "2026-10 2 70.00");
        assertCrossCheck("");
    }

    @Test
    void ac4_summaryDateRanges() throws Exception {
        scenarioQ();
        JsonNode r1 = summary("startDate=2026-11-01&endDate=2026-11-06");
        assertThat(r1.get("startDate").asText()).isEqualTo("2026-11-01");
        assertThat(r1.get("closedCount").asInt()).isEqualTo(3);
        assertThat(r1.get("realizedPnlTotal").asText()).isEqualTo("993.05");
        assertThat(groups(r1.get("bySymbol"), "symbol"))
                .containsExactly("AAPL 1 658.70", "MSFT 1 350.00", "XYZ 1 -15.65");
        assertThat(groups(r1.get("byMonth"), "month")).containsExactly("2026-11 3 993.05");

        JsonNode r2 = summary("startDate=2026-10-31&endDate=2026-10-31");
        assertThat(r2.get("realizedPnlTotal").asText()).isEqualTo("-50.00");
        assertThat(groups(r2.get("bySymbol"), "symbol")).containsExactly("AAPL 1 -50.00");
        assertThat(groups(r2.get("byMonth"), "month")).containsExactly("2026-10 1 -50.00");

        assertThat(groups(summary("startDate=2026-09-01&endDate=2026-11-30").get("byMonth"), "month"))
                .containsExactly("2026-11 4 633.05", "2026-10 2 70.00");

        JsonNode r4 = summary("startDate=2026-11-07");
        assertThat(r4.get("closedCount").asInt()).isEqualTo(1);
        assertThat(r4.get("realizedPnlTotal").asText()).isEqualTo("-360.00");

        JsonNode none = summary("startDate=2027-01-01");
        assertThat(none.get("closedCount").asInt()).isZero();
        assertThat(none.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(none.get("bySymbol")).isEmpty();
        assertThat(none.get("byMonth")).isEmpty();

        JsonNode err = call(get(URI.create("/api/pnl-summary?startDate=2026-11-07&endDate=2026-11-06")), 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
        JsonNode bad = call(get(URI.create("/api/pnl-summary?startDate=2026-02-30")), 400);
        assertThat(bad.get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(bad.get("details").get(0).get("field").asText()).isEqualTo("startDate");

        for (String q : List.of("startDate=2026-11-01&endDate=2026-11-06", "startDate=2026-10-31&endDate=2026-10-31",
                "startDate=2026-11-07", "endDate=2026-10-31", "startDate=2027-01-01")) {
            assertCrossCheck(q);
        }
    }

    // ---------------------------------------------------------------- AC 6-7

    @Test
    void ac6_editsDeletesAndDeposits() throws Exception {
        scenarioQ();
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 2);
        edit.put("netAmount", "700.00");
        edit.put("tradedAtLocal", "2026-11-07T10:00");
        send(put("/api/option-trades/" + c4), edit, 200);
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("763.05 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("693.05 (4)");
        JsonNode s = summary("");
        assertThat(groups(s.get("bySymbol"), "symbol")).first().isEqualTo("AAPL 3 308.70");
        assertThat(groups(s.get("byMonth"), "month")).first().isEqualTo("2026-11 4 693.05");
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo("763.05");

        call(delete("/api/option-trades/" + o4), 204);
        assertThat(pnl(getJson("/api/dashboard").get("realizedPnl"))).isEqualTo("813.05 (5)");
        JsonNode s2 = summary("");
        assertThat(groups(s2.get("bySymbol"), "symbol")).first().isEqualTo("AAPL 2 358.70");
        assertThat(groups(s2.get("byMonth"), "month")).containsExactly("2026-11 4 693.05", "2026-10 1 120.00");

        deposit("1000.00", "2026-11-12T09:00");
        JsonNode after = getJson("/api/dashboard");
        assertThat(pnl(after.get("realizedPnl"))).isEqualTo("813.05 (5)");
        assertCrossCheck("");
    }

    @Test
    void ac7_empty() throws Exception {
        deposit("30000.00", "2026-10-26T10:00");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        JsonNode p = d.get("positions");
        assertThat(p.get("positionCount").asInt()).isZero();
        assertThat(p.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(p.get("expiringWithin7DaysCount").asInt()).isZero();
        assertThat(p.get("expiredUnprocessedCount").asInt()).isZero();
        JsonNode s = summary("");
        assertThat(s.get("closedCount").asInt()).isZero();
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(s.get("bySymbol")).isEmpty();
        assertThat(s.get("byMonth")).isEmpty();
    }
}
