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

/**
 * FEAT-002 acceptance criteria (backend part) through the real HTTP API and database.
 * "Now" is 2026-11-05T15:00:00Z (10:00 New York). Most tests use the requirement's base data T1-T6.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class OptionTradeListControllerTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock listTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** Trade id -> T1..T6 of the base data. */
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

    private long trade(String name, String action, String symbol, String exp, String strike, String type,
                       int contracts, int multiplier, String net, String nyTime, String note) throws Exception {
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
        long id = postCreated("/api/option-trades", n).get("id").asLong();
        names.put(id, name);
        return id;
    }

    private JsonNode postCreated(String url, ObjectNode body) throws Exception {
        MvcResult r = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(201);
        return json.readTree(raw);
    }

    private void baseData() throws Exception {
        deposit("100000.00", "2026-10-26T10:00");
        trade("T1", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30", "第一笔 CSP");
        trade("T2", "BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "512.65", "2026-11-03T10:30", null);
        trade("T3", "STO", "F", "2026-12-18", "12.5", "PUT", 2, 100, "48.70", "2026-11-02T15:59", null);
        trade("T4", "STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00", "加仓");
        trade("T5", "BTO", "MSFT", "2027-01-15", "450", "CALL", 1, 100, "900.00", "2026-11-03T23:30", null);
        trade("T6", "STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-10-30T10:00", null);
        assertSummary("99379.75", "44500.00", "54879.75");
    }

    // ---------------------------------------------------------------- reads

    private JsonNode getJson(String url, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(get(java.net.URI.create(url))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(url + " -> " + raw).isEqualTo(expectedStatus);
        return json.readTree(raw);
    }

    private JsonNode list(String query) throws Exception {
        return getJson("/api/option-trades" + (query.isEmpty() ? "" : "?" + query), 200);
    }

    /** Names (T1..T6) of the first page, top to bottom. */
    private List<String> names(String query) throws Exception {
        JsonNode page = list(query);
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(names.get(i.get("id").asLong())));
        assertThat(page.get("totalItems").asLong()).isEqualTo(out.size());
        return out;
    }

    /** Asserts a 400 with {@code code} whose details name exactly {@code field}; returns the messages. */
    private List<String> error(String query, String code, String field) throws Exception {
        JsonNode err = getJson("/api/option-trades?" + query, 400);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("path").asText()).isEqualTo("/api/option-trades");
        List<String> fields = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        err.get("details").forEach(d -> {
            fields.add(d.get("field").asText());
            messages.add(d.get("message").asText());
        });
        assertThat(fields).as(err.toString()).isNotEmpty().containsOnly(field);
        return messages;
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary", 200);
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_defaultOrderAndFields() throws Exception {
        baseData();
        JsonNode page = list("");
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalItems").asLong()).isEqualTo(6);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(names("")).containsExactly("T4", "T5", "T2", "T1", "T3", "T6");

        JsonNode items = page.get("items");
        JsonNode t4 = items.get(0);
        assertThat(t4.get("tradedAtLocal").asText()).isEqualTo("2026-11-04T11:00:00");
        assertThat(t4.get("action").asText()).isEqualTo("STO");
        assertThat(t4.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(t4.get("contracts").asInt()).isEqualTo(1);
        assertThat(t4.get("cashChange").asText()).isEqualTo("360.00");
        assertThat(t4.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(t4.get("note").asText()).isEqualTo("加仓");

        JsonNode t5 = items.get(1);
        assertThat(t5.get("tradedAtLocal").asText()).isEqualTo("2026-11-03T23:30:00");
        assertThat(t5.get("tradedAt").asText()).isEqualTo("2026-11-04T04:30:00Z");
        assertThat(t5.get("contractLabel").asText()).isEqualTo("MSFT 2027-01-15 450 C");
        assertThat(t5.get("cashChange").asText()).isEqualTo("-900.00");
        assertThat(t5.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t5.get("note").isNull()).isTrue();

        assertThat(items.get(2).get("cashChange").asText()).isEqualTo("-512.65");
        assertThat(items.get(3).get("note").asText()).isEqualTo("第一笔 CSP");
        JsonNode t3 = items.get(4);
        assertThat(t3.get("contractLabel").asText()).isEqualTo("F 2026-12-18 12.5 P");
        assertThat(t3.get("contracts").asInt()).isEqualTo(2);
        assertThat(t3.get("frozenChange").asText()).isEqualTo("2500.00");
        JsonNode t6 = items.get(5);
        assertThat(t6.get("multiplier").asInt()).isEqualTo(10);
        assertThat(t6.get("cashChange").asText()).isEqualTo("34.35");
        assertThat(t6.get("frozenChange").asText()).isEqualTo("2000.00");

        // each item is exactly the by-id representation; no price / fee fields
        for (JsonNode item : items) {
            assertThat(getJson("/api/option-trades/" + item.get("id").asLong(), 200)).isEqualTo(item);
            assertThat(item.has("price")).isFalse();
            assertThat(item.has("fee")).isFalse();
        }
    }

    // ---------------------------------------------------------------- AC 2 symbol

    @Test
    void ac2_symbolExactMatchAfterNormalization() throws Exception {
        baseData();
        assertThat(names("symbol=aapl")).containsExactly("T4", "T2", "T1");
        assertThat(names("symbol=%20AAPL%20")).containsExactly("T4", "T2", "T1");
        JsonNode none = list("symbol=AAP");
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("totalItems").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();
        assertThat(error("symbol=%24AAPL", "VALIDATION_ERROR", "symbol")).containsExactly("标的只能包含字母、数字和点");
        assertThat(error("symbol=ABCDEFGHIJK", "VALIDATION_ERROR", "symbol")).containsExactly("标的最多 10 个字符");
        assertThat(names("symbol=")).hasSize(6);    // empty = not filtered
        assertThat(names("symbol=%20%20")).hasSize(6);
    }

    // ---------------------------------------------------------------- AC 3 contract

    @Test
    void ac3_contractFilters() throws Exception {
        baseData();
        assertThat(names("symbol=AAPL&expirationDate=2026-12-18&strike=200&optionType=PUT"))
                .containsExactly("T4", "T1");
        assertThat(names("symbol=AAPL&expirationDate=2026-12-18&strike=200.00&optionType=PUT"))
                .containsExactly("T4", "T1");
        assertThat(names("expirationDate=2026-12-18")).containsExactly("T4", "T2", "T1", "T3", "T6");
        assertThat(names("optionType=CALL")).containsExactly("T5", "T2");
        assertThat(names("strike=12.5")).containsExactly("T3");
        assertThat(names("strike=12.500")).containsExactly("T3");
        assertThat(names("symbol=XYZ&expirationDate=2026-12-18&strike=200&optionType=PUT")).containsExactly("T6");

        assertThat(error("strike=0", "VALIDATION_ERROR", "strike")).containsExactly("行权价必须大于 0");
        assertThat(error("strike=-5", "VALIDATION_ERROR", "strike")).containsExactly("行权价必须大于 0");
        assertThat(error("strike=200.1234", "VALIDATION_ERROR", "strike")).containsExactly("行权价最多 3 位小数");
        assertThat(error("strike=10000000", "VALIDATION_ERROR", "strike")).containsExactly("行权价过大");
        assertThat(error("strike=abc", "BAD_REQUEST", "strike")).containsExactly("行权价必须是数字");
        assertThat(error("expirationDate=2026-02-30", "BAD_REQUEST", "expirationDate"))
                .containsExactly("日期格式不正确（YYYY-MM-DD）");
        assertThat(error("expirationDate=2026-12-1", "BAD_REQUEST", "expirationDate")).hasSize(1);
        assertThat(error("optionType=C", "BAD_REQUEST", "optionType")).containsExactly("类型只能是 CALL 或 PUT");
    }

    // ---------------------------------------------------------------- AC 4 action

    @Test
    void ac4_actionMultiSelect() throws Exception {
        baseData();
        assertThat(names("action=STO")).containsExactly("T4", "T1", "T3", "T6");
        assertThat(names("action=BTO")).containsExactly("T5", "T2");
        assertThat(names("action=BTO&action=STO")).containsExactly("T4", "T5", "T2", "T1", "T3", "T6");
        assertThat(names("action=BTO,STO")).containsExactly("T4", "T5", "T2", "T1", "T3", "T6");
        assertThat(names("action=BTC")).isEmpty();
        assertThat(names("action=STC")).isEmpty();
        assertThat(names("action=BTC&action=STC")).isEmpty();
        assertThat(names("action=")).hasSize(6);
        assertThat(error("action=BUY", "BAD_REQUEST", "action")).containsExactly("方向只能是 BTO、STO、BTC、STC、EXP、ASG、EXR");
        assertThat(error("action=STO&action=sto", "BAD_REQUEST", "action")).hasSize(1);
    }

    // ---------------------------------------------------------------- AC 5 dates

    @Test
    void ac5_newYorkDateRangeInclusive() throws Exception {
        baseData();
        assertThat(names("startDate=2026-11-03&endDate=2026-11-03")).containsExactly("T5", "T2", "T1");
        assertThat(names("startDate=2026-11-04&endDate=2026-11-04")).containsExactly("T4");
        assertThat(names("startDate=2026-11-03")).containsExactly("T4", "T5", "T2", "T1");
        assertThat(names("endDate=2026-11-02")).containsExactly("T3", "T6");
        assertThat(error("startDate=2026-11-04&endDate=2026-11-03", "VALIDATION_ERROR", "endDate"))
                .containsExactly("开始日期不能晚于结束日期");
        assertThat(names("startDate=2026-11-06&endDate=2099-12-31")).isEmpty();
        assertThat(error("startDate=2026/11/03", "BAD_REQUEST", "startDate")).hasSize(1);
    }

    @Test
    void ac5_summerTradeBelongsToItsNewYorkDate() throws Exception {
        deposit("1000.00", "2026-07-01T10:00");
        trade("S", "BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "100.00", "2026-07-15T21:00", null);
        assertThat(list("").get("items").get(0).get("tradedAt").asText()).isEqualTo("2026-07-16T01:00:00Z");
        assertThat(names("startDate=2026-07-15&endDate=2026-07-15")).containsExactly("S");
        assertThat(names("startDate=2026-07-16&endDate=2026-07-16")).isEmpty();
    }

    // ---------------------------------------------------------------- AC 6 combined

    @Test
    void ac6_combinedFilters() throws Exception {
        baseData();
        assertThat(names("symbol=AAPL&action=STO&startDate=2026-11-03&endDate=2026-11-04"))
                .containsExactly("T4", "T1");
        assertThat(names("symbol=AAPL&action=STO&startDate=2026-11-03&endDate=2026-11-03"))
                .containsExactly("T1");
        assertThat(names("")).hasSize(6);
    }

    @Test
    void severalProblemsAreReportedTogether() throws Exception {
        JsonNode err = getJson("/api/option-trades?symbol=%24A&startDate=2026-11-04&endDate=2026-11-03&size=0", 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        List<String> fields = new ArrayList<>();
        err.get("details").forEach(d -> fields.add(d.get("field").asText()));
        assertThat(fields).containsExactly("endDate", "size", "symbol");

        // unparsable values win over rule violations
        JsonNode bad = getJson("/api/option-trades?strike=abc&symbol=%24A", 400);
        assertThat(bad.get("code").asText()).isEqualTo("BAD_REQUEST");
    }

    // ---------------------------------------------------------------- AC 7 pagination

    @Test
    void ac7_pagination() throws Exception {
        deposit("100000.00", "2026-08-01T10:00");
        LocalDate day = LocalDate.parse("2026-09-01");
        for (int i = 0; i < 45; i++) {
            trade("D" + i, "BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "1.00",
                    day.plusDays(i) + "T10:00", null);
        }

        JsonNode p1 = list("");
        assertThat(p1.get("totalItems").asLong()).isEqualTo(45);
        assertThat(p1.get("totalPages").asInt()).isEqualTo(3);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("items").get(0).get("tradedAtLocal").asText()).isEqualTo("2026-10-15T10:00:00");

        JsonNode p3 = list("page=3&size=20");
        assertThat(p3.get("items")).hasSize(5);
        assertThat(p3.get("items").get(4).get("tradedAtLocal").asText()).isEqualTo("2026-09-01T10:00:00");

        List<Long> all = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            list("page=" + page).get("items").forEach(i -> all.add(i.get("id").asLong()));
        }
        assertThat(all).hasSize(45).doesNotHaveDuplicates();

        JsonNode p50 = list("size=50");
        assertThat(p50.get("items")).hasSize(45);
        assertThat(p50.get("totalPages").asInt()).isEqualTo(1);

        JsonNode filtered = list("startDate=2026-10-01");
        assertThat(filtered.get("page").asInt()).isEqualTo(1);
        assertThat(filtered.get("totalItems").asLong()).isEqualTo(15);

        JsonNode p4 = list("page=4&size=20");
        assertThat(p4.get("items")).isEmpty();
        assertThat(p4.get("totalItems").asLong()).isEqualTo(45);
        assertThat(p4.get("totalPages").asInt()).isEqualTo(3);

        assertThat(error("size=0", "VALIDATION_ERROR", "size")).containsExactly("size 必须在 1 到 100 之间");
        assertThat(error("size=101", "VALIDATION_ERROR", "size")).containsExactly("size 必须在 1 到 100 之间");
        assertThat(error("page=0", "VALIDATION_ERROR", "page")).containsExactly("page 必须 ≥ 1");
        error("page=abc", "BAD_REQUEST", "page");
    }

    // ---------------------------------------------------------------- AC 8 / 11

    @Test
    void ac8_noTrades() throws Exception {
        deposit("1000.00", "2026-11-02T10:00");
        JsonNode page = list("");
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("totalItems").asLong()).isZero();
        assertThat(page.get("totalPages").asInt()).isZero();
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void ac11_listingIsReadOnly() throws Exception {
        baseData();
        names("symbol=AAPL&action=STO");
        list("page=2&size=5");
        list("startDate=2026-11-03&endDate=2026-11-03");
        assertSummary("99379.75", "44500.00", "54879.75");
        assertThat(getJson("/api/cash/ledger", 200).get("totalItems").asLong()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class)).isEqualTo(6);
    }
}
