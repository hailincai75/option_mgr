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
 * FEAT-013 acceptance criteria (backend part), scenario P. Fictional tickers only. The clock is set per test (noted
 * on each); 2026-11-20 is a Friday, 2026-11-23 the Monday after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class PutAssignmentControllerTest {

    static final Instant TUE_17_10AM = Instant.parse("2026-11-17T15:00:00Z");
    static final Instant THU_19_10AM = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant FRI_20_1630 = Instant.parse("2026-11-20T21:30:00Z");
    static final Instant MON_23_10AM = Instant.parse("2026-11-23T15:00:00Z");

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
        TestClock assignmentTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String ASSIGN = "/api/option-trades/assignments";
    static final String XYZ = "XYZ 2026-11-20 45 P";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private String account = "1";
    private long p1;
    private long p2;

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

    private long transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        return send(post("/api/cash/transfers"), n, 201).get("id").asLong();
    }

    private long open(String action, String symbol, String exp, String strike, String type, int contracts, String net,
                      String nyTime, Integer multiplier) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        return send(post(com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(n)), n, 201).get("id").asLong();
    }

    /** Scenario P in the current account (clock must be &gt;= 2026-11-06 12:00). */
    private void scenarioP() throws Exception {
        transfer("DEPOSIT", "20000.00", "2026-11-02T10:00");
        p1 = open("STO", "XYZ", "2026-11-20", "45", "PUT", 2, "240.00", "2026-11-03T10:00", null);
        p2 = open("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "130.00", "2026-11-05T10:00", null);
        open("STO", "QRTV", "2026-12-18", "30", "PUT", 1, "85.00", "2026-11-06T10:00", null);
        open("BTO", "QRTV", "2026-11-20", "25", "CALL", 1, "40.00", "2026-11-06T11:00", null);
        open("STO", "IXQZ", "2026-11-20", "8", "CALL", 1, "22.00", "2026-11-06T12:00", null);
        assertSummary("20437.00", "16500.00", "3937.00");
    }

    private ObjectNode assignBody(String symbol, String strike, String type, int contracts, String date, String fee) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", "2026-11-20");
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        if (fee != null) {
            n.put("assignmentFee", fee);
        }
        return n;
    }

    private JsonNode assign(int contracts, String date, String fee) throws Exception {
        return send(post(ASSIGN), assignBody("XYZ", "45", "PUT", contracts, date, fee), 201);
    }

    private void assignRejected(ObjectNode body, String code, String message) throws Exception {
        JsonNode err = send(post(ASSIGN), body, 422);
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

    private JsonNode position(String label) throws Exception {
        for (JsonNode p : getJson("/api/option-positions").get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    /** closable / expiryPending / expirable / assignable of a position. */
    private List<Boolean> buttons(String label) throws Exception {
        JsonNode p = position(label);
        return List.of(p.get("closable").asBoolean(), p.get("expiryPending").asBoolean(),
                p.get("expirable").asBoolean(), p.get("assignable").asBoolean());
    }

    private JsonNode stockPosition(String symbol) throws Exception {
        for (JsonNode p : getJson("/api/stock-positions").get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    private int auditCount() throws Exception {
        return getJson("/api/audit-log?size=100").get("totalItems").asInt();
    }

    private JsonNode sell(String symbol, int shares, String amount, String nyTime, int status) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "SELL");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradedAtLocal", nyTime);
        return send(post("/api/stock-trades"), n, status);
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

    // ---------------------------------------------------------------- AC 1

    /** Clocks 2026-11-17 10:00, 2026-11-20 16:30, 2026-11-23 10:00. */
    @Test
    void ac1_buttonsAndTimeWindows() throws Exception {
        clock.set(TUE_17_10AM);
        scenarioP();
        // closable, expiryPending, expirable, assignable
        assertThat(buttons(XYZ)).containsExactly(true, false, false, true);
        assertThat(buttons("QRTV 2026-12-18 30 P")).containsExactly(true, false, false, true);
        assertThat(buttons("QRTV 2026-11-20 25 C")).containsExactly(true, false, false, false);
        assertThat(buttons("IXQZ 2026-11-20 8 C")).containsExactly(true, false, false, true) /* FEAT-041: short call */;
        JsonNode longCall = position("QRTV 2026-11-20 25 C");
        assertThat(longCall.get("assignChoices")).isEmpty();
        assertThat(longCall.get("assignmentDateMax").isNull()).isTrue();
        assertThat(longCall.get("assignmentDateDefault").isNull()).isTrue();

        clock.set(FRI_20_1630);
        assertThat(buttons(XYZ)).containsExactly(false, true, false, true);
        assertThat(position(XYZ).get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(buttons("IXQZ 2026-11-20 8 C")).containsExactly(false, true, false, true);
        assertThat(buttons("QRTV 2026-11-20 25 C")).containsExactly(false, true, false, false);

        clock.set(MON_23_10AM);
        assertThat(buttons(XYZ)).containsExactly(false, false, true, true);
        assertThat(buttons("QRTV 2026-11-20 25 C")).containsExactly(false, false, true, false);
        assertThat(buttons("IXQZ 2026-11-20 8 C")).containsExactly(false, false, true, true);
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(json.writeValueAsString(xyz.get("assignChoices")))
                .isEqualTo("[{\"contracts\":2,\"shares\":200},{\"contracts\":3,\"shares\":300}]");
        assertThat(xyz.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(xyz.get("assignmentDateDefault").asText()).isEqualTo("2026-11-20");

        assignRejected(assignBody("IXQZ", "8", "CALL", 1, "2026-11-20", null), "SHARES_INSUFFICIENT",
                "持股不足：被指派需要 100 股 IXQZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）"); // FEAT-041
        assignRejected(assignBody("QRTV", "25", "CALL", 1, "2026-11-20", null), "ASSIGN_LONG_POSITION",
                "多头持仓不能被指派（主动行权见 FEAT-014）");
    }

    // ---------------------------------------------------------------- AC 2

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac2_assignAllThreeContracts() throws Exception {
        scenarioP();
        JsonNode r = assign(3, "2026-11-20", "0.00");
        long id = r.get("id").asLong();
        assertThat(r.get("action").asText()).isEqualTo("ASG");
        assertThat(r.get("tradedAt").asText()).isEqualTo("2026-11-20T21:00:00Z");
        assertThat(r.get("tradedAtLocal").asText()).isEqualTo("2026-11-20T16:00:00");
        assertThat(r.get("contracts").asInt()).isEqualTo(3);
        assertThat(r.get("multiplier").asInt()).isEqualTo(100);
        assertThat(r.get("netAmount").asText()).isEqualTo("0.00");
        assertThat(r.get("cashChange").asText()).isEqualTo("-13500.00");
        assertThat(r.get("frozenChange").asText()).isEqualTo("-13500.00");
        assertThat(r.get("realizedPnl").asText()).isEqualTo("0.00");
        assertThat(r.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(r.get("expiredDirection").isNull()).isTrue();
        assertThat(r.get("ledgerEntryId").isNumber()).isTrue();
        assertThat(rows(r.get("coveredLots"), "tradeId", "contracts", "netAmount", "frozen"))
                .containsExactly(p1 + " 2 240.00 9000.00", p2 + " 1 130.00 4500.00");
        JsonNode a = r.get("assignment");
        assertThat(a.get("assignmentDate").asText()).isEqualTo("2026-11-20");
        assertThat(a.get("assignmentFee").asText()).isEqualTo("0.00");
        assertThat(a.get("premium").asText()).isEqualTo("370.00");
        assertThat(a.get("shares").asInt()).isEqualTo(300);
        assertThat(a.get("purchaseAmount").asText()).isEqualTo("13500.00");
        assertThat(a.get("stockCost").asText()).isEqualTo("13130.00");
        assertThat(a.get("stockCostPerShare").asText()).isEqualTo("43.7667");
        long stockId = a.get("stockTradeId").asLong();

        assertSummary("6937.00", "3000.00", "3937.00");
        assertThat(position(XYZ)).isNull();
        JsonNode sp = stockPosition("XYZ");
        assertThat(sp.get("shares").asLong()).isEqualTo(300);
        assertThat(sp.get("totalCost").asText()).isEqualTo("13130.00");
        assertThat(sp.get("averageCost").asText()).isEqualTo("43.7667");
        assertThat(rows(sp.get("lots"), "tradeId", "type", "tradedAtLocal"))
                .containsExactly(stockId + " ASSIGN_BUY 2026-11-20T16:00:00");

        JsonNode ledgerTop = getJson("/api/cash/ledger?size=100").get("items").get(0);
        assertThat(rows(json.createArrayNode().add(ledgerTop),
                "occurredAt", "sourceType", "sourceId", "amount", "frozenChange", "balanceAfter", "description",
                "hasAuditLog"))
                .containsExactly("2026-11-20T21:00:00Z OPTION_ASSIGN " + id
                        + " -13500.00 -13500.00 6937.00 被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ false");

        JsonNode list = getJson(TRADES).get("items");
        assertThat(list.get(0).get("id").asLong()).isEqualTo(id);
        assertThat(list.get(0).get("assignment").get("stockTradeId").asLong()).isEqualTo(stockId);
        assertThat(list.get(1).get("assignment").isNull()).isTrue();
        JsonNode onlyAsg = getJson(TRADES + "?action=ASG").get("items");
        assertThat(onlyAsg).hasSize(1);
        assertThat(onlyAsg.get(0).get("id").asLong()).isEqualTo(id);

        JsonNode st = getJson("/api/stock-trades/" + stockId);
        assertThat(rows(json.createArrayNode().add(st), "type", "symbol", "shares", "amount", "cashChange",
                "pricePerShare", "assignmentTradeId", "editable", "deletable", "hasAuditLog", "ledgerEntryId", "note"))
                .containsExactly("ASSIGN_BUY XYZ 300 13130.00 0.00 43.7667 " + id + " false false false null null");
        assertThat(getJson("/api/stock-trades?type=ASSIGN_BUY").get("totalItems").asInt()).isEqualTo(1);

        JsonNode closed = getJson("/api/closed-trades");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        JsonNode row = closed.get("items").get(0);
        assertThat(rows(json.createArrayNode().add(row), "type", "id", "action", "closedDirection", "contracts",
                "openingNetTotal", "openingNetPerContract", "closeNetAmount", "closeNetPerContract", "realizedPnl"))
                .containsExactly("OPTION " + id + " ASG SHORT 3 370.00 123.33 0.00 0.00 0.00");
        assertThat(json.writeValueAsString(row.get("assignment")))
                .isEqualTo("{\"shares\":300,\"stockCost\":\"13130.00\",\"proceeds\":null,\"stockTradeId\":" + stockId + "}");
        assertThat(getJson("/api/closed-trades?action=ASG").get("filteredCount").asInt()).isEqualTo(1);

        JsonNode pnl = getJson("/api/pnl-summary");
        assertThat(pnl.get("closedCount").asInt()).isEqualTo(1);
        assertThat(pnl.get("realizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(json.writeValueAsString(pnl.get("bySymbol"))).isEqualTo("[{\"symbol\":\"XYZ\",\"closedCount\":1,"
                + "\"realizedPnl\":\"0.00\",\"option\":{\"closedCount\":1,\"realizedPnl\":\"0.00\"},"
                + "\"stock\":{\"closedCount\":0,\"realizedPnl\":\"0.00\"}}]");
        JsonNode dash = getJson("/api/dashboard");
        assertThat(dash.get("realizedPnl").get("closedCount").asInt()).isEqualTo(1);
        assertThat(dash.get("realizedPnl").get("total").asText()).isEqualTo("0.00");
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 3

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac3_laterSaleCarriesThePremiumInTheStockPnl() throws Exception {
        scenarioP();
        assign(3, "2026-11-20", "0.00");
        JsonNode err = sell("XYZ", 100, "4400.00", "2026-11-20T15:00", 422);
        assertThat(err.get("code").asText()).isEqualTo("SHARES_INSUFFICIENT");
        assertThat(err.get("message").asText()).isEqualTo("持股不足：2026-11-20 15:00 卖出 XYZ 100 股，当时持有 0 股");

        JsonNode sale = sell("XYZ", 100, "4400.00", "2026-11-20T16:00", 201);
        assertThat(sale.get("soldCost").asText()).isEqualTo("4376.67");
        assertThat(sale.get("realizedPnl").asText()).isEqualTo("23.33");
        assertThat(sale.get("soldLots").get(0).get("type").asText()).isEqualTo("ASSIGN_BUY");
        JsonNode sp = stockPosition("XYZ");
        assertThat(sp.get("shares").asLong()).isEqualTo(200);
        assertThat(sp.get("totalCost").asText()).isEqualTo("8753.33");
        assertSummary("11337.00", "3000.00", "8337.00");

        JsonNode closed = getJson("/api/closed-trades");
        assertThat(rows(closed.get("items"), "type", "action", "realizedPnl"))
                .containsExactly("STOCK SELL 23.33", "OPTION ASG 0.00");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("23.33");
        JsonNode xyz = getJson("/api/pnl-summary").get("bySymbol").get(0);
        assertThat(xyz.get("closedCount").asInt()).isEqualTo(2);
        assertThat(xyz.get("option").get("realizedPnl").asText()).isEqualTo("0.00");
        assertThat(xyz.get("stock").get("realizedPnl").asText()).isEqualTo("23.33");
        assertThat(xyz.get("realizedPnl").asText()).isEqualTo("23.33");
    }

    // ---------------------------------------------------------------- AC 4

    /** Clock 2026-11-19 10:00, then 2026-11-23 10:00. */
    @Test
    void ac4_earlyAssignmentFeeWholeLotsDatesAndRemainingExpiry() throws Exception {
        clock.set(THU_19_10AM);
        scenarioP();
        assertThat(position(XYZ).get("assignmentDateMax").asText()).isEqualTo("2026-11-18");
        assignRejected(assignBody("XYZ", "45", "PUT", 1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS",
                "必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-21", null), "ASSIGNMENT_DATE_INVALID",
                "指派日期不能晚于到期日（2026-11-20）");
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-19", null), "ASSIGNMENT_DATE_INVALID",
                "指派日期为今天时，美东 16:00 之后才能记录");
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-20", null), "ASSIGNMENT_DATE_INVALID",
                "指派日期不能晚于今天");
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-02", null), "NO_POSITION_TO_ASSIGN",
                "2026-11-02 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派");
        assignRejected(assignBody("XYZ", "45", "PUT", 4, "2026-11-18", null), "CLOSE_EXCEEDS_POSITION",
                "被指派 4 张超过当时持仓 3 张");

        JsonNode r = assign(2, "2026-11-18", "5.00");
        assertThat(r.get("tradedAt").asText()).isEqualTo("2026-11-18T21:00:00Z");
        assertThat(rows(r.get("coveredLots"), "tradeId")).containsExactly(String.valueOf(p1));
        assertThat(r.get("assignment").get("shares").asInt()).isEqualTo(200);
        assertThat(r.get("assignment").get("stockCost").asText()).isEqualTo("8765.00");
        assertThat(r.get("assignment").get("stockCostPerShare").asText()).isEqualTo("43.8250");
        assertThat(r.get("cashChange").asText()).isEqualTo("-9005.00");
        assertThat(r.get("frozenChange").asText()).isEqualTo("-9000.00");
        assertSummary("11432.00", "7500.00", "3932.00");
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("contracts").asInt()).isEqualTo(1);
        assertThat(xyz.get("lots").get(0).get("tradeId").asLong()).isEqualTo(p2);
        assertThat(xyz.get("closable").asBoolean()).isTrue();
        assertThat(xyz.get("assignable").asBoolean()).isTrue();

        clock.set(MON_23_10AM);
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "XYZ");
        exp.put("expirationDate", "2026-11-20");
        exp.put("strike", "45");
        exp.put("optionType", "PUT");
        exp.put("contracts", 1);
        JsonNode e = send(post(TRADES + "/expirations"), exp, 201);
        assertThat(e.get("realizedPnl").asText()).isEqualTo("130.00");
        assertSummary("11432.00", "3000.00", "8432.00");
        assertThat(rows(getJson("/api/closed-trades?symbol=XYZ").get("items"), "action", "tradedAtLocal", "realizedPnl"))
                .containsExactly("EXP 2026-11-20T16:00:00 130.00", "ASG 2026-11-18T16:00:00 0.00");
    }

    // ---------------------------------------------------------------- AC 5

    private ObjectNode expireXyz(int contracts) {
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "XYZ");
        exp.put("expirationDate", "2026-11-20");
        exp.put("strike", "45");
        exp.put("optionType", "PUT");
        exp.put("contracts", contracts);
        return exp;
    }

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac5_assignThenExpireAndDeleteOrder() throws Exception {
        scenarioP();
        JsonNode asg = assign(2, "2026-11-20", null);
        assertThat(asg.get("assignment").get("stockCost").asText()).isEqualTo("8760.00");
        JsonNode exp = send(post(TRADES + "/expirations"), expireXyz(1), 201);
        assertThat(exp.get("realizedPnl").asText()).isEqualTo("130.00");
        assertThat(rows(getJson(TRADES).get("items"), "action")).startsWith("EXP", "ASG");

        rejected(delete(TRADES + "/" + asg.get("id").asLong()), null, "CLOSE_NOT_WHOLE_LOTS",
                "删除后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，"
                        + "可为 2 张或 3 张），请先删除该到期作废");
        call(delete(TRADES + "/" + exp.get("id").asLong()), 204);
        call(delete(TRADES + "/" + asg.get("id").asLong()), 204);
        assertThat(position(XYZ).get("contracts").asInt()).isEqualTo(3);
        assertSummary("20437.00", "16500.00", "3937.00");
        assertThat(stockPosition("XYZ")).isNull();
    }

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac5_expiredFirstThenAssignmentIsRejected() throws Exception {
        scenarioP();
        send(post(TRADES + "/expirations"), expireXyz(3), 201);
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-20", null), "NO_POSITION_TO_ASSIGN",
                "2026-11-20 16:00 时该合约（XYZ 2026-11-20 45 P）没有持仓可被指派（已于 2026-11-20 16:00 到期作废；"
                        + "如果实际是被指派，请先删除该到期作废）");
        assignRejected(assignBody("XYZ", "45", "PUT", 2, "2026-11-19", null), "CLOSE_EXCEEDS_POSITION",
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废");
        assertSummary("20437.00", "3000.00", "17437.00");
        assertThat(getJson("/api/stock-positions").get("positionCount").asInt()).isZero();
    }

    // ---------------------------------------------------------------- AC 6

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac6_deleteRestoresEverythingAndIsAudited() throws Exception {
        scenarioP();
        JsonNode r = assign(3, "2026-11-20", "0.00");
        long id = r.get("id").asLong();
        long stockId = r.get("assignment").get("stockTradeId").asLong();

        rejected(put(TRADES + "/" + id), editBody(), "ASSIGNMENT_NOT_EDITABLE", "被指派不能修改，记错请删除后重新记录");
        ObjectNode stockEdit = json.createObjectNode();
        stockEdit.put("shares", 300);
        stockEdit.put("amount", "13000.00");
        stockEdit.put("tradedAtLocal", "2026-11-20T16:00");
        rejected(put("/api/stock-trades/" + stockId), stockEdit, "STOCK_TRADE_FROM_ASSIGNMENT",
                "该股票记录由被指派生成，请在交易列表删除对应的被指派记录");
        rejected(delete("/api/stock-trades/" + stockId), null, "STOCK_TRADE_FROM_ASSIGNMENT",
                "该股票记录由被指派生成，请在交易列表删除对应的被指派记录");
        ObjectNode direct = json.createObjectNode();
        direct.put("type", "ASSIGN_BUY");
        direct.put("symbol", "XYZ");
        direct.put("shares", 100);
        direct.put("amount", "100.00");
        direct.put("tradedAtLocal", "2026-11-20T16:00");
        JsonNode bad = send(post("/api/stock-trades"), direct, 400);
        assertThat(bad.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows(bad.get("details"), "field", "message")).containsExactly("type 指派买入由被指派生成，不能直接录入");
        assertThat(auditCount()).isZero();

        call(delete(TRADES + "/" + id), 204);
        assertSummary("20437.00", "16500.00", "3937.00");
        JsonNode xyz = position(XYZ);
        assertThat(xyz.get("contracts").asInt()).isEqualTo(3);
        assertThat(xyz.get("lotCount").asInt()).isEqualTo(2);
        assertThat(xyz.get("frozenCash").asText()).isEqualTo("13500.00");
        assertThat(xyz.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(getJson("/api/stock-positions").get("positionCount").asInt()).isZero();
        assertThat(getJson("/api/stock-trades").get("totalItems").asInt()).isZero();
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
        for (JsonNode l : getJson("/api/cash/ledger?size=100").get("items")) {
            assertThat(l.get("sourceType").asText()).isNotEqualTo("OPTION_ASSIGN");
        }

        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("operation").asText()).isEqualTo("DELETE");
        assertThat(audit.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(audit.get("objectId").asLong()).isEqualTo(id);
        assertThat(audit.get("summary").asText()).isEqualTo("被指派 XYZ 2026-11-20 45 P 3 张（买入 300 股 XYZ）");
        JsonNode before = audit.get("before");
        assertThat(rows(json.createArrayNode().add(before), "action", "contracts", "multiplier", "assignmentDate",
                "tradedAtLocal", "assignmentFee", "netAmount", "shares", "purchaseAmount", "stockCost", "stockTradeId",
                "cashChange", "frozenChange"))
                .containsExactly("ASG 3 100 2026-11-20 2026-11-20T16:00:00 0.00 0.00 300 13500.00 13130.00 " + stockId
                        + " -13500.00 -13500.00");

        JsonNode again = assign(3, "2026-11-20", "0.00");
        assertThat(again.get("id").asLong()).isNotEqualTo(id);
        assertThat(again.get("hasAuditLog").asBoolean()).isFalse();
        assertThat(again.get("assignment").get("stockCost").asText()).isEqualTo("13130.00");
        assertSummary("6937.00", "3000.00", "3937.00");

        sell("XYZ", 100, "4400.00", "2026-11-20T16:00", 201);
        int audits = auditCount();
        rejected(delete(TRADES + "/" + again.get("id").asLong()), null, "SHARES_INSUFFICIENT",
                "删除后 2026-11-20 16:00 的卖出 XYZ 100 股将超过当时持股 0 股");
        assertThat(auditCount()).isEqualTo(audits);
        assertThat(stockPosition("XYZ").get("shares").asLong()).isEqualTo(200);
    }

    private ObjectNode editBody() {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", 3);
        n.put("netAmount", "1.00");
        n.put("tradedAtLocal", "2026-11-20T16:00");
        return n;
    }

    // ---------------------------------------------------------------- AC 7

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac7_changesToEarlierTradesReplayTheAssignment() throws Exception {
        scenarioP();
        JsonNode r = assign(3, "2026-11-20", "0.00");
        long id = r.get("id").asLong();
        long stockId = r.get("assignment").get("stockTradeId").asLong();

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "135.00");
        edit.put("tradedAtLocal", "2026-11-05T10:00");
        send(put(TRADES + "/" + p2), edit, 200);
        JsonNode after = getJson(TRADES + "/" + id).get("assignment");
        assertThat(after.get("stockCost").asText()).isEqualTo("13125.00");
        assertThat(after.get("stockCostPerShare").asText()).isEqualTo("43.7500");
        assertThat(getJson("/api/stock-trades/" + stockId).get("amount").asText()).isEqualTo("13125.00");
        assertThat(stockPosition("XYZ").get("totalCost").asText()).isEqualTo("13125.00");
        assertSummary("6942.00", "3000.00", "3942.00");
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(rows(audit.get("sideEffects"), "text"))
                .containsExactly("被指派 #" + id + " 股票成本 13,130.00 → 13,125.00");
        int audits = auditCount();

        rejected(delete(TRADES + "/" + p2), null, "CLOSE_EXCEEDS_POSITION",
                "该开仓被 2026-11-20 16:00 的被指派 3 张覆盖（删除后被指派将超过当时持仓 2 张），请先删除该被指派");
        ObjectNode btc = json.createObjectNode();
        btc.put("symbol", "XYZ");
        btc.put("expirationDate", "2026-11-20");
        btc.put("strike", "45");
        btc.put("optionType", "PUT");
        btc.put("action", "BTC");
        btc.put("contracts", 2);
        btc.put("netAmount", "50.00");
        btc.put("tradedAtLocal", "2026-11-19T10:00");
        rejected(post(TRADES + "/closes"), btc, "CLOSE_EXCEEDS_POSITION",
                "补录后该合约（XYZ 2026-11-20 45 P）在 2026-11-20 16:00 的被指派 3 张将超过当时持仓 1 张，请先删除该被指派");
        assertThat(auditCount()).isEqualTo(audits);
        assertSummary("6942.00", "3000.00", "3942.00");
    }

    // ---------------------------------------------------------------- AC 8

    private void newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
    }

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac8_feeNeedsAvailableCash() throws Exception {
        newAccount("新账户");
        transfer("DEPOSIT", "4500.00", "2026-11-02T10:00");
        open("STO", "QRTV", "2026-11-20", "45", "PUT", 1, "50.00", "2026-11-03T10:00", null);
        long w = transfer("WITHDRAWAL", "50.00", "2026-11-10T10:00");
        assertSummary("4500.00", "4500.00", "0.00");

        assignRejected(assignBody("QRTV", "45", "PUT", 1, "2026-11-20", "5.00"), "CASH_INSUFFICIENT",
                "可用现金不足：需要 5.00；2026-11-20 16:00 时可用现金将变为 −5.00");
        JsonNode r = send(post(ASSIGN), assignBody("QRTV", "45", "PUT", 1, "2026-11-20", "0.00"), 201);
        assertSummary("0.00", "0.00", "0.00");
        assertThat(r.get("assignment").get("stockCost").asText()).isEqualTo("4450.00");
        JsonNode sp = stockPosition("QRTV");
        assertThat(sp.get("shares").asLong()).isEqualTo(100);
        assertThat(sp.get("averageCost").asText()).isEqualTo("44.5000");

        call(delete("/api/cash/transfers/" + w), 204);
        assertSummary("50.00", "0.00", "50.00");
    }

    // ---------------------------------------------------------------- AC 9

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac9_multiplierOneAndThreeDecimalStrike() throws Exception {
        newAccount("新账户");
        transfer("DEPOSIT", "100.00", "2026-11-02T10:00");
        open("STO", "QFZ", "2026-11-20", "12.345", "PUT", 1, "0.50", "2026-11-03T10:00", 1);
        open("STO", "QFZ", "2026-11-20", "12.345", "PUT", 1, "0.50", "2026-11-04T10:00", 1);
        assertSummary("101.00", "24.70", "76.30");

        JsonNode r = send(post(ASSIGN), assignBody("QFZ", "12.345", "PUT", 2, "2026-11-20", null), 201);
        JsonNode a = r.get("assignment");
        assertThat(a.get("purchaseAmount").asText()).isEqualTo("24.69");
        assertThat(r.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(a.get("stockCost").asText()).isEqualTo("23.69");
        assertThat(a.get("stockCostPerShare").asText()).isEqualTo("11.8450");
        assertSummary("76.31", "0.00", "76.31");
        assertThat(getJson("/api/cash/ledger").get("items").get(0).get("description").asText())
                .isEqualTo("被指派 2 张 QFZ 2026-11-20 12.345 P（multiplier 1），买入 2 股 QFZ");

        call(delete(TRADES + "/" + r.get("id").asLong()), 204);
        JsonNode one = send(post(ASSIGN), assignBody("QFZ", "12.345", "PUT", 1, "2026-11-20", null), 201);
        assertThat(one.get("assignment").get("purchaseAmount").asText()).isEqualTo("12.35");
        assertThat(one.get("assignment").get("stockCost").asText()).isEqualTo("11.85");
        assertSummary("88.65", "12.35", "76.30");
    }

    // ---------------------------------------------------------------- AC 10

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac10_accountsAreIsolatedAndFieldsValidated() throws Exception {
        scenarioP();
        JsonNode def = assign(3, "2026-11-20", "0.00");
        long defId = def.get("id").asLong();
        long defStock = def.get("assignment").get("stockTradeId").asLong();

        newAccount("小账户");
        transfer("DEPOSIT", "5000.00", "2026-11-02T10:00");
        open("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "100.00", "2026-11-04T10:00", null);
        assertSummary("5100.00", "4500.00", "600.00");
        assertThat(buttons(XYZ)).containsExactly(false, false, true, true);
        assertThat(stockPosition("XYZ")).isNull();

        call(delete(TRADES + "/" + defId), 404);
        call(get("/api/stock-trades/" + defStock), 404);
        JsonNode small = assign(1, "2026-11-20", null);
        assertThat(stockPosition("XYZ").get("totalCost").asText()).isEqualTo("4400.00");
        assertThat(small.get("assignment").get("stockCost").asText()).isEqualTo("4400.00");

        account = "1";
        JsonNode sp = stockPosition("XYZ");
        assertThat(sp.get("shares").asLong()).isEqualTo(300);
        assertThat(sp.get("totalCost").asText()).isEqualTo("13130.00");

        // field format: nothing saved
        int trades = getJson(TRADES).get("totalItems").asInt();
        fieldError(assignBody("XYZ", "45", "PUT", 3, "2026-11-20", "-1.00"), "assignmentFee", "指派费不能为负数");
        fieldError(assignBody("XYZ", "45", "PUT", 3, "2026-11-20", "1.234"), "assignmentFee", "指派费最多 2 位小数");
        fieldError(assignBody("XYZ", "45", "PUT", 3, "2026-11-20", "10000000000000.00"), "assignmentFee", "指派费过大");
        ObjectNode noDate = assignBody("XYZ", "45", "PUT", 3, "2026-11-20", null);
        noDate.remove("assignmentDate");
        fieldError(noDate, "assignmentDate", "指派日期不能为空");
        ObjectNode longNote = assignBody("XYZ", "45", "PUT", 3, "2026-11-20", null);
        longNote.put("note", "x".repeat(501));
        fieldError(longNote, "note", "备注最多 500 个字符");
        fieldError(assignBody("XYZ", "45", "PUT", 0, "2026-11-20", null), "contracts", "张数必须是正整数");
        assertThat(getJson(TRADES).get("totalItems").asInt()).isEqualTo(trades);
    }

    private void fieldError(ObjectNode body, String field, String message) throws Exception {
        JsonNode err = send(post(ASSIGN), body, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows(err.get("details"), "field", "message")).containsExactly(field + " " + message);
    }
}
