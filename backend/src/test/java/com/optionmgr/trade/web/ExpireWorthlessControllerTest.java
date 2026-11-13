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
import java.time.OffsetDateTime;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-012 acceptance criteria (backend part), scenario E. Fictional tickers only. The clock is set per test; most
 * tests run at 2026-11-23 10:00 New York (Monday after the Friday 2026-11-20 expiration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class ExpireWorthlessControllerTest {

    static final Instant MON_10AM = Instant.parse("2026-11-23T15:00:00Z");

    /** Clock the tests move. */
    static final class TestClock extends Clock {
        private volatile Instant now = MON_10AM;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        TestClock expireTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String EXPIRATIONS = "/api/option-trades/expirations";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private String account = "1";

    // scenario E trade ids
    private long e1;
    private long e2;
    private long e3;
    private long e4;
    private long e5;

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
        clock.set(MON_10AM);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    void cleanUp() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req.header("X-Account-Id", account)).andReturn();
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

    private long transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        return send(post("/api/cash/transfers"), n, 201).get("id").asLong();
    }

    private long deposit(String amount, String nyTime) throws Exception {
        return transfer("DEPOSIT", amount, nyTime);
    }

    private static boolean isClose(String action) {
        return action.equals("BTC") || action.equals("STC");
    }

    private ObjectNode trade(String action, String symbol, String exp, String strike, String type, int contracts,
                             String net, String nyTime) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return n;
    }

    private JsonNode ok(ObjectNode body) throws Exception {
        return send(post(isClose(body.get("action").asText()) ? TRADES + "/closes" : TRADES), body, 201);
    }

    private JsonNode rejected(ObjectNode body, int status, String code) throws Exception {
        JsonNode err = send(post(isClose(body.get("action").asText()) ? TRADES + "/closes" : TRADES), body, status);
        assertThat(err.get("code").asText()).isEqualTo(code);
        return err;
    }

    private ObjectNode expireBody(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    private JsonNode expire(String symbol, String strike, String type, int contracts) throws Exception {
        return send(post(EXPIRATIONS), expireBody(symbol, "2026-11-20", strike, type, contracts), 201);
    }

    private JsonNode expireRejected(ObjectNode body, String code, String message) throws Exception {
        JsonNode err = send(post(EXPIRATIONS), body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("message").asText()).isEqualTo(message);
        assertThat(err.get("details")).isEmpty();
        return err;
    }

    private ObjectNode edit(int contracts, String net, String nyTime, String note) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        n.put("note", note);
        return n;
    }

    /** PUT keeping the current contracts / net / time of the trade unless overridden. */
    private ObjectNode editOf(long id) throws Exception {
        JsonNode t = getJson(TRADES + "/" + id);
        return edit(t.get("contracts").asInt(), t.get("netAmount").asText(),
                t.get("tradedAtLocal").asText().substring(0, 16), t.get("note").isNull() ? null : t.get("note").asText());
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private JsonNode positions() throws Exception {
        return getJson("/api/option-positions");
    }

    private JsonNode position(String label) throws Exception {
        for (JsonNode p : positions().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    private JsonNode ledger() throws Exception {
        return getJson("/api/cash/ledger?size=100");
    }

    private int auditCount() throws Exception {
        return getJson("/api/audit-log?size=100").get("totalItems").asInt();
    }

    private static List<String> lots(JsonNode coveredLots) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : coveredLots) {
            out.add(l.get("tradedAtLocal").asText() + " " + l.get("action").asText() + " " + l.get("contracts").asInt()
                    + " " + l.get("netAmount").asText());
        }
        return out;
    }

    /** Scenario E: deposit 20,000.00 and E1-E5; summary 20,266.69 / 13,500.00 / 6,766.69, 6 ledger rows. */
    private void scenarioE() throws Exception {
        deposit("20000.00", "2026-11-02T10:00");
        e1 = ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 2, "240.00", "2026-11-03T10:00")).get("id").asLong();
        e2 = ok(trade("BTO", "QRTV", "2026-11-20", "30", "CALL", 1, "85.65", "2026-11-04T10:00")).get("id").asLong();
        e3 = ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "130.00", "2026-11-05T10:00")).get("id").asLong();
        e4 = ok(trade("STO", "IXQZ", "2026-11-20", "8", "CALL", 1, "22.34", "2026-11-06T10:00")).get("id").asLong();
        e5 = ok(trade("BTO", "QRTV", "2026-11-20", "25", "PUT", 2, "40.00", "2026-11-06T11:00")).get("id").asLong();
        assertSummary("20266.69", "13500.00", "6766.69");
        assertThat(ledger().get("totalItems").asInt()).isEqualTo(6);
    }

    /** Acceptance 2: expire XYZ (3 contracts). */
    private JsonNode expireXyz() throws Exception {
        return expire("XYZ", "45", "PUT", 3);
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_afterTheWeekendEveryPositionOnlyHasExpire() throws Exception {
        scenarioE();
        JsonNode ps = positions();
        List<String> labels = new ArrayList<>();
        for (JsonNode p : ps.get("positions")) {
            labels.add(p.get("contractLabel").asText());
            assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
            assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
            assertThat(p.get("closable").asBoolean()).isFalse();
            assertThat(p.get("expiryPending").asBoolean()).isFalse();
            assertThat(p.get("expirable").asBoolean()).isTrue();
            assertThat(p.get("expiryContracts").asInt()).isEqualTo(p.get("contracts").asInt());
            assertThat(p.get("expiryAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
            assertThat(p.get("expiryAtLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        }
        assertThat(labels).containsExactly("IXQZ 2026-11-20 8 C", "QRTV 2026-11-20 25 P", "QRTV 2026-11-20 30 C",
                "XYZ 2026-11-20 45 P");
        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(4);
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("13500.00");
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_shortPutWithTwoLotsReleasesFrozenCashAndKeepsThePremium() throws Exception {
        scenarioE();
        JsonNode xyz = position("XYZ 2026-11-20 45 P");
        assertThat(xyz.get("direction").asText()).isEqualTo("SHORT");
        assertThat(xyz.get("expiryContracts").asInt()).isEqualTo(3);
        assertThat(xyz.get("openingNetTotal").asText()).isEqualTo("370.00");
        assertThat(xyz.get("frozenCash").asText()).isEqualTo("13500.00");

        JsonNode x = expireXyz();
        assertThat(x.get("action").asText()).isEqualTo("EXP");
        assertThat(x.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(x.get("contracts").asInt()).isEqualTo(3);
        assertThat(x.get("multiplier").asInt()).isEqualTo(100);
        assertThat(x.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(x.get("cashChange").asText()).isEqualTo("0.00");
        assertThat(x.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(x.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(x.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        assertThat(x.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(x.get("note").isNull()).isTrue();
        assertThat(x.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(x.get("ledgerEntryId").isNumber()).isTrue();
        assertThat(lots(x.get("coveredLots"))).containsExactly("2026-11-03T10:00:00 STO 2 240.00",
                "2026-11-05T10:00:00 STO 1 130.00");
        assertThat(x.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(e1);
        assertThat(jdbc.queryForObject("SELECT traded_at FROM option_trade WHERE id = ?", OffsetDateTime.class,
                x.get("id").asLong()).toInstant()).isEqualTo(Instant.parse("2026-11-20T21:00:00Z"));

        assertSummary("20266.69", "0.00", "20266.69");
        assertThat(positions().get("positionCount").asInt()).isEqualTo(3);
        assertThat(position("XYZ 2026-11-20 45 P")).isNull();

        JsonNode led = ledger();
        assertThat(led.get("totalItems").asInt()).isEqualTo(7);
        JsonNode top = led.get("items").get(0);
        assertThat(top.get("id").asLong()).isEqualTo(x.get("ledgerEntryId").asLong());
        assertThat(top.get("occurredAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(top.get("sourceType").asText()).isEqualTo("OPTION_EXPIRE");
        assertThat(top.get("sourceId").asLong()).isEqualTo(x.get("id").asLong());
        assertThat(top.get("amount").asText()).isEqualTo("0.00");
        assertThat(top.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(top.get("balanceAfter").asText()).isEqualTo("20266.69");
        assertThat(top.get("description").asText()).isEqualTo("到期作废 3 张 XYZ 2026-11-20 45 P");
        assertThat(top.get("remark").isNull()).isTrue();
        assertThat(top.get("hasAuditLog").asBoolean()).isFalse();

        JsonNode list = getJson(TRADES);
        assertThat(list.get("totalItems").asInt()).isEqualTo(6);
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(x.get("id").asLong());
        assertThat(first.get("action").asText()).isEqualTo("EXP");
        assertThat(first.get("contractLabel").asText()).isEqualTo("XYZ 2026-11-20 45 P");
        assertThat(first.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(first.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(list.get("items").get(1).get("expiredDirection").isNull()).isTrue();
        assertThat(getJson(TRADES + "/" + x.get("id").asLong())).isEqualTo(x);

        JsonNode closed = getJson("/api/closed-trades");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(1);
        JsonNode c = closed.get("items").get(0);
        assertThat(c.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(c.get("action").asText()).isEqualTo("EXP");
        assertThat(c.get("closedDirection").asText()).isEqualTo("SHORT");
        assertThat(c.get("contracts").asInt()).isEqualTo(3);
        assertThat(c.get("openingNetTotal").asText()).isEqualTo("370.00");
        assertThat(c.get("openingNetPerContract").asText()).isEqualTo("123.33");
        assertThat(c.get("closeNetAmount").asText()).isEqualTo("0.00");
        assertThat(c.get("closeNetPerContract").asText()).isEqualTo("0.00");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(c.get("coveredLots")).hasSize(2);
        assertThat(c.get("hasAuditLog").asBoolean()).isFalse();

        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_longCallShortCallLongPutAndTheTotals() throws Exception {
        scenarioE();
        expireXyz();
        JsonNode longCall = expire("QRTV", "30", "CALL", 1);
        assertThat(longCall.get("realizedPnl").asText()).isEqualTo("-85.65");
        assertThat(longCall.get("expiredDirection").asText()).isEqualTo("LONG");
        assertThat(longCall.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(longCall.get("ledgerEntryId").isNull()).isTrue();
        JsonNode shortCall = expire("IXQZ", "8", "CALL", 1);
        assertThat(shortCall.get("realizedPnl").asText()).isEqualTo("22.34");
        assertThat(shortCall.get("expiredDirection").asText()).isEqualTo("SHORT");
        assertThat(shortCall.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(shortCall.get("ledgerEntryId").isNull()).isTrue();
        JsonNode longPut = expire("QRTV", "25", "PUT", 2);
        assertThat(longPut.get("realizedPnl").asText()).isEqualTo("-40.00");
        assertThat(lots(longPut.get("coveredLots"))).containsExactly("2026-11-06T11:00:00 BTO 2 -40.00");

        assertThat(ledger().get("totalItems").asInt()).isEqualTo(7);
        assertSummary("20266.69", "0.00", "20266.69");

        JsonNode closed = getJson("/api/closed-trades");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("266.69");
        List<String> rows = new ArrayList<>();
        for (JsonNode c : closed.get("items")) {
            rows.add(c.get("contractLabel").asText() + " " + c.get("realizedPnl").asText());
        }
        assertThat(rows).containsExactly("QRTV 2026-11-20 25 P -40.00", "IXQZ 2026-11-20 8 C 22.34",
                "QRTV 2026-11-20 30 C -85.65", "XYZ 2026-11-20 45 P 370.00");

        JsonNode pnl = getJson("/api/pnl-summary");
        assertThat(pnl.get("closedCount").asInt()).isEqualTo(4);
        assertThat(pnl.get("bySymbol").toString()).isEqualTo("[{\"symbol\":\"IXQZ\",\"closedCount\":1,\"realizedPnl\":\"22.34\"},"
                + "{\"symbol\":\"QRTV\",\"closedCount\":2,\"realizedPnl\":\"-125.65\"},"
                + "{\"symbol\":\"XYZ\",\"closedCount\":1,\"realizedPnl\":\"370.00\"}]");
        assertThat(pnl.get("byMonth").toString())
                .isEqualTo("[{\"month\":\"2026-11\",\"closedCount\":4,\"realizedPnl\":\"266.69\"}]");

        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("realizedPnl").toString()).isEqualTo("{\"closedCount\":4,\"total\":\"266.69\"}");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(d.get("currentMonthRealizedPnl").toString()).isEqualTo("{\"closedCount\":4,\"total\":\"266.69\"}");
        assertThat(d.get("positions").get("positionCount").asInt()).isZero();
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(d.get("positions").get("expiredUnprocessedCount").asInt()).isZero();

        assertThat(getJson(TRADES + "?action=EXP").get("totalItems").asInt()).isEqualTo(4);
        JsonNode sto = getJson(TRADES + "?action=STO").get("items");
        assertThat(List.of(sto.get(0).get("id").asLong(), sto.get(1).get("id").asLong(), sto.get(2).get("id").asLong()))
                .containsExactly(e4, e3, e1);
        assertThat(getJson(TRADES + "?action=EXP,STO").get("totalItems").asInt()).isEqualTo(7);
        JsonNode closedExp = getJson("/api/closed-trades?action=EXP");
        assertThat(closedExp.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(closedExp.get("filteredRealizedPnlTotal").asText()).isEqualTo("266.69");
        assertThat(getJson("/api/closed-trades?action=BTC").get("filteredCount").asInt()).isZero();
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_buttonsOnTheExpirationDayAt1600And1730() throws Exception {
        clock.set(Instant.parse("2026-11-17T15:00:00Z")); // 11-17 10:00
        scenarioE();
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(true, false, false);

        clock.set(Instant.parse("2026-11-20T20:59:00Z")); // 15:59
        p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(true, false, false);
        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_TOO_EARLY",
                "到期日（2026-11-20）美东 17:30 起才能作废");

        clock.set(Instant.parse("2026-11-20T21:00:00Z")); // 16:00
        p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(false, true, false);

        clock.set(Instant.parse("2026-11-20T22:29:00Z")); // 17:29
        p = position("XYZ 2026-11-20 45 P");
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(false, true, false);
        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_TOO_EARLY",
                "到期日（2026-11-20）美东 17:30 起才能作废");
        assertSummary("20266.69", "13500.00", "6766.69");
        assertThat(getJson(TRADES).get("totalItems").asInt()).isEqualTo(5);

        clock.set(Instant.parse("2026-11-21T14:00:00Z")); // Saturday 09:00
        p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(1);
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(false, false, true);

        clock.set(Instant.parse("2026-11-20T22:30:00Z")); // 17:30
        p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean())).containsExactly(false, false, true);
        JsonNode x = expireXyz();
        assertThat(x.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(x.get("realizedPnl").asText()).isEqualTo("370.00");
        assertSummary("20266.69", "0.00", "20266.69");
    }

    @Test
    void cutoffInDaylightSavingTimeIs2000Utc() throws Exception {
        clock.set(Instant.parse("2027-03-22T14:00:00Z"));
        deposit("10000.00", "2027-03-01T10:00");
        ok(trade("STO", "XYZ", "2027-03-19", "40", "PUT", 1, "50.00", "2027-03-02T10:00"));
        JsonNode p = position("XYZ 2027-03-19 40 P");
        assertThat(p.get("expiryAt").asText()).isEqualTo("2027-03-19T20:00:00Z");
        assertThat(p.get("expiryAtLocal").asText()).isEqualTo("2027-03-19T16:00:00");
        JsonNode x = send(post(EXPIRATIONS), expireBody("XYZ", "2027-03-19", "40", "PUT", 1), 201);
        assertThat(x.get("tradedAt").asText()).isEqualTo("2027-03-19T20:00:00Z");
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_lateClickStillBelongsToTheExpirationMonthAndDeletionIsReplayed() throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", "新账户");
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
        clock.set(Instant.parse("2026-12-01T15:00:00Z")); // 12-01 10:00
        deposit("5000.00", "2026-11-16T10:00");
        ok(trade("STO", "XYZ", "2026-11-27", "40", "PUT", 1, "95.00", "2026-11-17T10:00"));
        assertSummary("5095.00", "4000.00", "1095.00");

        ObjectNode w = json.createObjectNode();
        w.put("type", "WITHDRAWAL");
        w.put("amount", "1500.00");
        w.put("occurredAtLocal", "2026-11-30T10:00");
        assertThat(send(post("/api/cash/transfers"), w, 422).get("code").asText()).isEqualTo("CASH_INSUFFICIENT");

        JsonNode x = send(post(EXPIRATIONS), expireBody("XYZ", "2026-11-27", "40", "PUT", 1), 201);
        assertThat(x.get("tradedAt").asText()).isEqualTo("2026-11-27T21:00:00Z");
        assertThat(x.get("realizedPnl").asText()).isEqualTo("95.00");
        assertSummary("5095.00", "0.00", "5095.00");
        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-12");
        assertThat(d.get("currentMonthRealizedPnl").toString()).isEqualTo("{\"closedCount\":0,\"total\":\"0.00\"}");
        assertThat(d.get("realizedPnl").toString()).isEqualTo("{\"closedCount\":1,\"total\":\"95.00\"}");
        assertThat(getJson("/api/pnl-summary").get("byMonth").toString())
                .isEqualTo("[{\"month\":\"2026-11\",\"closedCount\":1,\"realizedPnl\":\"95.00\"}]");

        long withdrawal = send(post("/api/cash/transfers"), w, 201).get("id").asLong();
        assertSummary("3595.00", "0.00", "3595.00");
        List<String> types = new ArrayList<>();
        for (JsonNode row : ledger().get("items")) {
            types.add(row.get("sourceType").asText());
        }
        assertThat(types).containsExactly("WITHDRAWAL", "OPTION_EXPIRE", "OPTION_OPEN", "DEPOSIT");

        JsonNode err = call(delete(TRADES + "/" + x.get("id").asLong()), 422);
        assertThat(err.get("code").asText()).isEqualTo("CASH_INSUFFICIENT");
        assertThat(err.get("message").asText()).isEqualTo("可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00");
        assertSummary("3595.00", "0.00", "3595.00");
        assertThat(auditCount()).isEqualTo(1); // only the account creation

        call(delete("/api/cash/transfers/" + withdrawal), 204);
        call(delete(TRADES + "/" + x.get("id").asLong()), 204);
        assertSummary("5095.00", "4000.00", "1095.00");
        JsonNode p = position("XYZ 2026-11-27 40 P");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_partialCloseThenExpireTheRest() throws Exception {
        scenarioE();
        JsonNode btc = ok(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "60.00", "2026-11-18T10:00"));
        assertThat(btc.get("realizedPnl").asText()).isEqualTo("180.00");
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(p.get("lotCount").asInt()).isEqualTo(1);
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(1);

        JsonNode x = expire("XYZ", "45", "PUT", 1);
        assertThat(x.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(x.get("frozenChange").asText()).isEqualTo("-4500.00");
        assertThat(x.get("coveredLots")).hasSize(1);
        assertThat(x.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(e3);
        assertSummary("20206.69", "0.00", "20206.69");
        JsonNode closed = getJson("/api/closed-trades?symbol=XYZ").get("items");
        assertThat(closed).hasSize(2);
        assertThat(closed.get(0).get("action").asText() + " " + closed.get(0).get("realizedPnl").asText())
                .isEqualTo("EXP 130.00");
        assertThat(closed.get(1).get("action").asText() + " " + closed.get(1).get("realizedPnl").asText())
                .isEqualTo("BTC 180.00");
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void ac7_deleteTheExpiryThenExpireAgain() throws Exception {
        scenarioE();
        long id = expireXyz().get("id").asLong();
        call(delete(TRADES + "/" + id), 204);
        assertSummary("20266.69", "13500.00", "6766.69");
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(3);
        assertThat(p.get("lotCount").asInt()).isEqualTo(2);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("370.00");
        assertThat(p.get("frozenCash").asText()).isEqualTo("13500.00");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
        assertThat(ledger().get("totalItems").asInt()).isEqualTo(6);
        assertThat(getJson(TRADES).get("totalItems").asInt()).isEqualTo(5);
        call(get(URI.create(TRADES + "/" + id)), 404);

        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("DELETE");
        assertThat(audit.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(id);
        assertThat(audit.get("summary").asText()).isEqualTo("到期作废 XYZ 2026-11-20 45 P 3 张");
        assertThat(audit.get("after").isNull()).isTrue();
        assertThat(audit.get("changes")).isEmpty();
        assertThat(audit.get("sideEffects")).isEmpty();
        JsonNode before = audit.get("before");
        assertThat(before.get("action").asText()).isEqualTo("EXP");
        assertThat(before.get("contracts").asInt()).isEqualTo(3);
        assertThat(before.get("multiplier").asInt()).isEqualTo(100);
        assertThat(before.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(before.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(before.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        assertThat(before.get("cashChange").asText()).isEqualTo("0.00");
        assertThat(before.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(before.get("note").isNull()).isTrue();

        JsonNode again = expireXyz();
        assertThat(again.get("id").asLong()).isNotEqualTo(id);
        assertThat(again.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
        assertSummary("20266.69", "0.00", "20266.69");
    }

    // ---------------------------------------------------------------- AC 8

    @Test
    void ac8_editTheNetOfAnExpiredLot() throws Exception {
        scenarioE();
        long x = expireXyz().get("id").asLong();
        ObjectNode body = editOf(e3);
        body.put("netAmount", "135.00");
        send(put(TRADES + "/" + e3), body, 200);
        assertThat(getJson(TRADES + "/" + x).get("realizedPnl").asText()).isEqualTo("375.00");
        assertSummary("20271.69", "0.00", "20271.69");
    }

    @Test
    void ac8_deletingOrShrinkingAnExpiredLotIsRejected() throws Exception {
        scenarioE();
        expireXyz();
        JsonNode del = call(delete(TRADES + "/" + e3), 422);
        assertThat(del.get("code").asText()).isEqualTo("CLOSE_EXCEEDS_POSITION");
        assertThat(del.get("message").asText())
                .isEqualTo("该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（删除后作废将超过当时持仓 2 张），请先删除该到期作废");

        ObjectNode body = editOf(e1);
        body.put("contracts", 1);
        JsonNode shrink = send(put(TRADES + "/" + e1), body, 422);
        assertThat(shrink.get("code").asText()).isEqualTo("CLOSE_EXCEEDS_POSITION");
        assertThat(shrink.get("message").asText())
                .isEqualTo("该开仓被 2026-11-20 16:00 的到期作废 3 张覆盖（修改后作废将超过当时持仓 2 张），请先删除该到期作废");

        ObjectNode mult = editOf(e1);
        mult.put("multiplier", 10);
        JsonNode m = send(put(TRADES + "/" + e1), mult, 422);
        assertThat(m.get("code").asText()).isEqualTo("MULTIPLIER_MISMATCH");

        assertSummary("20266.69", "0.00", "20266.69");
        assertThat(auditCount()).isZero();
    }

    @Test
    void ac8_backfilledLaterOpenStaysAPositionAndCanBeExpiredAgain() throws Exception {
        scenarioE();
        JsonNode first = expireXyz();
        ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "110.00", "2026-11-19T10:00"));
        JsonNode x1 = getJson(TRADES + "/" + first.get("id").asLong());
        assertThat(x1.get("realizedPnl").asText()).isEqualTo("370.00");
        assertThat(x1.get("coveredLots")).hasSize(2);
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("4500.00");
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(p.get("expirable").asBoolean()).isTrue();
        assertSummary("20376.69", "4500.00", "15876.69");

        JsonNode second = expire("XYZ", "45", "PUT", 1);
        assertThat(second.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(second.get("realizedPnl").asText()).isEqualTo("110.00");
        assertThat(second.get("frozenChange").asText()).isEqualTo("-4500.00");
        assertSummary("20376.69", "0.00", "20376.69");
        JsonNode exps = getJson(TRADES + "?action=EXP&symbol=XYZ").get("items");
        assertThat(exps).hasSize(2);
        assertThat(exps.get(0).get("id").asLong()).isEqualTo(second.get("id").asLong());

        // deleting the earlier expiry would leave the later one not covering whole lots
        JsonNode err = call(delete(TRADES + "/" + first.get("id").asLong()), 422);
        assertThat(err.get("code").asText()).isEqualTo("CLOSE_NOT_WHOLE_LOTS");
        assertThat(err.get("message").asText()).isEqualTo("删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张"
                + "将不是整笔批次（最早一笔是 2 张，可为 2 张、3 张或 4 张），请先删除该到期作废");
        call(delete(TRADES + "/" + second.get("id").asLong()), 204);
        call(delete(TRADES + "/" + first.get("id").asLong()), 204);
        assertSummary("20376.69", "18000.00", "2376.69");
    }

    @Test
    void ac8_backfillsThatBreakTheExpiryAreRejected() throws Exception {
        scenarioE();
        long x = expireXyz().get("id").asLong();
        JsonNode early = rejected(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 2, "200.00", "2026-11-02T11:00"), 422,
                "CLOSE_NOT_WHOLE_LOTS");
        assertThat(early.get("message").asText()).isEqualTo("补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张"
                + "将不是整笔批次（最早一笔是 2 张，可为 2 张、4 张或 5 张），请先删除该到期作废");

        JsonNode close = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "50.00", "2026-11-19T10:00"), 422,
                "CLOSE_EXCEEDS_POSITION");
        assertThat(close.get("message").asText()).isEqualTo("补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张"
                + "将超过当时持仓 1 张，请先删除该到期作废");

        JsonNode after = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 3, "50.00", "2026-11-23T09:00"), 400,
                "VALIDATION_ERROR");
        assertThat(after.get("details").get(0).get("message").asText()).isEqualTo("到期日（2026-11-20）之后不能平仓");

        JsonNode editExp = send(put(TRADES + "/" + x), edit(3, "1.00", "2026-11-20T16:00", null), 422);
        assertThat(editExp.get("code").asText()).isEqualTo("EXPIRY_NOT_EDITABLE");
        assertThat(editExp.get("message").asText()).isEqualTo("到期作废不能修改，记错请删除后重新作废");

        assertSummary("20266.69", "0.00", "20266.69");
        assertThat(getJson(TRADES).get("totalItems").asInt()).isEqualTo(6);
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 9

    @Test
    void ac9_closesUntil1600AreAllowed() throws Exception {
        scenarioE();
        JsonNode c = ok(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "30.00", "2026-11-20T15:59"));
        assertThat(c.get("realizedPnl").asText()).isEqualTo("210.00");
        assertThat(c.get("frozenChange").asText()).isEqualTo("-9000.00");
        call(delete(TRADES + "/" + c.get("id").asLong()), 204);
        JsonNode at1600 = ok(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "30.00", "2026-11-20T16:00"));
        assertThat(at1600.get("realizedPnl").asText()).isEqualTo("210.00");
    }

    @Test
    void ac9_tradesAfter1600OnTheExpirationDateAreRejected() throws Exception {
        scenarioE();
        String msg = "成交时间不能晚于到期日（2026-11-20）美东 16:00";
        JsonNode close = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "30.00", "2026-11-20T16:01"), 400,
                "VALIDATION_ERROR");
        assertThat(close.get("details").toString())
                .isEqualTo("[{\"field\":\"tradedAtLocal\",\"message\":\"" + msg + "\"}]");
        JsonNode closeSecond = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "30.00",
                "2026-11-20T16:00:01"), 400, "VALIDATION_ERROR");
        assertThat(closeSecond.get("details").get(0).get("message").asText()).isEqualTo(msg);
        JsonNode open = rejected(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "50.00", "2026-11-20T16:01"), 400,
                "VALIDATION_ERROR");
        assertThat(open.get("details").toString())
                .isEqualTo("[{\"field\":\"tradedAtLocal\",\"message\":\"" + msg + "\"}]");
        JsonNode nextDay = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 3, "30.00", "2026-11-21T10:00"), 400,
                "VALIDATION_ERROR");
        assertThat(nextDay.get("details").toString()).isEqualTo(
                "[{\"field\":\"tradedAtLocal\",\"message\":\"到期日（2026-11-20）之后不能平仓\"}]");
        assertSummary("20266.69", "13500.00", "6766.69");

        JsonNode at1600 = ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "50.00", "2026-11-20T16:00"));
        assertThat(at1600.get("frozenChange").asText()).isEqualTo("4500.00");
    }

    @Test
    void ac9_editingTheTradeTimeAfter1600IsRejected() throws Exception {
        scenarioE();
        ObjectNode late = editOf(e3);
        late.put("tradedAtLocal", "2026-11-20T16:01");
        JsonNode err = send(put(TRADES + "/" + e3), late, 400);
        assertThat(err.get("details").toString())
                .isEqualTo("[{\"field\":\"tradedAtLocal\",\"message\":\"成交时间不能晚于到期日（2026-11-20）美东 16:00\"}]");
        assertThat(auditCount()).isZero();
        ObjectNode at1600 = editOf(e3);
        at1600.put("tradedAtLocal", "2026-11-20T16:00");
        assertThat(send(put(TRADES + "/" + e3), at1600, 200).get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(auditCount()).isEqualTo(1);
        // the lot at exactly 16:00 was entered before the expiry, so the expiry covers it
        JsonNode x = expireXyz();
        assertThat(x.get("coveredLots")).hasSize(2);
    }

    @Test
    void ac9_closeAt1600EnteredAfterTheExpiryHasNoPosition() throws Exception {
        scenarioE();
        expireXyz();
        JsonNode err = rejected(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 1, "10.00", "2026-11-20T16:00"), 422,
                "NO_POSITION_TO_CLOSE");
        assertThat(err.get("message").asText()).isEqualTo("该合约（XYZ 2026-11-20 45 P）在成交时没有持仓，无法平仓");
        // an open at 16:00 entered after the expiry becomes a new lot that can be expired again
        ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "50.00", "2026-11-20T16:00"));
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(1);
        assertThat(expire("XYZ", "45", "PUT", 1).get("realizedPnl").asText()).isEqualTo("50.00");
        assertSummary("20316.69", "0.00", "20316.69");
    }

    /** A close stored before FEAT-012 at 16:10 on the expiration date (written around the validation). */
    private long legacyLateClose() throws Exception {
        long id = ok(trade("BTC", "XYZ", "2026-11-20", "45", "PUT", 2, "30.00", "2026-11-20T15:50")).get("id").asLong();
        OffsetDateTime late = OffsetDateTime.parse("2026-11-20T21:10:00Z");
        jdbc.update("UPDATE option_trade SET traded_at = ? WHERE id = ?", late, id);
        jdbc.update("UPDATE cash_ledger_entry SET occurred_at = ? WHERE source_type = 'OPTION_CLOSE' AND source_id = ?",
                late, id);
        return id;
    }

    @Test
    void ac9_legacyTradeAfter1600BlocksTheExpiryUntilItsTimeIsChanged() throws Exception {
        scenarioE();
        long late = legacyLateClose();
        assertThat(getJson(TRADES + "/" + late).get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:10:00");
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(e3);
        assertThat(p.get("expirable").asBoolean()).isTrue();
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(3);
        // other trades are not rejected because of it
        ObjectNode e2Edit = editOf(e2);
        e2Edit.put("note", "备注");
        send(put(TRADES + "/" + e2), e2Edit, 200);
        ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "10.00", "2026-11-19T10:00"));
        long backfill = getJson(TRADES + "?action=STO&symbol=XYZ").get("items").get(0).get("id").asLong();
        call(delete(TRADES + "/" + backfill), 204);

        String blocked = "2026-11-20 16:10 的 BTC 2 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它";
        // the page sends expiryContracts (3), which passes the contracts check and hits the late trade
        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 3), "EXPIRY_BLOCKED_BY_LATE_TRADE", blocked);
        // the contracts are checked first: 1 (the current position) is not the 16:00 position
        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 1), "POSITION_CHANGED",
                "持仓已变化（当前 3 张），请刷新后重试");

        ObjectNode fix = editOf(late);
        fix.put("tradedAtLocal", "2026-11-20T15:50");
        send(put(TRADES + "/" + late), fix, 200);
        JsonNode x = expire("XYZ", "45", "PUT", 1);
        assertThat(x.get("contracts").asInt()).isEqualTo(1);
        assertThat(x.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(x.get("frozenChange").asText()).isEqualTo("-4500.00");
        assertThat(x.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(e3);
    }

    @Test
    void ac9_legacyTradeAfter1600CanBeEditedWithoutChangingItsTime() throws Exception {
        scenarioE();
        long late = legacyLateClose();
        ObjectNode note = editOf(late);
        note.put("note", "补记");
        JsonNode t = send(put(TRADES + "/" + late), note, 200);
        assertThat(t.get("note").asText()).isEqualTo("补记");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:10:00");
        assertThat(t.get("hasAuditLog").asBoolean()).isTrue();
        assertThat(auditCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC 10

    @Test
    void ac10_accountsAreIsolated() throws Exception {
        scenarioE();
        ObjectNode n = json.createObjectNode();
        n.put("name", "小账户");
        String small = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
        account = small;
        deposit("5000.00", "2026-11-02T10:00");
        ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "100.00", "2026-11-04T10:00"));
        assertSummary("5100.00", "4500.00", "600.00");

        account = "1";
        long defaultExpiry = expireXyz().get("id").asLong();
        account = small;
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(p.get("expirable").asBoolean()).isTrue();
        assertSummary("5100.00", "4500.00", "600.00");
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
        assertThat(getJson("/api/dashboard").get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(1);

        assertThat(expire("XYZ", "45", "PUT", 1).get("realizedPnl").asText()).isEqualTo("100.00");
        assertSummary("5100.00", "0.00", "5100.00");
        assertThat(call(delete(TRADES + "/" + defaultExpiry), 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(get(URI.create(TRADES + "/" + defaultExpiry)), 404).get("code").asText()).isEqualTo("NOT_FOUND");

        account = "1";
        assertSummary("20266.69", "0.00", "20266.69");
    }

    // ---------------------------------------------------------------- AC 11

    @Test
    void ac11_frozenReleaseIsTheSumOfTheLotsAndItsCorrectionIsAudited() throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", "新账户");
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
        deposit("100.00", "2026-11-02T10:00");
        ObjectNode g = trade("STO", "QFZ", "2026-11-20", "12.345", "PUT", 1, "0.50", "2026-11-03T10:00");
        g.put("multiplier", 1);
        long g1 = ok(g).get("id").asLong();
        g.put("tradedAtLocal", "2026-11-04T10:00");
        long g2 = ok(g).get("id").asLong();
        assertSummary("101.00", "24.70", "76.30");

        JsonNode x = expire("QFZ", "12.345", "PUT", 2);
        long xid = x.get("id").asLong();
        assertThat(x.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(x.get("realizedPnl").asText()).isEqualTo("1.00");
        assertThat(x.get("multiplier").asInt()).isEqualTo(1);
        assertSummary("101.00", "0.00", "101.00");
        assertThat(ledger().get("items").get(0).get("description").asText())
                .isEqualTo("到期作废 2 张 QFZ 2026-11-20 12.345 P（multiplier 1）");

        ObjectNode bigger = editOf(g1);
        bigger.put("contracts", 2);
        JsonNode edited = send(put(TRADES + "/" + g1), bigger, 200);
        assertThat(edited.get("frozenChange").asText()).isEqualTo("24.69");
        JsonNode after = getJson(TRADES + "/" + xid);
        assertThat(after.get("contracts").asInt()).isEqualTo(2);
        assertThat(after.get("frozenChange").asText()).isEqualTo("-24.69");
        assertThat(after.get("realizedPnl").asText()).isEqualTo("0.50");
        assertThat(after.get("coveredLots")).hasSize(1);
        assertThat(after.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(g1);
        JsonNode p = position("QFZ 2026-11-20 12.345 P");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("frozenCash").asText()).isEqualTo("12.35");
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(g2);
        assertThat(p.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertSummary("101.00", "12.35", "88.65");

        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        List<String> changes = new ArrayList<>();
        audit.get("changes").forEach(c -> changes.add(c.get("text").asText()));
        assertThat(changes).contains("张数：1 → 2", "冻结变动：+12.35 → +24.69");
        assertThat(audit.get("sideEffects")).hasSize(1);
        assertThat(audit.get("sideEffects").get(0).get("action").asText()).isEqualTo("EXP");
        assertThat(audit.get("sideEffects").get(0).get("text").asText())
                .isEqualTo("到期作废 #" + xid + " 冻结变动 −24.70 → −24.69");
    }

    // ---------------------------------------------------------------- AC 12

    @Test
    void ac12_noPositionRepeatedExpiryAndAChangedPosition() throws Exception {
        scenarioE();
        expireRejected(expireBody("XYZ", "2026-11-20", "50", "PUT", 1), "NO_POSITION_TO_EXPIRE",
                "该合约（XYZ 2026-11-20 50 P）没有持仓，无法作废");

        ok(trade("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "110.00", "2026-11-19T10:00"));
        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 3), "POSITION_CHANGED",
                "持仓已变化（当前 4 张），请刷新后重试");
        assertThat(position("XYZ 2026-11-20 45 P").get("expiryContracts").asInt()).isEqualTo(4);
        JsonNode x = expire("XYZ", "45", "PUT", 4);
        assertThat(x.get("realizedPnl").asText()).isEqualTo("480.00");
        assertThat(x.get("frozenChange").asText()).isEqualTo("-18000.00");

        expireRejected(expireBody("XYZ", "2026-11-20", "45", "PUT", 4), "NO_POSITION_TO_EXPIRE",
                "该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
        assertThat(getJson(TRADES + "?action=EXP").get("totalItems").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- request validation

    @Test
    void expireRequestValidationAndIgnoredProperties() throws Exception {
        scenarioE();
        ObjectNode bad = expireBody(" xyz ", "2026-11-20", "45.1234", "PUT", -1);
        JsonNode err = send(post(EXPIRATIONS), bad, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").toString()).contains("\"field\":\"contracts\",\"message\":\"张数必须是非负整数\"")
                .contains("\"field\":\"strike\",\"message\":\"行权价最多 3 位小数\"");
        ObjectNode missing = json.createObjectNode();
        JsonNode m = send(post(EXPIRATIONS), missing, 400);
        assertThat(m.get("details").toString()).contains("张数不能为空").contains("标的不能为空").contains("到期日不能为空");

        // normalized contract, extra properties ignored (no note, no own time / net)
        ObjectNode extra = expireBody(" xyz ", "2026-11-20", "45.000", "PUT", 3);
        extra.put("note", "不能填备注");
        extra.put("tradedAtLocal", "2026-11-23T09:00");
        extra.put("netAmount", "5.00");
        JsonNode x = send(post(EXPIRATIONS), extra, 201);
        assertThat(x.get("note").isNull()).isTrue();
        assertThat(x.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(x.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(x.get("strike").asText()).isEqualTo("45");

        // the open / close endpoints do not take EXP
        assertThat(rejected(trade("EXP", "XYZ", "2026-11-20", "45", "PUT", 1, "1.00", "2026-11-19T10:00"), 400,
                "VALIDATION_ERROR").get("details").get(0).get("field").asText()).isEqualTo("action");
        ObjectNode closeExp = trade("EXP", "XYZ", "2026-11-20", "45", "PUT", 1, "1.00", "2026-11-19T10:00");
        JsonNode ce = send(post(TRADES + "/closes"), closeExp, 400);
        assertThat(ce.get("details").get(0).get("message").asText())
                .isEqualTo("本功能只支持平仓（BTC / STC），开仓请使用开仓功能");
    }
}
