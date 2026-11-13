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
import java.time.LocalDate;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FEAT-006 acceptance criteria (backend part). "Now" is 2026-11-11 10:00 New York (15:00Z). */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class ClosedTradeControllerTest {

    static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock closedTradesTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Long, String> names = new HashMap<>();

    @BeforeEach
    void setUp() {
        emptyDatabase();
        names.clear();
    }

    @AfterEach
    void tearDown() {
        emptyDatabase();
    }

    void emptyDatabase() {
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM stock_trade");
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

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers"), n, 201);
    }

    private long trade(String name, String action, String symbol, String exp, String strike, String type,
                       int contracts, Integer multiplier, String net, String nyTime, String note) throws Exception {
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
        n.put("note", note);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        long id = send(post(url), n, 201).get("id").asLong();
        names.put(id, name);
        return id;
    }

    private JsonNode closed(String query) throws Exception {
        return call(get(URI.create("/api/closed-trades" + (query.isEmpty() ? "" : "?" + query))), 200);
    }

    /** "name pnl" rows of the page, and asserts count + total. */
    private List<String> rows(String query, int count, String total) throws Exception {
        JsonNode r = closed(query);
        assertThat(r.get("filteredCount").asLong()).isEqualTo(count);
        assertThat(r.get("totalItems").asLong()).isEqualTo(count);
        assertThat(r.get("filteredRealizedPnlTotal").asText()).isEqualTo(total);
        List<String> out = new ArrayList<>();
        r.get("items").forEach(i -> out.add(names.get(i.get("id").asLong()) + " " + i.get("realizedPnl").asText()));
        return out;
    }

    private JsonNode error(String query, int status) throws Exception {
        return call(get(URI.create("/api/closed-trades?" + query)), status);
    }

    private void scenarioP() throws Exception {
        deposit("100000.00", "2026-10-26T10:00");
        trade("T1", "STO", "AAPL", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-02T10:30", null);
        trade("T2", "BTO", "MSFT", "2027-01-15", "450", "CALL", 1, 100, "900.00", "2026-11-02T14:00", null);
        trade("T3", "STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-11-02T15:00", null);
        trade("T4", "BTO", "AAPL", "2026-12-18", "210", "CALL", 2, 100, "1000.00", "2026-11-03T09:45", null);
        trade("T5", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-03T11:00", null);
        trade("C1", "BTC", "AAPL", "2026-12-18", "200", "PUT", 3, null, "400.00", "2026-11-05T10:00", "全部平掉");
        trade("C2", "BTC", "XYZ", "2026-12-18", "200", "PUT", 1, null, "50.00", "2026-11-06T10:00", null);
        trade("C3", "STC", "MSFT", "2027-01-15", "450", "CALL", 1, null, "1250.00", "2026-11-06T23:30", "盘后");
        trade("C4", "STC", "AAPL", "2026-12-18", "210", "CALL", 2, null, "640.00", "2026-11-07T10:00", null);
        JsonNode s = call(get("/api/cash/summary"), 200);
        assertThat(s.get("totalCash").asText()).isEqualTo("100633.05");
        assertThat(s.get("frozenCash").asText()).isEqualTo("0.00");
    }

    // ---------------------------------------------------------------- AC 1-2

    @Test
    void ac1_listOrderAndColumns() throws Exception {
        scenarioP();
        assertThat(rows("", 4, "633.05")).containsExactly("C4 -360.00", "C3 350.00", "C2 -15.65", "C1 658.70");
        JsonNode items = closed("").get("items");

        JsonNode c4 = items.get(0);
        assertThat(c4.get("tradedAtLocal").asText()).isEqualTo("2026-11-07T10:00:00");
        assertThat(c4.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 210 C");
        assertThat(c4.get("action").asText()).isEqualTo("STC");
        assertThat(c4.get("closedDirection").asText()).isEqualTo("LONG");
        assertThat(c4.get("contracts").asInt()).isEqualTo(2);
        assertThat(c4.get("openingNetTotal").asText()).isEqualTo("-1000.00");
        assertThat(c4.get("openingNetPerContract").asText()).isEqualTo("-500.00");
        assertThat(c4.get("closeNetAmount").asText()).isEqualTo("640.00");
        assertThat(c4.get("closeNetPerContract").asText()).isEqualTo("320.00");

        JsonNode c3 = items.get(1);
        assertThat(c3.get("tradedAt").asText()).isEqualTo("2026-11-07T04:30:00Z");
        assertThat(c3.get("tradedAtLocal").asText()).isEqualTo("2026-11-06T23:30:00");
        assertThat(c3.get("openingNetTotal").asText()).isEqualTo("-900.00");
        assertThat(c3.get("closeNetAmount").asText()).isEqualTo("1250.00");
        assertThat(c3.get("note").asText()).isEqualTo("盘后");

        JsonNode c2 = items.get(2);
        assertThat(c2.get("closedDirection").asText()).isEqualTo("SHORT");
        assertThat(c2.get("multiplier").asInt()).isEqualTo(10);
        assertThat(c2.get("openingNetTotal").asText()).isEqualTo("34.35");
        assertThat(c2.get("closeNetAmount").asText()).isEqualTo("-50.00");

        JsonNode c1 = items.get(3);
        assertThat(c1.get("action").asText()).isEqualTo("BTC");
        assertThat(c1.get("contracts").asInt()).isEqualTo(3);
        assertThat(c1.get("openingNetTotal").asText()).isEqualTo("1058.70");
        assertThat(c1.get("openingNetPerContract").asText()).isEqualTo("352.90");
        assertThat(c1.get("closeNetAmount").asText()).isEqualTo("-400.00");
        assertThat(c1.get("closeNetPerContract").asText()).isEqualTo("-133.33");
        assertThat(c1.get("realizedPnl").asText()).isEqualTo("658.70");
        assertThat(c1.get("strike").asText()).isEqualTo("200");

        for (JsonNode i : items) {
            assertThat(i.has("price")).isFalse();
            assertThat(i.has("fee")).isFalse();
        }
    }

    @Test
    void ac2_coveredLots() throws Exception {
        scenarioP();
        JsonNode items = closed("").get("items");
        JsonNode c1Lots = items.get(3).get("coveredLots");
        assertThat(c1Lots).hasSize(2);
        assertThat(names.get(c1Lots.get(0).get("tradeId").asLong())).isEqualTo("T1");
        assertThat(c1Lots.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-11-02T10:30:00");
        assertThat(c1Lots.get(0).get("action").asText()).isEqualTo("STO");
        assertThat(c1Lots.get(0).get("contracts").asInt()).isEqualTo(2);
        assertThat(c1Lots.get(0).get("netAmount").asText()).isEqualTo("698.70");
        assertThat(names.get(c1Lots.get(1).get("tradeId").asLong())).isEqualTo("T5");
        assertThat(c1Lots.get(1).get("netAmount").asText()).isEqualTo("360.00");

        JsonNode c4Lots = items.get(0).get("coveredLots");
        assertThat(c4Lots).hasSize(1);
        assertThat(names.get(c4Lots.get(0).get("tradeId").asLong())).isEqualTo("T4");
        assertThat(c4Lots.get(0).get("action").asText()).isEqualTo("BTO");
        assertThat(c4Lots.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-11-03T09:45:00");
        assertThat(c4Lots.get(0).get("netAmount").asText()).isEqualTo("-1000.00");
    }

    // ---------------------------------------------------------------- AC 3-4 filters

    @Test
    void ac3_filtersAndTotals() throws Exception {
        scenarioP();
        assertThat(rows("symbol=aapl", 2, "298.70")).containsExactly("C4 -360.00", "C1 658.70");
        assertThat(rows("symbol=AAPL&expirationDate=2026-12-18&strike=200.00&optionType=PUT", 1, "658.70"))
                .containsExactly("C1 658.70");
        assertThat(rows("optionType=CALL", 2, "-10.00")).containsExactly("C4 -360.00", "C3 350.00");
        assertThat(rows("action=BTC", 2, "643.05")).containsExactly("C2 -15.65", "C1 658.70");
        assertThat(rows("action=STC", 2, "-10.00")).containsExactly("C4 -360.00", "C3 350.00");
        assertThat(rows("action=BTC,STC", 4, "633.05")).hasSize(4);
        assertThat(rows("symbol=TSLA", 0, "0.00")).isEmpty();
        assertThat(closed("symbol=TSLA").get("totalPages").asInt()).isZero();

        JsonNode bad = error("symbol=%24AAPL", 400);
        assertThat(bad.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(bad.get("details").get(0).get("field").asText()).isEqualTo("symbol");
        assertThat(bad.get("details").get(0).get("message").asText()).isEqualTo("标的只能包含字母、数字和点");

        for (String action : List.of("STO", "BTO", "XYZ", "btc")) {
            JsonNode err = error("action=" + action, 400);
            assertThat(err.get("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("action");
            assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTC、STC、EXP、ASG");
        }
    }

    @Test
    void ac4_closeDateInNewYork() throws Exception {
        scenarioP();
        assertThat(rows("startDate=2026-11-06&endDate=2026-11-06", 2, "334.35"))
                .containsExactly("C3 350.00", "C2 -15.65");
        assertThat(rows("startDate=2026-11-07&endDate=2026-11-07", 1, "-360.00")).containsExactly("C4 -360.00");
        assertThat(rows("endDate=2026-11-05", 1, "658.70")).containsExactly("C1 658.70");
        JsonNode err = error("startDate=2026-11-07&endDate=2026-11-06", 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
    }

    // ---------------------------------------------------------------- AC 5 re-matching

    @Test
    void ac5_rematchAfterBackfillEditDelete() throws Exception {
        deposit("80000.00", "2026-10-26T10:00");
        long l1 = trade("L1", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30", null);
        long l2 = trade("L2", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00", null);
        trade("X", "BTC", "AAPL", "2026-12-18", "200", "PUT", 1, null, "100.00", "2026-11-06T10:00", null);
        assertThat(rows("", 1, "249.35")).containsExactly("X 249.35");
        assertThat(closed("").get("items").get(0).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(l1);

        long y = trade("Y", "BTC", "AAPL", "2026-12-18", "200", "PUT", 1, null, "120.00", "2026-11-05T10:00", null);
        assertThat(rows("", 2, "489.35")).containsExactly("X 260.00", "Y 229.35");
        JsonNode items = closed("").get("items");
        assertThat(items.get(0).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(l2);
        assertThat(items.get(1).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(l1);

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "365.00");
        edit.put("tradedAtLocal", "2026-11-04T11:00");
        edit.put("multiplier", 100);
        send(put("/api/option-trades/" + l2), edit, 200);
        assertThat(rows("", 2, "494.35")).containsExactly("X 265.00", "Y 229.35");

        call(delete("/api/option-trades/" + y), 204);
        assertThat(rows("", 1, "249.35")).containsExactly("X 249.35");
        assertThat(closed("").get("items").get(0).get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(l1);
    }

    // ---------------------------------------------------------------- AC 6 pagination

    @Test
    void ac6_paginationTotalsCoverAllPages() throws Exception {
        deposit("10000.00", "2026-09-30T10:00");
        LocalDate day = LocalDate.parse("2026-10-01");
        for (int i = 0; i < 25; i++) {
            trade("O" + i, "BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "10.00", day.plusDays(i) + "T09:30", null);
            trade("S" + i, "STC", "AAPL", "2026-12-18", "210", "CALL", 1, null, "12.00", day.plusDays(i) + "T15:00", null);
        }
        JsonNode p1 = closed("");
        assertThat(p1.get("filteredCount").asLong()).isEqualTo(25);
        assertThat(p1.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");
        assertThat(p1.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("items").get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-25T15:00:00");
        assertThat(p1.get("items").get(0).get("realizedPnl").asText()).isEqualTo("2.00");

        JsonNode p2 = closed("page=2");
        assertThat(p2.get("items")).hasSize(5);
        assertThat(p2.get("items").get(4).get("tradedAtLocal").asText()).isEqualTo("2026-10-01T15:00:00");
        assertThat(p2.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");

        JsonNode p3 = closed("page=3");
        assertThat(p3.get("items")).isEmpty();
        assertThat(p3.get("filteredRealizedPnlTotal").asText()).isEqualTo("50.00");

        JsonNode filtered = closed("startDate=2026-10-20");
        assertThat(filtered.get("filteredCount").asLong()).isEqualTo(6);
        assertThat(filtered.get("filteredRealizedPnlTotal").asText()).isEqualTo("12.00");

        // the sum of items over all pages equals the total
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (JsonNode page : List.of(p1, p2)) {
            for (JsonNode i : page.get("items")) {
                sum = sum.add(new java.math.BigDecimal(i.get("realizedPnl").asText()));
            }
        }
        assertThat(sum).isEqualByComparingTo("50.00");

        assertThat(error("size=0", 400).get("details").get(0).get("field").asText()).isEqualTo("size");
        assertThat(error("size=101", 400).get("details").get(0).get("field").asText()).isEqualTo("size");
        assertThat(error("page=0", 400).get("details").get(0).get("field").asText()).isEqualTo("page");
    }

    // ---------------------------------------------------------------- AC 8-9

    @Test
    void ac8_onlyOpensNoClosedTrades() throws Exception {
        deposit("30000.00", "2026-10-26T10:00");
        trade("O", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30", null);
        JsonNode r = closed("");
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("filteredCount").asLong()).isZero();
        assertThat(r.get("filteredRealizedPnlTotal").asText()).isEqualTo("0.00");
        assertThat(r.get("totalPages").asInt()).isZero();
        assertThat(r.get("page").asInt()).isEqualTo(1);
        assertThat(r.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void ac9_readOnly() throws Exception {
        scenarioP();
        closed("");
        closed("action=BTC&page=1&size=1");
        closed("page=2&size=2");
        JsonNode s = call(get("/api/cash/summary"), 200);
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText()))
                .containsExactly("100633.05", "0.00", "100633.05");
        assertThat(call(get("/api/option-trades"), 200).get("totalItems").asLong()).isEqualTo(9);
        assertThat(call(post("/api/closed-trades"), 405).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
