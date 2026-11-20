package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * FEAT-003 acceptance criteria (backend part) through the real HTTP API and database.
 * "Now" defaults to 2026-11-11 10:00 New York (15:00Z); single tests move the clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class OptionPositionControllerTest {

    static final Instant DEFAULT_NOW = Instant.parse("2026-11-11T15:00:00Z");

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
        MovableClock positionsTestClock() {
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
        jdbc.update("DELETE FROM option_close_lot");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
    }

    // ---------------------------------------------------------------- data

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        postCreated("/api/cash/transfers", n);
    }

    private long trade(String action, String symbol, String exp, String strike, String type, int contracts,
                       int multiplier, String net, String nyTime, String note) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("multiplier", multiplier);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        n.put("note", note);
        return postCreated("/api/option-trades", n).get("id").asLong();
    }

    private JsonNode postCreated(String url, ObjectNode body) throws Exception {
        MvcResult r = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(201);
        return json.readTree(raw);
    }

    private final long[] t = new long[7];

    private void baseData() throws Exception {
        deposit("100000.00", "2026-10-26T10:00");
        t[1] = trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30", "第一笔 CSP");
        t[2] = trade("BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "512.65", "2026-11-03T10:30", null);
        t[3] = trade("STO", "F", "2026-12-18", "12.5", "PUT", 2, 100, "48.70", "2026-11-02T15:59", null);
        t[4] = trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00", "加仓");
        t[5] = trade("BTO", "MSFT", "2027-01-15", "450", "CALL", 1, 100, "900.00", "2026-11-03T23:30", null);
        t[6] = trade("STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-10-30T10:00", null);
    }

    // ---------------------------------------------------------------- reads

    private JsonNode getJson(String url) throws Exception {
        MvcResult r = mvc.perform(get(url)).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(200);
        return json.readTree(raw);
    }

    private JsonNode positions() throws Exception {
        return getJson("/api/option-positions");
    }

    private static List<String> labels(JsonNode resp) {
        List<String> out = new ArrayList<>();
        resp.get("positions").forEach(p -> out.add(p.get("contractLabel").asText()));
        return out;
    }

    /** "label direction contracts total average frozen dte lotCount". */
    private static String row(JsonNode p) {
        return String.join(" ", p.get("contractLabel").asText(), p.get("direction").asText(),
                p.get("contracts").asText(), p.get("openingNetTotal").asText(),
                p.get("averageNetPerContract").asText(), p.get("frozenCash").asText(), p.get("dte").asText(),
                p.get("lotCount").asText());
    }

    private static List<String> rows(JsonNode resp) {
        List<String> out = new ArrayList<>();
        resp.get("positions").forEach(p -> out.add(row(p)));
        return out;
    }

    private static JsonNode position(JsonNode resp, String label) {
        for (JsonNode p : resp.get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    /** "tradeId action tradedAtLocal remaining/original remainingNet remainingFrozen note". */
    private static List<String> lots(JsonNode position) {
        List<String> out = new ArrayList<>();
        position.get("lots").forEach(l -> out.add(String.join(" ", l.get("tradeId").asText(),
                l.get("action").asText(), l.get("tradedAtLocal").asText(),
                l.get("remainingContracts").asText() + "/" + l.get("originalContracts").asText(),
                l.get("remainingNetAmount").asText(), l.get("remainingFrozen").asText(),
                l.get("note").isNull() ? "-" : l.get("note").asText())));
        return out;
    }

    private String cashFrozen() throws Exception {
        return getJson("/api/cash/summary").get("frozenCash").asText();
    }

    // ---------------------------------------------------------------- AC 1-2

    @Test
    void ac1_positionsAndOrder() throws Exception {
        baseData();
        JsonNode r = positions();

        assertThat(r.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(r.get("positionCount").asInt()).isEqualTo(5);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("44500.00").isEqualTo(cashFrozen());
        assertThat(rows(r)).containsExactly(
                "AAPL 2026-12-18 200 P SHORT 2 709.35 354.68 40000.00 37 2",
                "AAPL 2026-12-18 210 C LONG 1 -512.65 -512.65 0.00 37 1",
                "F 2026-12-18 12.5 P SHORT 2 48.70 24.35 2500.00 37 1",
                "XYZ 2026-12-18 200 P SHORT 1 34.35 34.35 2000.00 37 1",
                "MSFT 2027-01-15 450 C LONG 1 -900.00 -900.00 0.00 65 1");

        JsonNode xyz = position(r, "XYZ 2026-12-18 200 P");
        assertThat(xyz.get("multiplier").asInt()).isEqualTo(10);
        assertThat(xyz.get("symbol").asText()).isEqualTo("XYZ");
        assertThat(xyz.get("expirationDate").asText()).isEqualTo("2026-12-18");
        assertThat(xyz.get("strike").asText()).isEqualTo("200");
        assertThat(xyz.get("optionType").asText()).isEqualTo("PUT");
        assertThat(xyz.get("expirationStatus").asText()).isEqualTo("ACTIVE");
        assertThat(xyz.get("daysPastExpiration").asInt()).isZero();
        assertThat(position(r, "F 2026-12-18 12.5 P").get("strike").asText()).isEqualTo("12.5");
        assertThat(xyz.get("openingNetTotal").isTextual()).isTrue();
    }

    @Test
    void ac2_lotsInFifoOrder() throws Exception {
        baseData();
        JsonNode r = positions();

        JsonNode aapl = position(r, "AAPL 2026-12-18 200 P");
        assertThat(lots(aapl)).containsExactly(
                t[1] + " STO 2026-11-03T10:30:00 1/1 349.35 20000.00 第一笔 CSP",
                t[4] + " STO 2026-11-04T11:00:00 1/1 360.00 20000.00 加仓");
        JsonNode lot1 = aapl.get("lots").get(0);
        assertThat(lot1.get("tradedAt").asText()).isEqualTo("2026-11-03T15:30:00Z");
        assertThat(lot1.get("originalNetAmount").asText()).isEqualTo("349.35");
        assertThat(lot1.get("originalFrozen").asText()).isEqualTo("20000.00");

        assertThat(lots(position(r, "F 2026-12-18 12.5 P")))
                .containsExactly(t[3] + " STO 2026-11-02T15:59:00 2/2 48.70 2500.00 -");
        assertThat(lots(position(r, "AAPL 2026-12-18 210 C")))
                .containsExactly(t[2] + " BTO 2026-11-03T10:30:00 1/1 -512.65 0.00 -");
        assertThat(lots(position(r, "MSFT 2027-01-15 450 C")).get(0)).contains("2026-11-03T23:30:00");
    }

    // ---------------------------------------------------------------- AC 3-4

    @Test
    void ac3_averageIsRoundedHalfUp() throws Exception {
        deposit("10000.00", "2026-10-26T10:00");
        trade("BTO", "KO", "2026-12-18", "60", "CALL", 3, 100, "200.00", "2026-11-02T10:00", null);
        JsonNode ko = position(positions(), "KO 2026-12-18 60 C");
        assertThat(ko.get("openingNetTotal").asText()).isEqualTo("-200.00");
        assertThat(ko.get("averageNetPerContract").asText()).isEqualTo("-66.67");

        trade("BTO", "PEP", "2026-12-18", "150", "CALL", 3, 100, "100.00", "2026-11-02T10:00", null);
        assertThat(position(positions(), "PEP 2026-12-18 150 C").get("averageNetPerContract").asText())
                .isEqualTo("-33.33");
    }

    @Test
    void ac4_frozenIsTheSumOfLots() throws Exception {
        deposit("100.00", "2026-10-26T10:00");
        trade("STO", "F", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-02T10:00", null);
        trade("STO", "F", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-03T10:00", null);

        JsonNode r = positions();
        assertThat(rows(r)).containsExactly("F 2026-12-18 12.345 P SHORT 2 1.00 0.50 24.70 37 2");
        assertThat(position(r, "F 2026-12-18 12.345 P").get("multiplier").asInt()).isEqualTo(1);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("24.70").isEqualTo(cashFrozen());
        position(r, "F 2026-12-18 12.345 P").get("lots")
                .forEach(l -> assertThat(l.get("remainingFrozen").asText()).isEqualTo("12.35"));
    }

    // ---------------------------------------------------------------- AC 5-6 DTE

    @Test
    void ac5_dteUsesNewYorkCalendarDate() throws Exception {
        baseData();
        // 2026-11-11 23:30 New York = 2026-11-12 04:30 UTC: still the 11th in New York
        clock.set(Instant.parse("2026-11-12T04:30:00Z"));
        JsonNode lateEvening = positions();
        assertThat(lateEvening.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(position(lateEvening, "AAPL 2026-12-18 200 P").get("dte").asLong()).isEqualTo(37);

        // 2026-11-12 00:30 New York
        clock.set(Instant.parse("2026-11-12T05:30:00Z"));
        JsonNode afterMidnight = positions();
        assertThat(afterMidnight.get("asOfDate").asText()).isEqualTo("2026-11-12");
        assertThat(position(afterMidnight, "AAPL 2026-12-18 200 P").get("dte").asLong()).isEqualTo(36);
        assertThat(position(afterMidnight, "MSFT 2027-01-15 450 C").get("dte").asLong()).isEqualTo(64);
    }

    @Test
    void ac6_expiresTodayAndExpiredUnprocessed() throws Exception {
        deposit("50000.00", "2026-10-26T10:00");
        trade("STO", "TSLA", "2026-11-06", "250", "PUT", 1, 100, "300.00", "2026-11-02T10:00", null);
        trade("BTO", "SPY", "2026-11-11", "600", "CALL", 1, 100, "50.00", "2026-11-02T10:00", null);

        JsonNode r = positions();
        assertThat(rows(r)).containsExactly(
                "TSLA 2026-11-06 250 P SHORT 1 300.00 300.00 25000.00 -5 1",
                "SPY 2026-11-11 600 C LONG 1 -50.00 -50.00 0.00 0 1");
        JsonNode tsla = position(r, "TSLA 2026-11-06 250 P");
        assertThat(tsla.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(tsla.get("daysPastExpiration").asInt()).isEqualTo(5);
        JsonNode spy = position(r, "SPY 2026-11-11 600 C");
        assertThat(spy.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(spy.get("daysPastExpiration").asInt()).isZero();
        assertThat(r.get("positionCount").asInt()).isEqualTo(2);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("25000.00").isEqualTo(cashFrozen());
    }

    // ---------------------------------------------------------------- AC 7, 9, 10

    @Test
    void ac7_newTradesAppear() throws Exception {
        baseData();
        long t7 = trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "340.00", "2026-11-05T10:00", null);
        JsonNode r = positions();
        assertThat(row(position(r, "AAPL 2026-12-18 200 P")))
                .isEqualTo("AAPL 2026-12-18 200 P SHORT 3 1049.35 349.78 60000.00 37 3");
        JsonNode lastLot = position(r, "AAPL 2026-12-18 200 P").get("lots").get(2);
        assertThat(lastLot.get("tradeId").asLong()).isEqualTo(t7);
        assertThat(r.get("positionCount").asInt()).isEqualTo(5);
        assertThat(r.get("frozenTotal").asText()).isEqualTo("64500.00").isEqualTo(cashFrozen());

        trade("BTO", "NVDA", "2026-12-18", "150", "CALL", 1, 100, "400.00", "2026-11-05T10:30", null);
        JsonNode r2 = positions();
        assertThat(labels(r2)).containsExactly("AAPL 2026-12-18 200 P", "AAPL 2026-12-18 210 C",
                "F 2026-12-18 12.5 P", "NVDA 2026-12-18 150 C", "XYZ 2026-12-18 200 P", "MSFT 2027-01-15 450 C");
        assertThat(position(r2, "NVDA 2026-12-18 150 C").get("dte").asLong()).isEqualTo(37);
        assertThat(position(r2, "NVDA 2026-12-18 150 C").get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(r2.get("positionCount").asInt()).isEqualTo(6);
    }

    @Test
    void backfilledOpenTakesItsFifoPlace() throws Exception {
        deposit("50000.00", "2026-10-26T10:00");
        long later = trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "1.00", "2026-11-04T10:00", null);
        long earlier = trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "1.00", "2026-11-02T10:00", null);
        JsonNode lotsNode = position(positions(), "AAPL 2026-12-18 200 P").get("lots");
        assertThat(lotsNode.get(0).get("tradeId").asLong()).isEqualTo(earlier);
        assertThat(lotsNode.get(1).get("tradeId").asLong()).isEqualTo(later);
    }

    @Test
    void ac9_noPositions() throws Exception {
        deposit("1000.00", "2026-10-26T10:00");
        JsonNode r = positions();
        assertThat(r.get("asOfDate").asText()).isEqualTo("2026-11-11");
        assertThat(r.get("positionCount").asInt()).isZero();
        assertThat(r.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(r.get("positions")).isEmpty();
    }

    @Test
    void ac10_readOnly() throws Exception {
        baseData();
        positions();
        positions();
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText()))
                .containsExactly("99379.75", "44500.00", "54879.75");
        assertThat(getJson("/api/option-trades").get("totalItems").asLong()).isEqualTo(6);
        assertThat(getJson("/api/cash/ledger").get("totalItems").asLong()).isEqualTo(7);
    }
}
