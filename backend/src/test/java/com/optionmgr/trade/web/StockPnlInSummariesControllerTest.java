package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * FEAT-040 acceptance criteria (backend part): stock sales on the closed-trades list, the P&amp;L summary and the
 * dashboard. "Now" defaults to 2026-11-13 10:00 New York (15:00Z). All tickers are fictional.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockPnlInSummariesControllerTest {

    static final Instant DEFAULT_NOW = Instant.parse("2026-11-13T15:00:00Z");

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
        MovableClock stockPnlTestClock() {
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

    private String account = "1";

    /** Scenario M ids: option trades O1..O4, stock records S1..S7 (two separate id sequences). */
    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        clock.set(DEFAULT_NOW);
        cleanUp();
        account = "1";
        ids.clear();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        clock.set(DEFAULT_NOW);
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

    private long option(String name, String action, String symbol, String exp, String strike, String type,
                        String net, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        if (action.endsWith("O")) {
            n.put("multiplier", 100);
        }
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        long id = send(post(url), n, 201).get("id").asLong();
        if (name != null) {
            ids.put(name, id);
        }
        return id;
    }

    private long stock(String name, String type, String symbol, int shares, String amount, String time, String note)
            throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("TRANSFER_IN".equals(type) ? "tradeDate" : "tradedAtLocal", time);
        if (note != null) {
            n.put("note", note);
        }
        long id = send(post("/api/stock-trades"), n, 201).get("id").asLong();
        if (name != null) {
            ids.put(name, id);
        }
        return id;
    }

    private void editStock(String name, int shares, String amount, String time, boolean transfer, String note)
            throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("shares", shares);
        n.put("amount", amount);
        n.put(transfer ? "tradeDate" : "tradedAtLocal", time);
        n.put("note", note);
        send(put("/api/stock-trades/" + ids.get(name)), n, 200);
    }

    private void scenarioM() throws Exception {
        deposit("20000.00", "2026-10-26T10:00");
        stock("S1", "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock("S2", "TRANSFER_IN", "QRTV", 50, "400.00", "2026-10-01", null);
        option("O1", "STO", "IXQZ", "2026-11-20", "7", "PUT", "45.00", "2026-10-27T10:00");
        option("O2", "BTC", "IXQZ", "2026-11-20", "7", "PUT", "10.00", "2026-10-30T10:00");
        stock("S3", "SELL", "QRTV", 50, "380.00", "2026-10-31T23:30", "盘后");
        stock("S4", "BUY", "IXQZ", 200, "1500.65", "2026-11-03T10:00", null);
        stock("S5", "BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00", null);
        option("O3", "STO", "XYZ", "2026-12-18", "3", "CALL", "25.00", "2026-11-04T11:00");
        stock("S6", "SELL", "IXQZ", 150, "1200.00", "2026-11-05T10:00", null);
        stock("S7", "SELL", "XYZ", 100, "400.00", "2026-11-06T10:00", null);
        option("O4", "BTC", "XYZ", "2026-12-18", "3", "CALL", "40.00", "2026-11-09T10:00");
        JsonNode cash = getJson("/api/cash/summary");
        assertThat(cash.get("totalCash").asText()).isEqualTo("19499.35");
        assertThat(cash.get("frozenCash").asText()).isEqualTo("0.00");
    }

    private JsonNode closed(String query) throws Exception {
        return getJson("/api/closed-trades" + (query.isEmpty() ? "" : "?" + query));
    }

    /** Name of a row: O1.. / S1.. by type + id. */
    private String name(JsonNode row) {
        String prefix = row.get("type").asText().equals("OPTION") ? "O" : "S";
        long id = row.get("id").asLong();
        return ids.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getValue() == id)
                .map(Map.Entry::getKey).findFirst().orElse(prefix + "#" + id);
    }

    private List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(name(i)));
        return out;
    }

    private static String totals(JsonNode page) {
        return page.get("filteredCount").asInt() + " " + page.get("filteredRealizedPnlTotal").asText();
    }

    private static String row(JsonNode i) {
        if (i.get("type").asText().equals("OPTION")) {
            return "OPTION " + i.get("tradedAtLocal").asText() + " " + i.get("contractLabel").asText() + " "
                    + i.get("action").asText() + " " + i.get("closedDirection").asText() + " "
                    + i.get("contracts").asInt() + " " + i.get("openingNetTotal").asText() + "/"
                    + i.get("openingNetPerContract").asText() + " " + i.get("closeNetAmount").asText() + "/"
                    + i.get("closeNetPerContract").asText() + " " + i.get("realizedPnl").asText();
        }
        return "STOCK " + i.get("tradedAtLocal").asText() + " " + i.get("symbol").asText() + " "
                + i.get("action").asText() + " " + i.get("shares").asInt() + " " + i.get("openingNetTotal").asText()
                + "/" + i.get("costPerShare").asText() + " " + i.get("closeNetAmount").asText() + "/"
                + i.get("pricePerShare").asText() + " " + i.get("realizedPnl").asText();
    }

    private JsonNode rowOf(JsonNode page, String name) {
        for (JsonNode i : page.get("items")) {
            if (name(i).equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no row " + name);
    }

    private JsonNode summary(String query) throws Exception {
        return getJson("/api/pnl-summary" + (query.isEmpty() ? "" : "?" + query));
    }

    private static String part(JsonNode p) {
        return p.get("closedCount").asInt() + " " + p.get("realizedPnl").asText();
    }

    /** "<all count> <all pnl> | <option> | <stock>" of the summary total. */
    private static String total(JsonNode s) {
        return s.get("closedCount").asInt() + " " + s.get("realizedPnlTotal").asText() + " | " + part(s.get("option"))
                + " | " + part(s.get("stock"));
    }

    private static List<String> groups(JsonNode array, String key) {
        List<String> out = new ArrayList<>();
        array.forEach(g -> out.add(g.get(key).asText() + " " + g.get("closedCount").asInt() + " "
                + g.get("realizedPnl").asText() + " | " + part(g.get("option")) + " | " + part(g.get("stock"))));
        return out;
    }

    private static String pnl(JsonNode p) {
        return p.get("total").asText() + " (" + p.get("closedCount").asInt() + ")";
    }

    /** Summary total = closed-trades total for the same range; groups add up to the totals (incl. the split). */
    private void assertConsistent(String query) throws Exception {
        JsonNode s = summary(query);
        JsonNode c = closed(query);
        assertThat(s.get("realizedPnlTotal").asText()).isEqualTo(c.get("filteredRealizedPnlTotal").asText());
        assertThat(s.get("closedCount").asInt()).isEqualTo(c.get("filteredCount").asInt());
        for (String groupKey : List.of("bySymbol", "byMonth")) {
            for (String type : List.of("option", "stock")) {
                BigDecimal sum = BigDecimal.ZERO;
                int count = 0;
                for (JsonNode g : s.get(groupKey)) {
                    sum = sum.add(new BigDecimal(g.get(type).get("realizedPnl").asText()));
                    count += g.get(type).get("closedCount").asInt();
                    assertThat(new BigDecimal(g.get("option").get("realizedPnl").asText())
                            .add(new BigDecimal(g.get("stock").get("realizedPnl").asText())))
                            .isEqualByComparingTo(g.get("realizedPnl").asText());
                }
                assertThat(sum).isEqualByComparingTo(s.get(type).get("realizedPnl").asText());
                assertThat(count).isEqualTo(s.get(type).get("closedCount").asInt());
            }
        }
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_optionsAndStockSalesMerged() throws Exception {
        scenarioM();
        JsonNode p = closed("");
        assertThat(totals(p)).isEqualTo("5 141.51");
        assertThat(p.get("totalItems").asInt()).isEqualTo(5);
        assertThat(names(p)).containsExactly("O4", "S7", "S6", "S3", "O2");
        List<String> rows = new ArrayList<>();
        p.get("items").forEach(i -> rows.add(row(i)));
        assertThat(rows).containsExactly(
                "OPTION 2026-11-09T10:00:00 XYZ 2026-12-18 3 C BTC SHORT 1 25.00/25.00 -40.00/-40.00 -15.00",
                "STOCK 2026-11-06T10:00:00 XYZ SELL 100 -333.33/3.3333 400.00/4.0000 66.67",
                "STOCK 2026-11-05T10:00:00 IXQZ SELL 150 -1125.16/7.5011 1200.00/8.0000 74.84",
                "STOCK 2026-10-31T23:30:00 QRTV SELL 50 -400.00/8.0000 380.00/7.6000 -20.00",
                "OPTION 2026-10-30T10:00:00 IXQZ 2026-11-20 7 P BTC SHORT 1 45.00/45.00 -10.00/-10.00 35.00");

        JsonNode s3 = rowOf(p, "S3");
        assertThat(s3.get("note").asText()).isEqualTo("盘后");
        assertThat(s3.get("tradedAt").asText()).isEqualTo("2026-11-01T03:30:00Z");
        for (JsonNode i : p.get("items")) {
            assertThat(i.get("hasAuditLog").asBoolean()).isFalse();
            if (i.get("type").asText().equals("STOCK")) {
                for (String f : List.of("expirationDate", "strike", "optionType", "contractLabel", "closedDirection",
                        "contracts", "multiplier", "openingNetPerContract", "closeNetPerContract", "coveredLots")) {
                    assertThat(i.has(f)).as(f).isTrue();
                    assertThat(i.get(f).isNull()).as(f).isTrue();
                }
                assertThat(i.get("soldLots").isArray()).isTrue();
            } else {
                for (String f : List.of("shares", "costPerShare", "pricePerShare", "soldLots")) {
                    assertThat(i.has(f)).as(f).isTrue();
                    assertThat(i.get(f).isNull()).as(f).isTrue();
                }
                assertThat(i.get("multiplier").asInt()).isEqualTo(100);
            }
        }
        // the stock row figures are FEAT-011's figures of that sale
        JsonNode s6 = getJson("/api/stock-trades/" + ids.get("S6"));
        JsonNode r6 = rowOf(p, "S6");
        assertThat(new BigDecimal(r6.get("openingNetTotal").asText()).negate())
                .isEqualByComparingTo(s6.get("soldCost").asText());
        assertThat(r6.get("realizedPnl")).isEqualTo(s6.get("realizedPnl"));
        assertThat(r6.get("pricePerShare")).isEqualTo(s6.get("pricePerShare"));
        assertThat(r6.get("closeNetAmount")).isEqualTo(s6.get("cashChange"));
    }

    // ---------------------------------------------------------------- AC 2

    private List<String> soldLots(JsonNode row) {
        List<String> out = new ArrayList<>();
        row.get("soldLots").forEach(l -> {
            String src = ids.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("S") && e.getValue() == l.get("tradeId").asLong())
                    .map(Map.Entry::getKey).findFirst().orElse("?");
            out.add(src + " " + l.get("type").asText() + " " + l.get("tradedAtLocal").asText() + " "
                    + l.get("tradeDate").asText() + " " + l.get("shares").asInt() + " " + l.get("netAmount").asText());
        });
        return out;
    }

    @Test
    void ac2_soldLots() throws Exception {
        scenarioM();
        JsonNode p = closed("");
        JsonNode s6 = rowOf(p, "S6");
        assertThat(soldLots(s6)).containsExactly(
                "S1 TRANSFER_IN 2026-10-01T00:00:00 2026-10-01 100 -750.00",
                "S4 BUY 2026-11-03T10:00:00 null 50 -375.16");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : s6.get("soldLots")) {
            sum = sum.add(new BigDecimal(l.get("netAmount").asText()));
        }
        assertThat(sum).isEqualByComparingTo(s6.get("openingNetTotal").asText());
        assertThat(s6.get("soldLots").get(0).get("tradedAt").asText()).isEqualTo("2026-10-01T04:00:00Z");
        assertThat(s6.get("soldLots").get(1).get("tradeDate").isNull()).isTrue();

        assertThat(soldLots(rowOf(p, "S7"))).containsExactly("S5 BUY 2026-11-04T10:00:00 null 100 -333.33");

        JsonNode lots = rowOf(p, "O2").get("coveredLots");
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).get("tradeId").asLong()).isEqualTo(ids.get("O1"));
        assertThat(lots.get(0).get("action").asText()).isEqualTo("STO");
        assertThat(lots.get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-27T10:00:00");
        assertThat(lots.get(0).get("contracts").asInt()).isEqualTo(1);
        assertThat(lots.get(0).get("netAmount").asText()).isEqualTo("45.00");
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_typeFilterAndTotals() throws Exception {
        scenarioM();
        JsonNode stocks = closed("type=STOCK");
        assertThat(names(stocks)).containsExactly("S7", "S6", "S3");
        assertThat(totals(stocks)).isEqualTo("3 121.51");
        JsonNode options = closed("type=OPTION");
        assertThat(names(options)).containsExactly("O4", "O2");
        assertThat(totals(options)).isEqualTo("2 20.00");
        JsonNode ixqz = closed("symbol=ixqz");
        assertThat(names(ixqz)).containsExactly("S6", "O2");
        assertThat(totals(ixqz)).isEqualTo("2 109.84");
        JsonNode ixqzStock = closed("symbol=IXQZ&type=STOCK");
        assertThat(names(ixqzStock)).containsExactly("S6");
        assertThat(totals(ixqzStock)).isEqualTo("1 74.84");
        JsonNode none = closed("symbol=QFZ");
        assertThat(none.get("items")).isEmpty();
        assertThat(totals(none)).isEqualTo("0 0.00");
        assertThat(totals(closed("type=%20"))).isEqualTo("5 141.51");
    }

    @Test
    void ac3_invalidTypeIsBadRequest() throws Exception {
        scenarioM();
        for (String bad : List.of("FUTURE", "stock", "OPTION,STOCK")) {
            JsonNode e = call(get(URI.create("/api/closed-trades?type=" + bad.replace(",", "%2C"))), 400);
            assertThat(e.get("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(e.get("details")).hasSize(1);
            assertThat(e.get("details").get(0).get("field").asText()).isEqualTo("type");
            assertThat(e.get("details").get(0).get("message").asText()).isEqualTo("类型只能是 OPTION 或 STOCK");
        }
        JsonNode both = call(get(URI.create("/api/closed-trades?type=FUTURE&action=SELL")), 400);
        assertThat(both.get("details")).extracting(d -> d.get("field").asText()).containsExactly("action", "type");
        // BAD_REQUEST first, then VALIDATION_ERROR (as FEAT-002)
        JsonNode first = call(get(URI.create("/api/closed-trades?type=FUTURE&size=0")), 400);
        assertThat(first.get("code").asText()).isEqualTo("BAD_REQUEST");
    }

    // ---------------------------------------------------------------- AC 4 (Q90)

    @Test
    void ac4_optionOnlyFiltersExcludeStockSales() throws Exception {
        scenarioM();
        JsonNode btc = closed("action=BTC");
        assertThat(names(btc)).containsExactly("O4", "O2");
        assertThat(totals(btc)).isEqualTo("2 20.00");
        JsonNode exp = closed("expirationDate=2026-11-20");
        assertThat(names(exp)).containsExactly("O2");
        assertThat(totals(exp)).isEqualTo("1 35.00");
        JsonNode call = closed("optionType=CALL");
        assertThat(names(call)).containsExactly("O4");
        assertThat(totals(call)).isEqualTo("1 -15.00");
        assertThat(names(closed("strike=3"))).containsExactly("O4");
        assertThat(names(closed("type=OPTION&action=BTC,STC,EXP"))).containsExactly("O4", "O2");

        for (String q : List.of("type=STOCK&action=BTC", "type=STOCK&strike=3", "type=STOCK&expirationDate=2026-11-20",
                "type=STOCK&optionType=PUT")) {
            JsonNode empty = closed(q);
            assertThat(empty.get("items")).as(q).isEmpty();
            assertThat(totals(empty)).as(q).isEqualTo("0 0.00");
            assertThat(empty.get("totalPages").asInt()).as(q).isZero();
        }
        JsonNode sell = call(get(URI.create("/api/closed-trades?action=SELL")), 400);
        assertThat(sell.get("details").get(0).get("message").asText()).isEqualTo("方向只能是 BTC、STC、EXP、ASG、EXR");
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_newYorkSaleDates() throws Exception {
        scenarioM();
        JsonNode oct31 = closed("startDate=2026-10-31&endDate=2026-10-31");
        assertThat(names(oct31)).containsExactly("S3");
        assertThat(totals(oct31)).isEqualTo("1 -20.00");
        JsonNode nov1 = closed("startDate=2026-11-01&endDate=2026-11-01");
        assertThat(nov1.get("items")).isEmpty();
        assertThat(totals(nov1)).isEqualTo("0 0.00");
        JsonNode range = closed("startDate=2026-11-06&endDate=2026-11-09");
        assertThat(names(range)).containsExactly("O4", "S7");
        assertThat(totals(range)).isEqualTo("2 51.67");
        JsonNode e = call(get(URI.create("/api/closed-trades?startDate=2026-11-07&endDate=2026-11-06")), 400);
        assertThat(e.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(e.get("details").get(0).get("field").asText()).isEqualTo("endDate");
        assertThat(e.get("details").get(0).get("message").asText()).isEqualTo("开始日期不能晚于结束日期");
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_paginationAcrossBothTypes() throws Exception {
        scenarioM();
        List<List<String>> pages = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            JsonNode p = closed("page=" + page + "&size=2");
            pages.add(names(p));
            assertThat(totals(p)).isEqualTo("5 141.51");
            assertThat(p.get("totalItems").asInt()).isEqualTo(5);
            assertThat(p.get("totalPages").asInt()).isEqualTo(3);
            assertThat(p.get("page").asInt()).isEqualTo(page);
            assertThat(p.get("size").asInt()).isEqualTo(2);
        }
        assertThat(pages).containsExactly(List.of("O4", "S7"), List.of("S6", "S3"), List.of("O2"), List.of());
    }

    // ---------------------------------------------------------------- AC 7 (Q92)

    @Test
    void ac7_sameInstantStockAboveOption() throws Exception {
        scenarioM();
        deposit("1000.00", "2026-11-10T09:00");
        option("O5", "STO", "QRTV", "2026-12-18", "5", "PUT", "60.00", "2026-11-10T09:30");
        stock("S8", "BUY", "QRTV", 10, "70.00", "2026-11-10T09:30", null);
        stock("S9", "SELL", "QRTV", 10, "75.00", "2026-11-11T10:00", null);
        option("O6", "BTC", "QRTV", "2026-12-18", "5", "PUT", "20.00", "2026-11-11T10:00");
        JsonNode p = closed("");
        assertThat(names(p)).startsWith("S9", "O6");
        assertThat(rowOf(p, "S9").get("realizedPnl").asText()).isEqualTo("5.00");
        assertThat(rowOf(p, "O6").get("realizedPnl").asText()).isEqualTo("40.00");
        assertThat(rowOf(p, "O6").get("contractLabel").asText()).isEqualTo("QRTV 2026-12-18 5 P");
        assertThat(totals(p)).isEqualTo("7 186.51");
        // paging splits the same-instant pair in the merged order
        assertThat(names(closed("page=1&size=1"))).containsExactly("S9");
        assertThat(names(closed("page=2&size=1"))).containsExactly("O6");
    }

    // ---------------------------------------------------------------- AC 8

    private Map<String, Boolean> flags() throws Exception {
        Map<String, Boolean> out = new LinkedHashMap<>();
        closed("").get("items").forEach(i -> out.put(name(i), i.get("hasAuditLog").asBoolean()));
        return out;
    }

    @Test
    void ac8_hasAuditLogPerRecordType() throws Exception {
        scenarioM();
        editStock("S1", 100, "750.00", "2026-10-01", true, "期初");
        assertThat(flags()).doesNotContainValue(true);

        editStock("S6", 150, "1200.00", "2026-11-05T10:00", false, "减仓");
        assertThat(flags()).containsEntry("S6", true).containsEntry("S7", false).containsEntry("S3", false)
                .containsEntry("O2", false).containsEntry("O4", false);
        assertThat(rowOf(closed(""), "S6").get("note").asText()).isEqualTo("减仓");

        ObjectNode n = json.createObjectNode();
        n.put("contracts", 1);
        n.put("netAmount", "10.00");
        n.put("tradedAtLocal", "2026-10-30T10:00");
        n.put("note", "改备注");
        send(put("/api/option-trades/" + ids.get("O2")), n, 200);
        assertThat(flags()).containsEntry("O2", true).containsEntry("S6", true).containsEntry("S3", false)
                .containsEntry("S7", false).containsEntry("O4", false);
        // the audit object of each row is type + id
        JsonNode audit = getJson("/api/audit-log?objectType=STOCK_TRADE&objectId=" + ids.get("S6"));
        assertThat(audit.get("items")).hasSize(1);
        JsonNode optAudit = getJson("/api/audit-log?objectType=OPTION_TRADE&objectId=" + ids.get("O2"));
        assertThat(optAudit.get("items")).hasSize(1);
    }

    // ---------------------------------------------------------------- AC 9 (Q91)

    @Test
    void ac9_summaryAllTime() throws Exception {
        scenarioM();
        JsonNode s = summary("");
        assertThat(total(s)).isEqualTo("5 141.51 | 2 20.00 | 3 121.51");
        assertThat(groups(s.get("bySymbol"), "symbol")).containsExactly(
                "IXQZ 2 109.84 | 1 35.00 | 1 74.84",
                "QRTV 1 -20.00 | 0 0.00 | 1 -20.00",
                "XYZ 2 51.67 | 1 -15.00 | 1 66.67");
        assertThat(groups(s.get("byMonth"), "month")).containsExactly(
                "2026-11 3 126.51 | 1 -15.00 | 2 141.51",
                "2026-10 2 15.00 | 1 35.00 | 1 -20.00");
        assertThat(s.get("startDate").isNull()).isTrue();
        assertConsistent("");
    }

    // ---------------------------------------------------------------- AC 10

    @Test
    void ac10_summaryRanges() throws Exception {
        scenarioM();
        JsonNode oct31 = summary("startDate=2026-10-31&endDate=2026-10-31");
        assertThat(total(oct31)).isEqualTo("1 -20.00 | 0 0.00 | 1 -20.00");
        assertThat(groups(oct31.get("bySymbol"), "symbol")).containsExactly("QRTV 1 -20.00 | 0 0.00 | 1 -20.00");
        assertThat(groups(oct31.get("byMonth"), "month")).containsExactly("2026-10 1 -20.00 | 0 0.00 | 1 -20.00");

        JsonNode nov = summary("startDate=2026-11-01");
        assertThat(total(nov)).isEqualTo("3 126.51 | 1 -15.00 | 2 141.51");
        assertThat(groups(nov.get("bySymbol"), "symbol")).containsExactly(
                "IXQZ 1 74.84 | 0 0.00 | 1 74.84",
                "XYZ 2 51.67 | 1 -15.00 | 1 66.67");

        JsonNode future = summary("startDate=2027-01-01");
        assertThat(total(future)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        assertThat(future.get("bySymbol")).isEmpty();
        assertThat(future.get("byMonth")).isEmpty();

        for (String q : List.of("", "startDate=2026-10-31&endDate=2026-10-31", "startDate=2026-11-01",
                "startDate=2027-01-01", "endDate=2026-10-31", "startDate=2026-10-01&endDate=2026-10-31")) {
            assertConsistent(q);
        }
        // links from the summary: symbol QRTV (no type) and month 2026-10
        JsonNode qrtv = closed("symbol=QRTV");
        assertThat(names(qrtv)).containsExactly("S3");
        assertThat(totals(qrtv)).isEqualTo("1 -20.00");
        JsonNode october = closed("startDate=2026-10-01&endDate=2026-10-31");
        assertThat(names(october)).containsExactly("S3", "O2");
        assertThat(totals(october)).isEqualTo("2 15.00");
    }

    // ---------------------------------------------------------------- AC 11 (Q89)

    @Test
    void ac11_dashboard() throws Exception {
        scenarioM();
        option("O5", "STO", "IXQZ", "2026-12-18", "9", "CALL", "30.00", "2026-11-12T10:00");
        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("asOfDate").asText()).isEqualTo("2026-11-13");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(List.of(d.get("cash").get("totalCash").asText(), d.get("cash").get("frozenCash").asText(),
                d.get("cash").get("availableCash").asText())).containsExactly("19529.35", "0.00", "19529.35");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("141.51 (5)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("126.51 (3)");
        JsonNode pos = d.get("positions");
        assertThat(List.of(pos.get("positionCount").asInt(), pos.get("expiringWithin7DaysCount").asInt(),
                pos.get("expiredUnprocessedCount").asInt())).containsExactly(1, 0, 0);
        assertThat(pos.get("frozenTotal").asText()).isEqualTo("0.00");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
        assertThat(getJson("/api/stock-positions").get("positionCount").asInt()).isEqualTo(2);

        stock(null, "SELL", "XYZ", 200, "700.00", "2026-11-12T11:00", null);
        d = getJson("/api/dashboard");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("174.84 (6)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("159.84 (4)");
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC 12

    @Test
    void ac12_currentMonthBoundary() throws Exception {
        clock.set(Instant.parse("2026-11-01T03:45:00Z")); // 2026-10-31 23:45 New York (EDT)
        deposit("20000.00", "2026-10-26T10:00");
        stock("S1", "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock("S2", "TRANSFER_IN", "QRTV", 50, "400.00", "2026-10-01", null);
        option("O1", "STO", "IXQZ", "2026-11-20", "7", "PUT", "45.00", "2026-10-27T10:00");
        option("O2", "BTC", "IXQZ", "2026-11-20", "7", "PUT", "10.00", "2026-10-30T10:00");
        stock("S3", "SELL", "QRTV", 50, "380.00", "2026-10-31T23:30", "盘后");

        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-10");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("15.00 (2)");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("15.00 (2)");

        clock.set(Instant.parse("2026-11-01T04:30:00Z")); // 2026-11-01 00:30 New York
        d = getJson("/api/dashboard");
        assertThat(d.get("currentMonth").asText()).isEqualTo("2026-11");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("15.00 (2)");
    }

    // ---------------------------------------------------------------- AC 13

    @Test
    void ac13_editTransferCost() throws Exception {
        scenarioM();
        editStock("S1", 100, "800.00", "2026-10-01", true, null);
        JsonNode p = closed("");
        JsonNode s6 = rowOf(p, "S6");
        assertThat(s6.get("openingNetTotal").asText()).isEqualTo("-1175.16");
        assertThat(s6.get("costPerShare").asText()).isEqualTo("7.8344");
        assertThat(s6.get("realizedPnl").asText()).isEqualTo("24.84");
        assertThat(s6.get("soldLots").get(0).get("netAmount").asText()).isEqualTo("-800.00");
        assertThat(totals(p)).isEqualTo("5 91.51");
        JsonNode s = summary("");
        assertThat(total(s)).isEqualTo("5 91.51 | 2 20.00 | 3 71.51");
        assertThat(groups(s.get("bySymbol"), "symbol")).contains("IXQZ 2 59.84 | 1 35.00 | 1 24.84");
        assertThat(groups(s.get("byMonth"), "month")).contains("2026-11 3 76.51 | 1 -15.00 | 2 91.51");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("91.51 (5)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("76.51 (3)");
    }

    @Test
    void ac13_editSaleAmount() throws Exception {
        scenarioM();
        editStock("S7", 100, "300.00", "2026-11-06T10:00", false, null);
        JsonNode p = closed("");
        assertThat(rowOf(p, "S7").get("realizedPnl").asText()).isEqualTo("-33.33");
        assertThat(rowOf(p, "S7").get("pricePerShare").asText()).isEqualTo("3.0000");
        assertThat(totals(p)).isEqualTo("5 41.51");
        assertThat(groups(summary("").get("bySymbol"), "symbol")).contains("XYZ 2 -48.33 | 1 -15.00 | 1 -33.33");
    }

    @Test
    void ac13_deleteSale() throws Exception {
        scenarioM();
        call(delete("/api/stock-trades/" + ids.get("S7")), 204);
        assertThat(totals(closed(""))).isEqualTo("4 74.84");
        JsonNode s = summary("");
        assertThat(groups(s.get("bySymbol"), "symbol")).contains("XYZ 1 -15.00 | 1 -15.00 | 0 0.00");
        assertThat(groups(s.get("byMonth"), "month")).contains("2026-11 2 59.84 | 1 -15.00 | 1 74.84");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("74.84 (4)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }

    @Test
    void ac13_backfillSale() throws Exception {
        scenarioM();
        long id = stock(null, "SELL", "XYZ", 100, "420.00", "2026-11-12T10:00", null);
        ids.put("S8", id);
        JsonNode p = closed("");
        assertThat(rowOf(p, "S8").get("openingNetTotal").asText()).isEqualTo("-333.34");
        assertThat(rowOf(p, "S8").get("realizedPnl").asText()).isEqualTo("86.66");
        assertThat(totals(p)).isEqualTo("6 228.17");
        assertThat(groups(summary("").get("byMonth"), "month")).contains("2026-11 4 213.17 | 1 -15.00 | 3 228.17");
    }

    @Test
    void ac13_deleteLotUsedBySale() throws Exception {
        scenarioM();
        call(delete("/api/stock-trades/" + ids.get("S1")), 204);
        JsonNode p = closed("");
        JsonNode s6 = rowOf(p, "S6");
        assertThat(s6.get("openingNetTotal").asText()).isEqualTo("-1125.49");
        assertThat(s6.get("realizedPnl").asText()).isEqualTo("74.51");
        assertThat(soldLots(s6)).containsExactly("S4 BUY 2026-11-03T10:00:00 null 150 -1125.49");
        assertThat(totals(p)).isEqualTo("5 141.18");
    }

    @Test
    void ac13_buysAndTransfersHaveNoPnl() throws Exception {
        scenarioM();
        String closedBefore = closed("").toString();
        String summaryBefore = summary("").toString();
        JsonNode dashBefore = getJson("/api/dashboard");
        stock(null, "TRANSFER_IN", "QFZ", 100, "300.00", "2026-11-10", null);
        stock(null, "BUY", "QFZ", 10, "31.00", "2026-11-11T10:00", null);
        assertThat(closed("").toString()).isEqualTo(closedBefore);
        assertThat(summary("").toString()).isEqualTo(summaryBefore);
        JsonNode d = getJson("/api/dashboard");
        assertThat(d.get("realizedPnl")).isEqualTo(dashBefore.get("realizedPnl"));
        assertThat(d.get("currentMonthRealizedPnl")).isEqualTo(dashBefore.get("currentMonthRealizedPnl"));
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- AC 14

    @Test
    void ac14_accountsAreIsolated() throws Exception {
        scenarioM();
        ObjectNode a = json.createObjectNode();
        a.put("name", "小账户");
        long small = send(post("/api/accounts"), a, 201).get("id").asLong();
        account = String.valueOf(small);
        deposit("1000.00", "2026-11-02T10:00");
        stock(null, "TRANSFER_IN", "IXQZ", 100, "700.00", "2026-11-02", null);
        long sale = stock(null, "SELL", "IXQZ", 100, "760.00", "2026-11-10T10:00", null);

        JsonNode p = closed("");
        assertThat(p.get("items")).hasSize(1);
        assertThat(p.get("items").get(0).get("type").asText()).isEqualTo("STOCK");
        assertThat(p.get("items").get(0).get("id").asLong()).isEqualTo(sale);
        assertThat(totals(p)).isEqualTo("1 60.00");
        JsonNode s = summary("");
        assertThat(groups(s.get("bySymbol"), "symbol")).containsExactly("IXQZ 1 60.00 | 0 0.00 | 1 60.00");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("60.00 (1)");
        assertThat(d.get("positions").get("positionCount").asInt()).isZero();
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isZero();

        account = "1";
        assertThat(totals(closed(""))).isEqualTo("5 141.51");
        assertThat(groups(summary("").get("bySymbol"), "symbol")).contains("IXQZ 2 109.84 | 1 35.00 | 1 74.84");
        assertThat(getJson("/api/dashboard").get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- AC 15

    @Test
    void ac15_optionOnlyDataUnchangedShape() throws Exception {
        deposit("10000.00", "2026-10-26T10:00");
        option("O1", "STO", "QRTV", "2026-11-20", "40", "PUT", "150.00", "2026-10-28T10:00");
        option("O2", "BTC", "QRTV", "2026-11-20", "40", "PUT", "200.00", "2026-10-31T23:30");
        JsonNode p = closed("");
        assertThat(names(p)).containsExactly("O2");
        assertThat(p.get("items").get(0).get("type").asText()).isEqualTo("OPTION");
        assertThat(totals(p)).isEqualTo("1 -50.00");
        JsonNode s = summary("");
        assertThat(total(s)).isEqualTo("1 -50.00 | 1 -50.00 | 0 0.00");
        assertThat(groups(s.get("bySymbol"), "symbol")).containsExactly("QRTV 1 -50.00 | 1 -50.00 | 0 0.00");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("-50.00 (1)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isZero();
        assertThat(totals(closed("type=STOCK"))).isEqualTo("0 0.00");
    }

    @Test
    void ac15_replacesFeat011Ac13() throws Exception {
        // FEAT-011 scenario S + STO 5 IXQZ 9 C
        deposit("10000.00", "2026-11-02T10:00");
        stock("S1", "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock("S2", "BUY", "IXQZ", 200, "1500.65", "2026-11-03T10:00", null);
        stock("S3", "BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00", null);
        stock("S4", "SELL", "IXQZ", 150, "1200.00", "2026-11-05T10:00", null);
        stock("S5", "SELL", "XYZ", 100, "400.00", "2026-11-06T10:00", null);
        ObjectNode sto = json.createObjectNode();
        sto.put("symbol", "IXQZ");
        sto.put("expirationDate", "2026-12-18");
        sto.put("strike", "9");
        sto.put("optionType", "CALL");
        sto.put("action", "STO");
        sto.put("contracts", 5);
        sto.put("netAmount", "150.00");
        sto.put("tradedAtLocal", "2026-11-12T10:00");
        send(post(com.optionmgr.testsupport.LegacyShortCallTestController.openUrl(sto)), sto, 201);

        JsonNode p = closed("");
        assertThat(names(p)).containsExactly("S5", "S4");
        assertThat(totals(p)).isEqualTo("2 141.51");
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("141.51 (2)");
        assertThat(d.get("positions").get("positionCount").asInt()).isEqualTo(1);
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- AC 16

    @Test
    void ac16_noSalesNoCloses() throws Exception {
        deposit("5000.00", "2026-11-02T10:00");
        stock(null, "TRANSFER_IN", "IXQZ", 100, "750.00", "2026-10-01", null);
        stock(null, "BUY", "XYZ", 300, "1000.00", "2026-11-04T10:00", null);
        JsonNode p = closed("");
        assertThat(p.get("items")).isEmpty();
        assertThat(totals(p)).isEqualTo("0 0.00");
        assertThat(p.get("totalPages").asInt()).isZero();
        JsonNode s = summary("");
        assertThat(total(s)).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        assertThat(s.get("bySymbol")).isEmpty();
        assertThat(s.get("byMonth")).isEmpty();
        JsonNode d = getJson("/api/dashboard");
        assertThat(pnl(d.get("realizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(pnl(d.get("currentMonthRealizedPnl"))).isEqualTo("0.00 (0)");
        assertThat(d.get("stockPositions").get("positionCount").asInt()).isEqualTo(2);
    }
}
