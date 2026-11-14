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

/**
 * FEAT-038 acceptance criteria (backend part): STO call allowed, nothing frozen, BTC only checks the cash paid.
 * "Now" is 2026-11-12 10:00 New York (15:00Z); account 1 unless stated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class ShortCallControllerTest {

    static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock shortCallTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** Account id used by the helpers. */
    private String account = "1";

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
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

    private ObjectNode body(String action, String symbol, String exp, String strike, String type, int contracts,
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

    private static String url(ObjectNode body) {
        return body.get("action").asText().endsWith("C") ? "/api/option-trades/closes"
                : com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(body);
    }

    private JsonNode ok(ObjectNode body) throws Exception {
        return send(post(url(body)), body, 201);
    }

    private JsonNode rejected(ObjectNode body, String code) throws Exception {
        JsonNode err = send(post(url(body)), body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        return err;
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private long t1;
    private long t3;
    private long t4a;
    private long t4b;

    /** Scenario IXQZ up to #3. */
    private void ixqzOpens() throws Exception {
        deposit("10944.65", "2026-11-09T09:00");
        t1 = ok(body("STO", "IXQZ", "2026-11-20", "7.5", "CALL", 1, "14.34", "2026-11-09T13:40")).get("id").asLong();
        ok(body("STO", "IXQZ", "2026-12-18", "9", "CALL", 1, "18.34", "2026-11-10T12:43"));
        t3 = ok(body("STO", "IXQZ", "2026-12-18", "10", "CALL", 1, "20.34", "2026-11-11T10:26")).get("id").asLong();
    }

    private ObjectNode roll4a() {
        return body("BTC", "IXQZ", "2026-11-20", "7.5", "CALL", 1, "35.01", "2026-11-11T12:21");
    }

    private ObjectNode roll4b() {
        return body("STO", "IXQZ", "2026-12-18", "9", "CALL", 1, "37.34", "2026-11-11T12:21");
    }

    private void scenarioIxqz() throws Exception {
        ixqzOpens();
        t4a = ok(roll4a()).get("id").asLong();
        t4b = ok(roll4b()).get("id").asLong();
    }

    /** The full results of acceptance 2 (also expected for the reversed roll order). */
    private void assertScenarioResults() throws Exception {
        assertSummary("11000.00", "0.00", "11000.00");

        JsonNode ps = getJson("/api/option-positions");
        assertThat(ps.get("positionCount").asInt()).isEqualTo(2);
        assertThat(ps.get("frozenTotal").asText()).isEqualTo("0.00");
        JsonNode p9 = ps.get("positions").get(0);
        assertThat(p9.get("contractLabel").asText()).isEqualTo("IXQZ 2026-12-18 9 C");
        assertThat(p9.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p9.get("optionType").asText()).isEqualTo("CALL");
        assertThat(p9.get("contracts").asInt()).isEqualTo(2);
        assertThat(p9.get("openingNetTotal").asText()).isEqualTo("55.68");
        assertThat(p9.get("averageNetPerContract").asText()).isEqualTo("27.84");
        assertThat(p9.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(p9.get("dte").asInt()).isEqualTo(36);
        assertThat(p9.get("lotCount").asInt()).isEqualTo(2);
        assertThat(p9.get("closeAction").asText()).isEqualTo("BTC");
        assertThat(p9.get("closable").asBoolean()).isTrue();
        assertThat(p9.get("allowedCloseContracts").toString()).isEqualTo("[1,2]");
        JsonNode lots = p9.get("lots");
        assertThat(lots.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-11-10T12:43:00");
        assertThat(lots.get(0).get("remainingNetAmount").asText()).isEqualTo("18.34");
        assertThat(lots.get(0).get("remainingFrozen").asText()).isEqualTo("0.00");
        assertThat(lots.get(1).get("tradedAtLocal").asText()).isEqualTo("2026-11-11T12:21:00");
        assertThat(lots.get(1).get("remainingNetAmount").asText()).isEqualTo("37.34");
        JsonNode p10 = ps.get("positions").get(1);
        assertThat(p10.get("contractLabel").asText()).isEqualTo("IXQZ 2026-12-18 10 C");
        assertThat(p10.get("contracts").asInt()).isEqualTo(1);
        assertThat(p10.get("openingNetTotal").asText()).isEqualTo("20.34");
        assertThat(p10.get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(p10.get("dte").asInt()).isEqualTo(36);

        JsonNode closed = getJson("/api/closed-trades");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(1);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("-20.67");
        JsonNode c = closed.get("items").get(0);
        assertThat(c.get("tradedAtLocal").asText()).isEqualTo("2026-11-11T12:21:00");
        assertThat(c.get("contractLabel").asText()).isEqualTo("IXQZ 2026-11-20 7.5 C");
        assertThat(c.get("action").asText()).isEqualTo("BTC");
        assertThat(c.get("closedDirection").asText()).isEqualTo("SHORT");
        assertThat(c.get("openingNetTotal").asText()).isEqualTo("14.34");
        assertThat(c.get("closeNetAmount").asText()).isEqualTo("-35.01");
        assertThat(c.get("realizedPnl").asText()).isEqualTo("-20.67");

        JsonNode summary = getJson("/api/pnl-summary");
        assertThat(summary.get("bySymbol").get(0).get("symbol").asText()).isEqualTo("IXQZ");
        assertThat(summary.get("bySymbol").get(0).get("realizedPnl").asText()).isEqualTo("-20.67");
        assertThat(summary.get("byMonth").get(0).get("month").asText()).isEqualTo("2026-11");
        assertThat(summary.get("byMonth").get(0).get("closedCount").asInt()).isEqualTo(1);

        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("cash").get("frozenCash").asText()).isEqualTo("0.00");
        assertThat(d.get("realizedPnl").get("total").asText()).isEqualTo("-20.67");
        assertThat(d.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("-20.67");
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(2);
        assertThat(d.get("positions").get("frozenTotal").asText()).isEqualTo("0.00");

        JsonNode list = getJson("/api/option-trades");
        assertThat(list.get("totalItems").asInt()).isEqualTo(5);
        list.get("items").forEach(i -> assertThat(i.get("frozenChange").asText()).isEqualTo("0.00"));
    }

    // ---------------------------------------------------------------- AC 1-3

    @Test
    void ac1_stoCallIsAllowedAndFreezesNothing() throws Exception {
        deposit("10944.65", "2026-11-09T09:00");
        JsonNode t = ok(body("STO", "IXQZ", "2026-11-20", "7.5", "CALL", 1, "14.34", "2026-11-09T13:40"));
        assertThat(t.get("cashChange").asText()).isEqualTo("14.34");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("10958.99", "0.00", "10958.99");
        JsonNode row = getJson("/api/cash/ledger").get("items").get(0);
        assertThat(row.get("sourceType").asText()).isEqualTo("OPTION_OPEN");
        assertThat(row.get("amount").asText()).isEqualTo("14.34");
        assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(row.get("description").asText()).isEqualTo("STO 1 张 IXQZ 2026-11-20 7.5 C");
    }

    @Test
    void ac2_fullScenario() throws Exception {
        scenarioIxqz();
        assertScenarioResults();
        assertThat(getJson("/api/option-trades").get("items").get(0).get("id").asLong()).isEqualTo(t4b);
    }

    @Test
    void ac3_rollInReversedEntryOrder() throws Exception {
        ixqzOpens();
        long b = ok(roll4b()).get("id").asLong();
        long a = ok(roll4a()).get("id").asLong();
        assertScenarioResults();
        JsonNode items = getJson("/api/option-trades").get("items");
        assertThat(items.get(0).get("id").asLong()).isEqualTo(a);
        assertThat(items.get(1).get("id").asLong()).isEqualTo(b);
    }

    // ---------------------------------------------------------------- AC 4-7

    private void newEmptyAccount() throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", "空账户");
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
    }

    @Test
    void ac4and5_stoCallWithoutCashAndBtcChecksOnlyThePayment() throws Exception {
        newEmptyAccount();
        assertSummary("0.00", "0.00", "0.00");
        ok(body("STO", "XYZ", "2026-12-18", "50", "CALL", 1, "25.00", "2026-11-10T10:00"));
        assertSummary("25.00", "0.00", "25.00");

        ObjectNode btc = body("BTC", "XYZ", "2026-12-18", "50", "CALL", 1, "30.00", "2026-11-11T10:00");
        assertThat(rejected(btc, "CASH_INSUFFICIENT").get("message").asText())
                .isEqualTo("可用现金不足：需要 30.00，可用 25.00");
        btc.put("netAmount", "20.00");
        JsonNode t = ok(btc);
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("5.00");
        assertThat(t.get("coveredLots").get(0).get("frozen").asText()).isEqualTo("0.00");
        assertSummary("5.00", "0.00", "5.00");
        assertThat(getJson("/api/option-positions").get("positionCount").asInt()).isZero();
    }

    @Test
    void ac6_longCallsUnchanged() throws Exception {
        deposit("1000.00", "2026-11-02T10:00");
        ok(body("BTO", "AAPL", "2026-12-18", "210", "CALL", 1, "512.65", "2026-11-03T10:30"));
        assertSummary("487.35", "0.00", "487.35");
        assertThat(rejected(body("STO", "AAPL", "2026-12-18", "210", "CALL", 1, "1.00", "2026-11-04T10:00"),
                "POSITION_DIRECTION_CONFLICT").get("message").asText()).contains("Sell to Close（STC）");
        JsonNode stc = ok(body("STC", "AAPL", "2026-12-18", "210", "CALL", 1, "830.00", "2026-11-05T10:00"));
        assertThat(stc.get("realizedPnl").asText()).isEqualTo("317.35");
    }

    @Test
    void ac7_onlyPutsFreeze() throws Exception {
        deposit("25000.00", "2026-11-02T10:00");
        ok(body("STO", "AAPL", "2026-12-18", "200", "PUT", 1, "349.35", "2026-11-03T10:30"));
        ok(body("STO", "AAPL", "2026-12-18", "250", "CALL", 1, "120.00", "2026-11-04T10:00"));
        assertSummary("25469.35", "20000.00", "5469.35");
        assertThat(getJson("/api/option-positions").get("frozenTotal").asText()).isEqualTo("20000.00");
        assertThat(getJson("/api/dashboard").get("positions").get("frozenTotal").asText()).isEqualTo("20000.00");
    }

    // ---------------------------------------------------------------- AC 8

    private ObjectNode edit(long id, String net, int contracts) throws Exception {
        JsonNode t = getJson("/api/option-trades/" + id);
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", t.get("tradedAtLocal").asText());
        return n;
    }

    @Test
    void ac8_editNetAmount() throws Exception {
        scenarioIxqz();
        send(put("/api/option-trades/" + t3), edit(t3, "21.34", 1), 200);
        assertSummary("11001.00", "0.00", "11001.00");
        assertThat(getJson("/api/audit-log").get("items").get(0).get("changes").get(0).get("text").asText())
                .isEqualTo("净额：20.34 → 21.34");
    }

    @Test
    void ac8_deleteCoveredOpenIsRejected() throws Exception {
        scenarioIxqz();
        JsonNode err = call(delete("/api/option-trades/" + t1), 422);
        assertThat(err.get("message").asText()).startsWith("该开仓被 2026-11-11 12:21 的 BTC 1 张平掉")
                .endsWith("请先修改或删除那笔平仓");
        assertScenarioResults();
    }

    @Test
    void ac8_deleteTheClose() throws Exception {
        scenarioIxqz();
        call(delete("/api/option-trades/" + t4a), 204);
        assertSummary("11035.01", "0.00", "11035.01");
        JsonNode ps = getJson("/api/option-positions");
        assertThat(ps.get("positionCount").asInt()).isEqualTo(3);
        assertThat(ps.get("positions").get(0).get("contractLabel").asText()).isEqualTo("IXQZ 2026-11-20 7.5 C");
        assertThat(ps.get("positions").get(0).get("direction").asText()).isEqualTo("SHORT");
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
    }

    @Test
    void editingAShortCallSkipsQ18() throws Exception {
        newEmptyAccount();
        // FEAT-039: 10 contracts need 1,000 covering shares (a transfer-in has no cash effect)
        ObjectNode shares = json.createObjectNode();
        shares.put("type", "TRANSFER_IN");
        shares.put("symbol", "XYZ");
        shares.put("shares", 1000);
        shares.put("amount", "40000.00");
        shares.put("tradeDate", "2026-11-02");
        send(post("/api/stock-trades"), shares, 201);
        long id = ok(body("STO", "XYZ", "2026-12-18", "50", "CALL", 1, "25.00", "2026-11-10T10:00")).get("id").asLong();
        // 10 contracts x 100 x 50 would need 50,000 as put collateral; a call needs none
        JsonNode t = send(put("/api/option-trades/" + id), edit(id, "25.00", 10), 200);
        assertThat(t.get("contracts").asInt()).isEqualTo(10);
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("25.00", "0.00", "25.00");
    }

    // ---------------------------------------------------------------- AC 9-10

    @Test
    void ac9_otherAccountHasNoneOfIt() throws Exception {
        scenarioIxqz();
        newEmptyAccount();
        assertThat(getJson("/api/option-positions").get("positionCount").asInt()).isZero();
        assertThat(getJson("/api/closed-trades").get("filteredCount").asInt()).isZero();
        rejected(body("BTC", "IXQZ", "2026-12-18", "9", "CALL", 1, "1.00", "2026-11-11T13:00"), "NO_POSITION_TO_CLOSE");
    }

    @Test
    void ac10_longShortMultiplierAndFifoStillApply() throws Exception {
        scenarioIxqz();
        assertThat(rejected(body("BTO", "IXQZ", "2026-12-18", "9", "CALL", 1, "1.00", "2026-11-11T13:00"),
                "POSITION_DIRECTION_CONFLICT").get("message").asText()).contains("Buy to Close（BTC）");
        ObjectNode m10 = body("STO", "IXQZ", "2026-12-18", "9", "CALL", 1, "1.00", "2026-11-11T13:00");
        m10.put("multiplier", 10);
        rejected(m10, "MULTIPLIER_MISMATCH");
        JsonNode btc = ok(body("BTC", "IXQZ", "2026-12-18", "9", "CALL", 1, "10.00", "2026-11-11T13:00"));
        JsonNode lots = btc.get("coveredLots");
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-11-10T12:43:00");
        assertThat(lots.get(0).get("netAmount").asText()).isEqualTo("18.34");
        assertThat(btc.get("realizedPnl").asText()).isEqualTo("8.34");
        List<String> codes = new ArrayList<>();
        codes.add(btc.get("frozenChange").asText());
        assertThat(codes).containsExactly("0.00");
    }
}
