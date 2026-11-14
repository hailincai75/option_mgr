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
 * FEAT-041 acceptance criteria (backend part), scenario C. Fictional tickers only. The clock is set per test (noted
 * on each); 2026-11-20 is a Friday, 2026-11-23 the Monday after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class CallAssignmentControllerTest {

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
        TestClock callAssignmentTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String ASSIGN = "/api/option-trades/assignments";
    static final String STOCKS = "/api/stock-trades";
    static final String C9 = "IXQZ 2026-11-20 9 C";
    static final String C10 = "IXQZ 2026-11-20 10 C";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private String account = "1";
    private long s1;
    private long s2;
    private long c1;
    private long c2;

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

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers"), n, 201);
    }

    private long open(String action, String symbol, String strike, int contracts, String net, String nyTime,
                      Integer multiplier) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", "2026-11-20");
        n.put("strike", strike);
        n.put("optionType", "CALL");
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        return send(post(com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(n)), n, 201).get("id").asLong();
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

    /** Scenario C in the current account (clock must be &gt;= 2026-11-06 11:00). */
    private void scenarioC() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        s1 = stock("BUY", "IXQZ", 150, "1200.00", "2026-11-03T10:00");
        s2 = stock("BUY", "IXQZ", 150, "1350.00", "2026-11-04T10:00");
        c1 = open("STO", "IXQZ", "9", 2, "60.00", "2026-11-05T10:00", null);
        c2 = open("STO", "IXQZ", "9", 1, "25.00", "2026-11-06T10:00", null);
        open("BTO", "IXQZ", "10", 1, "15.00", "2026-11-06T11:00", null);
        assertSummary("7520.00", "0.00", "7520.00");
        assertStock("IXQZ", 300, "2550.00");
    }

    /** The "小账户" of AC 4: a short call without shares. */
    private void smallAccount() throws Exception {
        newAccount("小账户");
        deposit("1000.00", "2026-11-02T10:00");
        open("STO", "IXQZ", "9", 1, "20.00", "2026-11-05T10:00", null);
    }

    private void newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
    }

    private ObjectNode assignBody(String symbol, String strike, int contracts, String date, String fee) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", "2026-11-20");
        n.put("strike", strike);
        n.put("optionType", "CALL");
        n.put("contracts", contracts);
        n.put("assignmentDate", date);
        if (fee != null) {
            n.put("assignmentFee", fee);
        }
        return n;
    }

    private JsonNode assign(int contracts, String date, String fee) throws Exception {
        return send(post(ASSIGN), assignBody("IXQZ", "9", contracts, date, fee), 201);
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

    private void assertStock(String symbol, long shares, String totalCost) throws Exception {
        JsonNode p = stockPosition(symbol);
        assertThat(p).as(symbol).isNotNull();
        assertThat(p.get("shares").asLong()).isEqualTo(shares);
        assertThat(p.get("totalCost").asText()).isEqualTo(totalCost);
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

    private static List<String> soldLots(JsonNode lots) {
        return rows(lots, "tradeId", "type", "shares", "cost");
    }

    // ---------------------------------------------------------------- AC 1

    /** Clocks 2026-11-17 10:00, 2026-11-20 16:30, 2026-11-23 10:00. */
    @Test
    void ac1_buttonsDialogDataAndLongRejected() throws Exception {
        clock.set(TUE_17_10AM);
        scenarioC();
        assertThat(buttons(C9)).containsExactly(true, false, false, true);
        assertThat(buttons(C10)).containsExactly(true, false, false, false);
        assertThat(position(C10).get("underlyingShares").isNull()).isTrue();
        assertThat(position(C9).get("underlyingShares").asLong()).isEqualTo(300);

        clock.set(FRI_20_1630);
        assertThat(buttons(C9)).containsExactly(false, true, false, true);

        clock.set(MON_23_10AM);
        JsonNode p = position(C9);
        assertThat(p.get("assignable").asBoolean()).isTrue();
        assertThat(json.writeValueAsString(p.get("assignChoices")))
                .isEqualTo("[{\"contracts\":2,\"shares\":200},{\"contracts\":3,\"shares\":300}]");
        assertThat(p.get("assignmentDateMax").asText()).isEqualTo("2026-11-20");
        assertThat(p.get("assignmentDateDefault").asText()).isEqualTo("2026-11-20");
        assertThat(p.get("underlyingShares").asLong()).isEqualTo(300);
        assertThat(p.get("frozenCash").asText()).isEqualTo("0.00");

        assignRejected(assignBody("IXQZ", "10", 1, "2026-11-20", null), "ASSIGN_LONG_POSITION",
                "多头持仓不能被指派（主动行权见 FEAT-014）");
    }

    // ---------------------------------------------------------------- AC 2

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac2_assignAllThreeContracts() throws Exception {
        scenarioC();
        JsonNode r = assign(3, "2026-11-20", "0.00");
        long id = r.get("id").asLong();
        assertThat(rows(json.createArrayNode().add(r), "action", "optionType", "contracts", "multiplier", "netAmount",
                "tradedAt", "tradedAtLocal", "cashChange", "frozenChange", "realizedPnl", "hasAuditLog"))
                .containsExactly("ASG CALL 3 100 0.00 2026-11-20T21:00:00Z 2026-11-20T16:00:00 2700.00 0.00 0.00 false");
        assertThat(rows(r.get("coveredLots"), "tradeId", "contracts", "netAmount", "frozen"))
                .containsExactly(c1 + " 2 60.00 0.00", c2 + " 1 25.00 0.00");
        JsonNode a = r.get("assignment");
        assertThat(rows(json.createArrayNode().add(a), "assignmentDate", "assignmentFee", "premium", "shares",
                "saleAmount", "proceeds", "soldCost", "stockRealizedPnl"))
                .containsExactly("2026-11-20 0.00 85.00 300 2700.00 2785.00 2550.00 235.00");
        assertThat(a.get("purchaseAmount").isNull()).isTrue();
        assertThat(a.get("stockCost").isNull()).isTrue();
        assertThat(a.get("stockCostPerShare").isNull()).isTrue();
        assertThat(soldLots(a.get("soldLots"))).containsExactly(s1 + " BUY 150 1200.00", s2 + " BUY 150 1350.00");
        long stockId = a.get("stockTradeId").asLong();
        assertThat(getJson(TRADES + "/" + id)).isEqualTo(r);

        assertSummary("10220.00", "0.00", "10220.00");
        assertThat(position(C9)).isNull();
        assertThat(stockPosition("IXQZ")).isNull();

        // the generated sale
        JsonNode sale = getJson(STOCKS + "/" + stockId);
        assertThat(rows(json.createArrayNode().add(sale), "type", "symbol", "shares", "amount", "cashChange",
                "pricePerShare", "tradedAtLocal", "soldCost", "realizedPnl", "assignmentTradeId", "editable",
                "deletable", "hasAuditLog"))
                .containsExactly("ASSIGN_SELL IXQZ 300 2785.00 0.00 9.2833 2026-11-20T16:00:00 2550.00 235.00 " + id
                        + " false false false");
        assertThat(sale.get("ledgerEntryId").isNull()).isTrue();
        assertThat(sale.get("note").isNull()).isTrue();
        assertThat(soldLots(sale.get("soldLots"))).containsExactly(s1 + " BUY 150 1200.00", s2 + " BUY 150 1350.00");
        assertThat(getJson(STOCKS + "?type=ASSIGN_SELL").get("totalItems").asInt()).isEqualTo(1);

        // ledger
        JsonNode top = getJson("/api/cash/ledger?size=100").get("items").get(0);
        assertThat(rows(json.createArrayNode().add(top), "sourceType", "sourceId", "amount", "frozenChange",
                "balanceAfter")).containsExactly("OPTION_ASSIGN " + id + " 2700.00 0.00 10220.00");
        assertThat(top.get("description").asText()).isEqualTo("被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ");

        // closed trades: 2 rows (Q100), stock row on top
        JsonNode closed = getJson("/api/closed-trades");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(2);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("235.00");
        assertThat(rows(closed.get("items"), "type", "id", "action", "realizedPnl"))
                .containsExactly("STOCK " + stockId + " ASSIGN_SELL 235.00", "OPTION " + id + " ASG 0.00");
        JsonNode stockRow = closed.get("items").get(0);
        assertThat(rows(json.createArrayNode().add(stockRow), "shares", "openingNetTotal", "closeNetAmount",
                "assignmentTradeId")).containsExactly("300 -2550.00 2785.00 " + id);
        assertThat(stockRow.get("assignment").isNull()).isTrue();
        JsonNode optionRow = closed.get("items").get(1);
        assertThat(optionRow.get("openingNetTotal").asText()).isEqualTo("85.00");
        assertThat(optionRow.get("assignmentTradeId").isNull()).isTrue();
        assertThat(json.writeValueAsString(optionRow.get("assignment")))
                .isEqualTo("{\"shares\":300,\"stockCost\":null,\"proceeds\":\"2785.00\",\"stockTradeId\":" + stockId + "}");
        assertThat(getJson("/api/closed-trades?type=STOCK").get("filteredCount").asInt()).isEqualTo(1);
        assertThat(getJson("/api/closed-trades?action=ASG").get("filteredCount").asInt()).isEqualTo(1);

        JsonNode pnl = getJson("/api/pnl-summary");
        assertThat(pnl.get("closedCount").asInt()).isEqualTo(2);
        assertThat(pnl.get("realizedPnlTotal").asText()).isEqualTo("235.00");
        assertThat(json.writeValueAsString(pnl.get("bySymbol"))).isEqualTo("[{\"symbol\":\"IXQZ\",\"closedCount\":2,"
                + "\"realizedPnl\":\"235.00\",\"option\":{\"closedCount\":1,\"realizedPnl\":\"0.00\"},"
                + "\"stock\":{\"closedCount\":1,\"realizedPnl\":\"235.00\"}}]");
        JsonNode dash = getJson("/api/dashboard");
        assertThat(dash.get("realizedPnl").get("closedCount").asInt()).isEqualTo(2);
        assertThat(dash.get("realizedPnl").get("total").asText()).isEqualTo("235.00");
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 3

    /** Clocks 2026-11-19 10:00, then 2026-11-23 10:00. */
    @Test
    void ac3_earlyAssignmentFeeAndPartialStockLot() throws Exception {
        clock.set(THU_19_10AM);
        scenarioC();
        assignRejected(assignBody("IXQZ", "9", 1, "2026-11-18", null), "CLOSE_NOT_WHOLE_LOTS",
                "必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
        JsonNode r = assign(2, "2026-11-18", "5.00");
        JsonNode a = r.get("assignment");
        assertThat(r.get("cashChange").asText()).isEqualTo("1795.00");
        assertThat(rows(json.createArrayNode().add(a), "assignmentFee", "premium", "saleAmount", "proceeds",
                "soldCost", "stockRealizedPnl")).containsExactly("5.00 60.00 1800.00 1855.00 1650.00 205.00");
        assertThat(soldLots(a.get("soldLots"))).containsExactly(s1 + " BUY 150 1200.00", s2 + " BUY 50 450.00");
        assertSummary("9315.00", "0.00", "9315.00");
        assertStock("IXQZ", 100, "900.00");
        assertThat(position(C9).get("contracts").asInt()).isEqualTo(1);

        clock.set(MON_23_10AM);
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "IXQZ");
        exp.put("expirationDate", "2026-11-20");
        exp.put("strike", "9");
        exp.put("optionType", "CALL");
        exp.put("contracts", 1);
        assertThat(send(post(TRADES + "/expirations"), exp, 201).get("realizedPnl").asText()).isEqualTo("25.00");
    }

    // ---------------------------------------------------------------- AC 4

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac4_sharesMustBeHeldAtTheAssignmentTime() throws Exception {
        smallAccount();
        String insufficient = "持股不足：被指派需要 100 股 IXQZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）";
        assignRejected(assignBody("IXQZ", "9", 1, "2026-11-20", null), "SHARES_INSUFFICIENT", insufficient);
        long buy = stock("BUY", "IXQZ", 100, "900.00", "2026-11-21T10:00");
        assignRejected(assignBody("IXQZ", "9", 1, "2026-11-20", null), "SHARES_INSUFFICIENT", insufficient);
        call(delete(STOCKS + "/" + buy), 204);
        stock("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-02");

        // ASSIGNMENT_PROCEEDS_NOT_POSITIVE before the share and cash checks
        assignRejected(assignBody("IXQZ", "9", 1, "2026-11-20", "925.00"), "ASSIGNMENT_PROCEEDS_NOT_POSITIVE",
                "被指派后股票卖出实收将为 −5.00，请检查指派费");

        JsonNode r = assign(1, "2026-11-20", null);
        assertThat(rows(json.createArrayNode().add(r.get("assignment")), "proceeds", "soldCost", "stockRealizedPnl"))
                .containsExactly("920.00 800.00 120.00");
        assertThat(r.get("cashChange").asText()).isEqualTo("900.00");
        assertSummary("1920.00", "0.00", "1920.00");
    }

    // ---------------------------------------------------------------- AC 5

    /** Clock 2026-11-25 10:00. */
    @Test
    void ac5_laterSaleMustStayCovered() throws Exception {
        clock.set(WED_25_10AM);
        scenarioC();
        // FEAT-041 behaviour on calls entered before FEAT-039: LEGACY lots lock no shares, so the later sale is kept
        jdbc.update("UPDATE option_trade SET coverage_status = 'LEGACY' WHERE coverage_status IS NOT NULL");
        stock("SELL", "IXQZ", 100, "1000.00", "2026-11-24T10:00");
        assignRejected(assignBody("IXQZ", "9", 3, "2026-11-20", null), "SHARES_INSUFFICIENT",
                "补录后 2026-11-24 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股");
        JsonNode r = assign(2, "2026-11-20", null);
        assertThat(r.get("assignment").get("soldCost").asText()).isEqualTo("1650.00");
        assertThat(stockPosition("IXQZ")).isNull(); // the later sale takes the last 100 shares
    }

    // ---------------------------------------------------------------- AC 6

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac6_deleteRestoresEverythingAndGeneratedRecordsAreLocked() throws Exception {
        scenarioC();
        JsonNode r = assign(3, "2026-11-20", "0.00");
        long id = r.get("id").asLong();
        long stockId = r.get("assignment").get("stockTradeId").asLong();

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 3);
        edit.put("netAmount", "1.00");
        edit.put("tradedAtLocal", "2026-11-20T16:00");
        rejected(put(TRADES + "/" + id), edit, "ASSIGNMENT_NOT_EDITABLE", "被指派不能修改，记错请删除后重新记录");
        ObjectNode stockEdit = json.createObjectNode();
        stockEdit.put("shares", 300);
        stockEdit.put("amount", "2700.00");
        stockEdit.put("tradedAtLocal", "2026-11-20T16:00");
        rejected(put(STOCKS + "/" + stockId), stockEdit, "STOCK_TRADE_FROM_ASSIGNMENT",
                "该股票记录由被指派生成，请在交易列表删除对应的被指派记录");
        rejected(delete(STOCKS + "/" + stockId), null, "STOCK_TRADE_FROM_ASSIGNMENT",
                "该股票记录由被指派生成，请在交易列表删除对应的被指派记录");
        JsonNode bad = send(post(STOCKS), stockBody("ASSIGN_SELL", "IXQZ", 100, "100.00", "2026-11-20T16:00"), 400);
        assertThat(rows(bad.get("details"), "field", "message")).containsExactly("type 指派卖出由被指派生成，不能直接录入");
        assertThat(auditCount()).isZero();

        call(delete(TRADES + "/" + id), 204);
        assertSummary("7520.00", "0.00", "7520.00");
        assertThat(position(C9).get("contracts").asInt()).isEqualTo(3);
        assertStock("IXQZ", 300, "2550.00");
        assertThat(rows(stockPosition("IXQZ").get("lots"), "tradeId")).containsExactly(s1 + "", s2 + "");
        assertThat(getJson(STOCKS + "?type=ASSIGN_SELL").get("totalItems").asInt()).isZero();
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
        for (JsonNode l : getJson("/api/cash/ledger?size=100").get("items")) {
            assertThat(l.get("sourceType").asText()).isNotEqualTo("OPTION_ASSIGN");
        }
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(audit.get("summary").asText()).isEqualTo("被指派 IXQZ 2026-11-20 9 C 3 张（卖出 300 股 IXQZ）");
        assertThat(rows(json.createArrayNode().add(audit.get("before")), "action", "contracts", "assignmentDate",
                "assignmentFee", "shares", "premium", "saleAmount", "proceeds", "soldCost", "stockRealizedPnl",
                "stockTradeId", "cashChange", "frozenChange"))
                .containsExactly("ASG 3 2026-11-20 0.00 300 85.00 2700.00 2785.00 2550.00 235.00 " + stockId
                        + " 2700.00 0.00");
        assertThat(audit.get("before").has("purchaseAmount")).isFalse();
    }

    // ---------------------------------------------------------------- AC 7

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac7a_editedPremiumChangesTheProceeds() throws Exception {
        scenarioC();
        long id = assign(3, "2026-11-20", "0.00").get("id").asLong();
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "30.00");
        edit.put("tradedAtLocal", "2026-11-06T10:00");
        send(put(TRADES + "/" + c2), edit, 200);
        JsonNode a = getJson(TRADES + "/" + id).get("assignment");
        assertThat(a.get("proceeds").asText()).isEqualTo("2790.00");
        assertThat(a.get("stockRealizedPnl").asText()).isEqualTo("240.00");
        assertThat(getJson(STOCKS + "/" + a.get("stockTradeId").asLong()).get("amount").asText()).isEqualTo("2790.00");
        assertSummary("10225.00", "0.00", "10225.00");
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(rows(audit.get("sideEffects"), "field", "text"))
                .containsExactly("proceeds 被指派 #" + id + " 股票卖出实收 2,785.00 → 2,790.00");
    }

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac7b_backFilledTransferInIsDeliveredFirst() throws Exception {
        scenarioC();
        long id = assign(3, "2026-11-20", "0.00").get("id").asLong();
        long t = stock("TRANSFER_IN", "IXQZ", 100, "700.00", "2026-11-01");
        JsonNode a = getJson(TRADES + "/" + id).get("assignment");
        assertThat(soldLots(a.get("soldLots")))
                .containsExactly(t + " TRANSFER_IN 100 700.00", s1 + " BUY 150 1200.00", s2 + " BUY 50 450.00");
        assertThat(a.get("soldCost").asText()).isEqualTo("2350.00");
        assertThat(a.get("stockRealizedPnl").asText()).isEqualTo("435.00");
        assertStock("IXQZ", 100, "900.00");
        assertThat(auditCount()).isZero();

        // an edited earlier lot changes the delivered cost: a side effect of that stock edit
        ObjectNode edit = json.createObjectNode();
        edit.put("shares", 100);
        edit.put("amount", "600.00");
        edit.put("tradeDate", "2026-11-01");
        send(put(STOCKS + "/" + t), edit, 200);
        JsonNode audit = getJson("/api/audit-log").get("items").get(0);
        assertThat(audit.get("objectType").asText()).isEqualTo("STOCK_TRADE");
        assertThat(rows(audit.get("sideEffects"), "tradeId", "action", "field", "text"))
                .containsExactly(id + " ASG soldCost 被指派 #" + id + " 交出成本 2,350.00 → 2,250.00");
    }

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac7c_changesThatLeaveTooFewSharesAreRejected() throws Exception {
        scenarioC();
        assign(3, "2026-11-20", "0.00");
        int audits = auditCount();
        rejected(delete(STOCKS + "/" + s2), null, "SHARES_INSUFFICIENT",
                "删除后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 150 股，请先删除该被指派");
        JsonNode err = send(post(STOCKS), stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-10T10:00"), 422);
        assertThat(err.get("code").asText()).isEqualTo("SHARES_INSUFFICIENT");
        assertThat(err.get("message").asText())
                .isEqualTo("补录后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 200 股，请先删除该被指派");
        ObjectNode shrink = json.createObjectNode();
        shrink.put("shares", 100);
        shrink.put("amount", "1200.00");
        shrink.put("tradedAtLocal", "2026-11-03T10:00");
        rejected(put(STOCKS + "/" + s1), shrink, "SHARES_INSUFFICIENT",
                "修改后 2026-11-20 16:00 的被指派 3 张 IXQZ 2026-11-20 9 C 需要 300 股，当时持股 250 股，请先删除该被指派");
        assertThat(auditCount()).isEqualTo(audits);
        assertSummary("10220.00", "0.00", "10220.00");
        assertThat(getJson(STOCKS).get("totalItems").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- AC 8

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac8_multiplierOneAndStrikeAmountRoundedDown() throws Exception {
        newAccount("QFZ 账户");
        deposit("100.00", "2026-11-02T10:00");
        stock("TRANSFER_IN", "QFZ", 2, "20.00", "2026-11-02");
        open("STO", "QFZ", "12.345", 1, "0.50", "2026-11-03T10:00", 1);
        JsonNode r = send(post(ASSIGN), assignBody("QFZ", "12.345", 1, "2026-11-20", null), 201);
        assertThat(rows(json.createArrayNode().add(r.get("assignment")), "shares", "saleAmount", "proceeds",
                "soldCost", "stockRealizedPnl")).containsExactly("1 12.34 12.84 10.00 2.84");
        assertThat(r.get("cashChange").asText()).isEqualTo("12.34");
        assertThat(getJson("/api/cash/ledger").get("items").get(0).get("description").asText())
                .isEqualTo("被指派 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），卖出 1 股 QFZ");
        assertStock("QFZ", 1, "10.00");
    }

    // ---------------------------------------------------------------- AC 9

    /** Clock 2026-11-23 10:00. */
    @Test
    void ac9_accountsAreIsolatedAndFieldsValidated() throws Exception {
        scenarioC();
        long defId = assign(3, "2026-11-20", "0.00").get("id").asLong();

        smallAccount();
        assertThat(position(C9).get("contracts").asInt()).isEqualTo(1);
        assertThat(position(C9).get("underlyingShares").asLong()).isZero();
        assignRejected(assignBody("IXQZ", "9", 1, "2026-11-20", null), "SHARES_INSUFFICIENT",
                "持股不足：被指派需要 100 股 IXQZ，2026-11-20 16:00 时持有 0 股。请先录入股票（转入或更早的买入）");
        call(delete(TRADES + "/" + defId), 404);

        int trades = getJson(TRADES).get("totalItems").asInt();
        fieldError(assignBody("IXQZ", "9", 1, "2026-11-20", "-1.00"), "assignmentFee", "指派费不能为负数");
        fieldError(assignBody("IXQZ", "9", 1, "2026-11-20", "1.234"), "assignmentFee", "指派费最多 2 位小数");
        ObjectNode noDate = assignBody("IXQZ", "9", 1, "2026-11-20", null);
        noDate.remove("assignmentDate");
        fieldError(noDate, "assignmentDate", "指派日期不能为空");
        ObjectNode longNote = assignBody("IXQZ", "9", 1, "2026-11-20", null);
        longNote.put("note", "x".repeat(501));
        fieldError(longNote, "note", "备注最多 500 个字符");
        fieldError(assignBody("IXQZ", "9", 0, "2026-11-20", null), "contracts", "张数必须是正整数");
        assertThat(getJson(TRADES).get("totalItems").asInt()).isEqualTo(trades);

        account = "1";
        assertThat(getJson(TRADES + "/" + defId).get("action").asText()).isEqualTo("ASG");
    }

    private void fieldError(ObjectNode body, String field, String message) throws Exception {
        JsonNode err = send(post(ASSIGN), body, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows(err.get("details"), "field", "message")).containsExactly(field + " " + message);
    }
}
