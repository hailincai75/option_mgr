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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-014 acceptance criteria (backend part), scenario E. Fictional tickers only. The clock is set per test (noted
 * on each); 2026-11-20 is a Friday, 2026-11-23 the Monday after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class ExerciseControllerTest {

    static final Instant TUE_17_10AM = Instant.parse("2026-11-17T15:00:00Z");
    static final Instant THU_19_10AM = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant FRI_20_1630 = Instant.parse("2026-11-20T21:30:00Z");
    static final Instant MON_23_10AM = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant WED_25_10AM = Instant.parse("2026-11-25T15:00:00Z");

    static final class TestClock extends Clock {
        private volatile Instant now = MON_23_10AM;

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
        TestClock exerciseTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String EXERCISE = "/api/option-trades/exercises";
    static final String STOCKS = "/api/stock-trades";
    static final String QRTV_C = "QRTV 2026-11-20 30 C";
    static final String XYZ_P = "XYZ 2026-11-20 45 P";
    static final String XYZ_C = "XYZ 2026-12-18 50 C";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private String account = "1";
    private long t1;
    private long l1;
    private long l2;
    private long l3;
    private long s1;

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
        clock.set(MON_23_10AM);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    void cleanUp() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
        jdbc.update("DELETE FROM option_close_lot");
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

    private void transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers"), n, 201);
    }

    private ObjectNode openBody(String action, String symbol, String expiration, String strike, String type,
                                int contracts, String net, String nyTime, Integer multiplier) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        return n;
    }

    private long open(String action, String symbol, String expiration, String strike, String type, int contracts,
                      String net, String nyTime) throws Exception {
        return send(post(TRADES), openBody(action, symbol, expiration, strike, type, contracts, net, nyTime, null),
                201).get("id").asLong();
    }

    private ObjectNode stockBody(String type, String symbol, int shares, String amount, String time) {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(type.equals("TRANSFER_IN") ? "tradeDate" : "tradedAtLocal", time);
        return n;
    }

    private long stock(String type, String symbol, int shares, String amount, String time) throws Exception {
        return send(post(STOCKS), stockBody(type, symbol, shares, amount, time), 201).get("id").asLong();
    }

    /** Scenario E in the current account (clock must be &gt;= 2026-11-06 10:00). */
    private void scenarioE() throws Exception {
        transfer("DEPOSIT", "20000.00", "2026-11-02T10:00");
        t1 = stock("BUY", "XYZ", 200, "8000.00", "2026-11-03T10:00");
        l1 = open("BTO", "QRTV", "2026-11-20", "30", "CALL", 2, "300.00", "2026-11-04T10:00");
        l2 = open("BTO", "QRTV", "2026-11-20", "30", "CALL", 1, "160.00", "2026-11-05T10:00");
        l3 = open("BTO", "XYZ", "2026-11-20", "45", "PUT", 1, "120.00", "2026-11-05T11:00");
        s1 = open("STO", "XYZ", "2026-12-18", "50", "CALL", 1, "90.00", "2026-11-06T10:00");
        assertSummary("11510.00", "0.00", "11510.00");
        assertStockCoverage("XYZ", 200, 100, 100);
    }

    /** The "小账户" of AC 6 / AC 10. */
    private void smallAccount() throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", "小账户");
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
        transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        open("BTO", "XYZ", "2026-11-20", "45", "PUT", 1, "120.00", "2026-11-03T10:00");
    }

    private ObjectNode exerciseBody(String symbol, String expiration, String strike, String type, int contracts,
                                    String date, String fee) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("exerciseDate", date);
        if (fee != null) {
            n.put("exerciseFee", fee);
        }
        return n;
    }

    private ObjectNode qrtv(int contracts, String date, String fee) {
        return exerciseBody("QRTV", "2026-11-20", "30", "CALL", contracts, date, fee);
    }

    private ObjectNode xyzPut() {
        return exerciseBody("XYZ", "2026-11-20", "45", "PUT", 1, "2026-11-20", null);
    }

    private JsonNode exercise(ObjectNode body) throws Exception {
        return send(post(EXERCISE), body, 201);
    }

    private void exerciseRejected(ObjectNode body, String code, String message) throws Exception {
        JsonNode err = send(post(EXERCISE), body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("message").asText()).isEqualTo(message);
        assertThat(err.get("details")).isEmpty();
    }

    private void rejected(MockHttpServletRequestBuilder req, ObjectNode body, String code, String message)
            throws Exception {
        JsonNode err = body == null ? call(req, 422) : send(req, body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("message").asText()).isEqualTo(message);
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private JsonNode stockPosition(String symbol) throws Exception {
        for (JsonNode p : getJson("/api/stock-positions").get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    private void assertStockCoverage(String symbol, long shares, long covered, long available) throws Exception {
        JsonNode p = stockPosition(symbol);
        assertThat(List.of(p.get("shares").asLong(), p.get("coveredShares").asLong(),
                p.get("availableShares").asLong())).containsExactly(shares, covered, available);
    }

    private JsonNode position(String label) throws Exception {
        for (JsonNode p : getJson("/api/option-positions").get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    /** closable / expiryPending / expirable / exercisable of a position. */
    private List<Boolean> buttons(String label) throws Exception {
        JsonNode p = position(label);
        return List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean(), p.get("exercisable").asBoolean());
    }

    private int auditCount() throws Exception {
        return getJson("/api/audit-log?size=100").get("totalItems").asInt();
    }

    private static List<String> rows(JsonNode items, String... fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : items) {
            StringBuilder sb = new StringBuilder();
            for (String f : fields) {
                sb.append(sb.isEmpty() ? "" : " ").append(i.get(f).asText());
            }
            out.add(sb.toString());
        }
        return out;
    }

    private JsonNode closed() throws Exception {
        return getJson("/api/closed-trades?size=100");
    }

    // ---------------------------------------------------------------- AC 1

    /** Clocks 2026-11-17 10:00, 2026-11-20 16:30, 2026-11-23 10:00. */
    @Test
    void ac1_buttonsDialogDataAndShortRejected() throws Exception {
        clock.set(TUE_17_10AM);
        scenarioE();
        assertThat(buttons(QRTV_C)).containsExactly(true, false, false, true);
        assertThat(buttons(XYZ_P)).containsExactly(true, false, false, true);
        assertThat(position(XYZ_C).get("exercisable").asBoolean()).isFalse();
        assertThat(position(XYZ_C).get("exerciseChoices")).isEmpty();
        assertThat(position(XYZ_C).get("exerciseDateMax").isNull()).isTrue();
        assertThat(position(QRTV_C).get("exerciseDateMax").asText()).isEqualTo("2026-11-16");

        clock.set(FRI_20_1630);
        assertThat(buttons(QRTV_C)).containsExactly(false, true, false, true);
        assertThat(buttons(XYZ_P)).containsExactly(false, true, false, true);

        clock.set(MON_23_10AM);
        assertThat(buttons(QRTV_C)).containsExactly(false, false, true, true);
        JsonNode q = position(QRTV_C);
        assertThat(json.writeValueAsString(q.get("exerciseChoices")))
                .isEqualTo("[{\"contracts\":2,\"shares\":200},{\"contracts\":3,\"shares\":300}]");
        assertThat(q.get("exerciseDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(q.get("exerciseDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(q.get("availableCash").asText()).isEqualTo("11510.00");
        assertThat(q.get("underlyingShares").isNull()).isTrue();
        assertThat(q.get("availableShares").isNull()).isTrue();
        JsonNode p = position(XYZ_P);
        assertThat(p.get("underlyingShares").asLong()).isEqualTo(200);
        assertThat(p.get("availableShares").asLong()).isEqualTo(100);
        assertThat(p.get("availableCash").isNull()).isTrue();
        assertThat(p.get("coveredShares").isNull()).isTrue();
        // the short call row keeps its FEAT-039 values
        assertThat(position(XYZ_C).get("availableShares").asLong()).isEqualTo(100);

        exerciseRejected(exerciseBody("XYZ", "2026-12-18", "50", "CALL", 1, "2026-11-20", null),
                "EXERCISE_SHORT_POSITION", "空头持仓不能行权（被行权请用\"被指派\"）");
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_callExercisedInFull() throws Exception {
        scenarioE();
        JsonNode r = exercise(qrtv(3, "2026-11-20", null));
        assertThat(r.get("action").asText()).isEqualTo("EXR");
        assertThat(r.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(r.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        assertThat(r.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(r.get("cashChange").asText()).isEqualTo("-9000.00");
        assertThat(r.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(r.get("realizedPnl").asText()).isEqualTo("0.00");
        assertThat(r.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(r.get("assignment").isNull()).isTrue();
        assertThat(rows(r.get("coveredLots"), "tradeId", "action", "netAmount"))
                .containsExactly(l1 + " BTO -300.00", l2 + " BTO -160.00");
        JsonNode e = r.get("exercise");
        assertThat(e.get("exerciseDate").asText()).isEqualTo("2026-11-20");
        assertThat(e.get("exerciseFee").asText()).isEqualTo("0.00");
        assertThat(e.get("premium").asText()).isEqualTo("460.00");
        assertThat(e.get("shares").asInt()).isEqualTo(300);
        assertThat(e.get("purchaseAmount").asText()).isEqualTo("9000.00");
        assertThat(e.get("stockCost").asText()).isEqualTo("9460.00");
        assertThat(e.get("stockCostPerShare").asText()).isEqualTo("31.5333");
        assertThat(e.get("saleAmount").isNull()).isTrue();
        assertThat(e.get("soldLots").isNull()).isTrue();
        long exerciseId = r.get("id").asLong();
        long stockId = e.get("stockTradeId").asLong();

        assertSummary("2510.00", "0.00", "2510.00");
        assertThat(position(QRTV_C)).isNull();
        JsonNode sp = stockPosition("QRTV");
        assertThat(sp.get("shares").asLong()).isEqualTo(300);
        assertThat(sp.get("totalCost").asText()).isEqualTo("9460.00");
        assertThat(sp.get("lots").get(0).get("type").asText()).isEqualTo("EXERCISE_BUY");

        JsonNode st = getJson(STOCKS + "/" + stockId);
        assertThat(st.get("type").asText()).isEqualTo("EXERCISE_BUY");
        assertThat(st.get("amount").asText()).isEqualTo("9460.00");
        assertThat(st.get("pricePerShare").asText()).isEqualTo("31.5333");
        assertThat(st.get("exerciseTradeId").asLong()).isEqualTo(exerciseId);
        assertThat(st.get("assignmentTradeId").isNull()).isTrue();
        assertThat(st.get("editable").asBoolean()).isFalse();
        assertThat(st.get("deletable").asBoolean()).isFalse();
        assertThat(getJson(STOCKS + "?type=EXERCISE_BUY").get("totalItems").asInt()).isEqualTo(1);

        JsonNode c = closed();
        assertThat(c.get("totalItems").asInt()).isEqualTo(1);
        JsonNode row = c.get("items").get(0);
        assertThat(List.of(row.get("action").asText(), row.get("closedDirection").asText(),
                row.get("openingNetTotal").asText(), row.get("realizedPnl").asText()))
                .containsExactly("EXR", "LONG", "-460.00", "0.00");
        assertThat(json.writeValueAsString(row.get("exercise")))
                .isEqualTo("{\"shares\":300,\"stockCost\":\"9460.00\",\"proceeds\":null,\"stockTradeId\":" + stockId + "}");
        assertThat(row.get("assignment").isNull()).isTrue();
        assertThat(getJson("/api/closed-trades?action=EXR").get("totalItems").asInt()).isEqualTo(1);
        assertThat(getJson(TRADES + "?action=EXR").get("totalItems").asInt()).isEqualTo(1);

        JsonNode top = getJson("/api/cash/ledger?size=100").get("items").get(0);
        assertThat(top.get("sourceType").asText()).isEqualTo("OPTION_EXERCISE");
        assertThat(top.get("sourceId").asLong()).isEqualTo(exerciseId);
        assertThat(top.get("description").asText()).isEqualTo("行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV");
        assertThat(auditCount()).isZero();
        assertThat(getJson("/api/dashboard").get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_putExercised() throws Exception {
        scenarioE();
        JsonNode r = exercise(xyzPut());
        assertThat(r.get("cashChange").asText()).isEqualTo("4500.00");
        JsonNode e = r.get("exercise");
        assertThat(e.get("premium").asText()).isEqualTo("120.00");
        assertThat(e.get("saleAmount").asText()).isEqualTo("4500.00");
        assertThat(e.get("proceeds").asText()).isEqualTo("4380.00");
        assertThat(e.get("soldCost").asText()).isEqualTo("4000.00");
        assertThat(e.get("stockRealizedPnl").asText()).isEqualTo("380.00");
        assertThat(e.get("purchaseAmount").isNull()).isTrue();
        assertThat(rows(e.get("soldLots"), "tradeId", "type", "shares", "cost"))
                .containsExactly(t1 + " BUY 100 4000.00");
        long stockId = e.get("stockTradeId").asLong();

        assertSummary("16010.00", "0.00", "16010.00");
        assertStockCoverage("XYZ", 100, 100, 0);

        JsonNode c = closed();
        assertThat(c.get("totalItems").asInt()).isEqualTo(2);
        assertThat(rows(c.get("items"), "type", "action", "realizedPnl"))
                .containsExactly("STOCK EXERCISE_SELL 380.00", "OPTION EXR 0.00");
        JsonNode stockRow = c.get("items").get(0);
        assertThat(stockRow.get("exerciseTradeId").asLong()).isEqualTo(r.get("id").asLong());
        assertThat(stockRow.get("closeNetAmount").asText()).isEqualTo("4380.00");
        assertThat(json.writeValueAsString(c.get("items").get(1).get("exercise")))
                .isEqualTo("{\"shares\":100,\"stockCost\":null,\"proceeds\":\"4380.00\",\"stockTradeId\":" + stockId + "}");
        assertThat(c.get("filteredRealizedPnlTotal").asText()).isEqualTo("380.00");
        JsonNode d = getJson("/api/dashboard").get("realizedPnl");
        assertThat(List.of(d.get("closedCount").asInt(), d.get("total").asText())).containsExactly(2, "380.00");

        JsonNode st = getJson(STOCKS + "/" + stockId);
        assertThat(List.of(st.get("type").asText(), st.get("amount").asText(), st.get("soldCost").asText(),
                st.get("realizedPnl").asText())).containsExactly("EXERCISE_SELL", "4380.00", "4000.00", "380.00");
        assertThat(getJson("/api/cash/ledger?size=100").get("items").get(0).get("description").asText())
                .isEqualTo("行权 1 张 XYZ 2026-11-20 45 P，卖出 100 股 XYZ");
    }

    // ---------------------------------------------------------------- AC 4

    /** Clock 2026-11-19 10:00, then 2026-11-23 10:00. */
    @Test
    void ac4_earlyPartialExpiryAndDates() throws Exception {
        clock.set(THU_19_10AM);
        scenarioE();
        exerciseRejected(qrtv(1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS",
                "必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
        exerciseRejected(qrtv(2, "2026-11-19", null), "EXERCISE_DATE_INVALID", "行权日期为今天时，美东 16:00 之后才能记录");
        exerciseRejected(qrtv(2, "2026-11-21", null), "EXERCISE_DATE_INVALID", "行权日期不能晚于到期日（2026-11-20）");
        exerciseRejected(exerciseBody("QRTV", "2026-11-20", "30", "CALL", 2, "2026-11-20", null),
                "EXERCISE_DATE_INVALID", "行权日期不能晚于今天");

        JsonNode r = exercise(qrtv(2, "2026-11-18", "5.00"));
        assertThat(r.get("tradedAt").asText()).isEqualTo("2026-11-18T21:00:00Z");
        assertThat(r.get("cashChange").asText()).isEqualTo("-6005.00");
        assertThat(rows(r.get("coveredLots"), "tradeId")).containsExactly(String.valueOf(l1));
        JsonNode e = r.get("exercise");
        assertThat(List.of(e.get("exerciseFee").asText(), e.get("premium").asText(), e.get("purchaseAmount").asText(),
                e.get("stockCost").asText(), e.get("stockCostPerShare").asText()))
                .containsExactly("5.00", "300.00", "6000.00", "6305.00", "31.5250");
        assertSummary("5505.00", "0.00", "5505.00");
        assertThat(position(QRTV_C).get("contracts").asInt()).isEqualTo(1);

        clock.set(MON_23_10AM);
        ObjectNode expire = json.createObjectNode();
        expire.put("symbol", "QRTV");
        expire.put("expirationDate", "2026-11-20");
        expire.put("strike", "30");
        expire.put("optionType", "CALL");
        expire.put("contracts", 1);
        JsonNode exp = send(post(TRADES + "/expirations"), expire, 201);
        assertThat(exp.get("contracts").asInt()).isEqualTo(1);
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("-160.00");
    }

    @Test
    void ac4_exerciseAfterExpiryRejected() throws Exception {
        scenarioE();
        ObjectNode expire = json.createObjectNode();
        expire.put("symbol", "QRTV");
        expire.put("expirationDate", "2026-11-20");
        expire.put("strike", "30");
        expire.put("optionType", "CALL");
        expire.put("contracts", 3);
        send(post(TRADES + "/expirations"), expire, 201);
        exerciseRejected(qrtv(3, "2026-11-20", null), "NO_POSITION_TO_EXERCISE",
                "2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权"
                        + "（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）");
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_cashInsufficient() throws Exception {
        scenarioE();
        transfer("WITHDRAWAL", "3000.00", "2026-11-10T10:00");
        exerciseRejected(qrtv(3, "2026-11-20", null), "CASH_INSUFFICIENT",
                "可用现金不足：需要 9,000.00；2026-11-20 16:00 时可用现金将变为 −490.00");
        exercise(qrtv(2, "2026-11-20", null));
        assertSummary("2510.00", "0.00", "2510.00");
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_sharesInsufficient() throws Exception {
        smallAccount();
        exerciseRejected(xyzPut(), "SHARES_INSUFFICIENT",
                "持股不足：行权需要 100 股 XYZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）");
    }

    @Test
    void ac6_coveredSharesCannotBeDelivered() throws Exception {
        scenarioE();
        open("STO", "XYZ", "2026-12-18", "50", "CALL", 1, "80.00", "2026-11-07T10:00");
        assertStockCoverage("XYZ", 200, 200, 0);
        exerciseRejected(xyzPut(), "COVERAGE_INSUFFICIENT",
                "行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");

        ObjectNode btc = openBody("BTC", "XYZ", "2026-12-18", "50", "CALL", 1, "30.00", "2026-11-10T10:00", null);
        send(post(TRADES + "/closes"), btc, 201);
        JsonNode e = exercise(xyzPut()).get("exercise");
        assertThat(List.of(e.get("proceeds").asText(), e.get("stockRealizedPnl").asText()))
                .containsExactly("4380.00", "380.00");
    }

    @Test
    void ac6_laterSaleBecomesUncovered() throws Exception {
        // the put exercise passes at its own time, but after a stored later sale the short call is no longer covered
        scenarioE();
        clock.set(WED_25_10AM);
        stock("SELL", "XYZ", 100, "4700.00", "2026-11-24T10:00");
        exerciseRejected(xyzPut(), "COVERAGE_INSUFFICIENT",
                "行权后 2026-11-24 10:00 起 XYZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    @Test
    void ac6_laterSaleExceedsHolding() throws Exception {
        // exercising the put earlier than a stored sale leaves too few shares for that sale (FEAT-011 wording)
        clock.set(WED_25_10AM);
        transfer("DEPOSIT", "20000.00", "2026-11-02T10:00");
        stock("BUY", "XYZ", 100, "4000.00", "2026-11-03T10:00");
        open("BTO", "XYZ", "2026-11-20", "45", "PUT", 1, "120.00", "2026-11-05T11:00");
        stock("SELL", "XYZ", 100, "4700.00", "2026-11-24T10:00");
        exerciseRejected(xyzPut(), "SHARES_INSUFFICIENT", "补录后 2026-11-24 10:00 的卖出 XYZ 100 股将超过当时持股 0 股");
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void ac7_deleteAndReRecord() throws Exception {
        scenarioE();
        long id = exercise(qrtv(3, "2026-11-20", null)).get("id").asLong();
        call(delete(TRADES + "/" + id), 204);
        assertSummary("11510.00", "0.00", "11510.00");
        assertThat(position(QRTV_C).get("contracts").asInt()).isEqualTo(3);
        assertThat(stockPosition("QRTV")).isNull();
        assertThat(closed().get("totalItems").asInt()).isZero();
        assertThat(getJson("/api/cash/ledger?size=100").get("items").findValuesAsText("sourceType"))
                .doesNotContain("OPTION_EXERCISE");
        JsonNode audit = getJson("/api/audit-log?size=100");
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        JsonNode a = audit.get("items").get(0);
        assertThat(a.get("summary").asText()).isEqualTo("行权 QRTV 2026-11-20 30 C 3 张（买入 300 股 QRTV）");
        assertThat(a.get("before").get("action").asText()).isEqualTo("EXR");
        assertThat(a.get("before").get("exerciseDate").asText()).isEqualTo("2026-11-20");
        assertThat(a.get("before").get("premium").asText()).isEqualTo("460.00");
        assertThat(a.get("before").get("stockCost").asText()).isEqualTo("9460.00");

        JsonNode again = exercise(qrtv(3, "2026-11-20", null));
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
    }

    @Test
    void ac7_deleteRejectedByLaterSaleAndNotEditable() throws Exception {
        scenarioE();
        JsonNode r = exercise(qrtv(3, "2026-11-20", null));
        long id = r.get("id").asLong();
        long stockId = r.get("exercise").get("stockTradeId").asLong();
        clock.set(WED_25_10AM);
        stock("SELL", "QRTV", 100, "3300.00", "2026-11-24T10:00");
        rejected(delete(TRADES + "/" + id), null, "SHARES_INSUFFICIENT",
                "删除后 2026-11-24 10:00 的卖出 QRTV 100 股将超过当时持股 0 股");

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 3);
        edit.put("netAmount", "1.00");
        edit.put("tradedAtLocal", "2026-11-20T16:00");
        rejected(put(TRADES + "/" + id), edit, "EXERCISE_NOT_EDITABLE", "行权不能修改，记错请删除后重新记录");
        rejected(put(STOCKS + "/" + stockId), stockBody("BUY", "QRTV", 300, "9000.00", "2026-11-20T16:00"),
                "STOCK_TRADE_FROM_EXERCISE", "该股票记录由行权生成，请在交易列表删除对应的行权记录");
        rejected(delete(STOCKS + "/" + stockId), null, "STOCK_TRADE_FROM_EXERCISE",
                "该股票记录由行权生成，请在交易列表删除对应的行权记录");
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 8

    @Test
    void ac8_replayAfterOpeningEdits() throws Exception {
        scenarioE();
        long callId = exercise(qrtv(3, "2026-11-20", null)).get("id").asLong();
        long putId = exercise(xyzPut()).get("id").asLong();

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "170.00");
        edit.put("tradedAtLocal", "2026-11-05T10:00");
        send(put(TRADES + "/" + l2), edit, 200);
        assertThat(getJson(TRADES + "/" + callId).get("exercise").get("stockCost").asText()).isEqualTo("9470.00");
        assertThat(stockPosition("QRTV").get("totalCost").asText()).isEqualTo("9470.00");
        JsonNode audit = getJson("/api/audit-log?size=100").get("items").get(0);
        assertThat(rows(audit.get("sideEffects"), "action", "field", "text"))
                .containsExactly("EXR stockCost 行权 #" + callId + " 股票成本 9,460.00 → 9,470.00");

        edit.put("netAmount", "130.00");
        edit.put("tradedAtLocal", "2026-11-05T11:00");
        send(put(TRADES + "/" + l3), edit, 200);
        JsonNode e = getJson(TRADES + "/" + putId).get("exercise");
        assertThat(List.of(e.get("proceeds").asText(), e.get("stockRealizedPnl").asText()))
                .containsExactly("4370.00", "370.00");
        audit = getJson("/api/audit-log?size=100").get("items").get(0);
        assertThat(rows(audit.get("sideEffects"), "field", "text"))
                .containsExactly("proceeds 行权 #" + putId + " 股票卖出实收 4,380.00 → 4,370.00");
        assertSummary("6990.00", "0.00", "6990.00");

        rejected(delete(TRADES + "/" + l2), null, "CLOSE_EXCEEDS_POSITION",
                "该开仓被 2026-11-20 16:00 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权");
    }

    // ---------------------------------------------------------------- AC 9

    @Test
    void ac9_multiplierOneAndRounding() throws Exception {
        ObjectNode acc = json.createObjectNode();
        acc.put("name", "取整账户");
        account = String.valueOf(send(post("/api/accounts"), acc, 201).get("id").asLong());
        transfer("DEPOSIT", "100.00", "2026-11-02T10:00");
        stock("TRANSFER_IN", "QFZ", 1, "10.00", "2026-11-02");
        send(post(TRADES), openBody("BTO", "QFZ", "2026-11-20", "12.345", "CALL", 1, "0.50", "2026-11-03T10:00", 1),
                201);
        send(post(TRADES), openBody("BTO", "QFZ", "2026-11-20", "12.345", "PUT", 1, "0.40", "2026-11-03T10:05", 1),
                201);
        assertSummary("99.10", "0.00", "99.10");

        JsonNode c = exercise(exerciseBody("QFZ", "2026-11-20", "12.345", "CALL", 1, "2026-11-20", null))
                .get("exercise");
        assertThat(List.of(c.get("purchaseAmount").asText(), c.get("stockCost").asText()))
                .containsExactly("12.35", "12.85");
        assertSummary("86.75", "0.00", "86.75");
        assertThat(getJson("/api/cash/ledger?size=100").get("items").get(0).get("description").asText())
                .isEqualTo("行权 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），买入 1 股 QFZ");

        JsonNode p = exercise(exerciseBody("QFZ", "2026-11-20", "12.345", "PUT", 1, "2026-11-20", null))
                .get("exercise");
        assertThat(List.of(p.get("saleAmount").asText(), p.get("proceeds").asText(), p.get("soldCost").asText(),
                p.get("stockRealizedPnl").asText())).containsExactly("12.34", "11.94", "10.00", "1.94");
        assertThat(rows(p.get("soldLots"), "type", "tradeDate", "shares", "cost"))
                .containsExactly("TRANSFER_IN 2026-11-02 1 10.00");
        assertSummary("99.09", "0.00", "99.09");
        assertThat(getJson("/api/cash/ledger?size=100").get("items").get(0).get("description").asText())
                .isEqualTo("行权 1 张 QFZ 2026-11-20 12.345 P（multiplier 1），卖出 1 股 QFZ");
    }

    // ---------------------------------------------------------------- AC 10

    @Test
    void ac10_accountIsolationAndFieldValidation() throws Exception {
        scenarioE();
        long id = exercise(qrtv(3, "2026-11-20", null)).get("id").asLong();

        smallAccount();
        open("BTO", "QRTV", "2026-11-20", "30", "CALL", 1, "50.00", "2026-11-04T10:00");
        assertThat(position(QRTV_C).get("contracts").asInt()).isEqualTo(1);
        assertSummary("830.00", "0.00", "830.00");
        call(delete(TRADES + "/" + id), 404);
        call(get(TRADES + "/" + id), 404);

        ObjectNode bad = qrtv(0, "2026-11-20", "-1.00");
        bad.putNull("exerciseDate");
        bad.put("note", "x".repeat(501));
        JsonNode err = send(post(EXERCISE), bad, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows(err.get("details"), "field", "message")).containsExactly(
                "contracts 张数必须是正整数", "exerciseDate 行权日期不能为空", "exerciseFee 行权费不能为负数",
                "note 备注最多 500 个字符");
        err = send(post(EXERCISE), qrtv(1, "2026-11-20", "1.234"), 400);
        assertThat(rows(err.get("details"), "field", "message")).containsExactly("exerciseFee 行权费最多 2 位小数");
    }

    // ---------------------------------------------------------------- generated stock types

    @Test
    void generatedStockTypesCannotBeEnteredAndFiltersAccepted() throws Exception {
        transfer("DEPOSIT", "1000.00", "2026-11-02T10:00");
        JsonNode err = send(post(STOCKS), stockBody("EXERCISE_BUY", "QRTV", 1, "10.00", "2026-11-03T10:00"), 400);
        assertThat(rows(err.get("details"), "field", "message")).containsExactly("type 行权买入由行权生成，不能直接录入");
        err = send(post(STOCKS), stockBody("EXERCISE_SELL", "QRTV", 1, "10.00", "2026-11-03T10:00"), 400);
        assertThat(rows(err.get("details"), "field", "message")).containsExactly("type 行权卖出由行权生成，不能直接录入");
        assertThat(getJson(STOCKS + "?type=EXERCISE_SELL").get("totalItems").asInt()).isZero();
        err = call(get(URI.create(TRADES + "?action=XXX")), 400);
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTO、STO、BTC、STC、EXP、ASG、EXR");
        err = call(get(URI.create("/api/closed-trades?action=XXX")), 400);
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTC、STC、EXP、ASG、EXR");
        err = call(get(URI.create(STOCKS + "?type=XXX")), 400);
        assertThat(err.get("details").get(0).get("message").asText())
                .isEqualTo("类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL");
    }
}
