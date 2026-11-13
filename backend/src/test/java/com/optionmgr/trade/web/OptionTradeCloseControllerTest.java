package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
 * FEAT-004 acceptance criteria (backend part) through the real HTTP API and database.
 * "Now" is 2026-11-11 10:00 New York (15:00Z).
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class OptionTradeCloseControllerTest {

    static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
    static final String CLOSES = "/api/option-trades/closes";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock closeTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        emptyDatabase();
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

    // ---------------------------------------------------------------- helpers

    private JsonNode send(String url, ObjectNode body, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return json.readTree(raw);
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult r = mvc.perform(get(URI.create(url))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(200);
        return json.readTree(raw);
    }

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send("/api/cash/transfers", n, 201);
    }

    private ObjectNode contract(String symbol, String exp, String strike, String type) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        return n;
    }

    private long open(String action, String symbol, String exp, String strike, String type, int contracts,
                      int multiplier, String net, String nyTime) throws Exception {
        ObjectNode n = contract(symbol, exp, strike, type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("multiplier", multiplier);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return send("/api/option-trades", n, 201).get("id").asLong();
    }

    private ObjectNode closeBody(String action, String symbol, String exp, String strike, String type,
                                 int contracts, String net, String nyTime) {
        ObjectNode n = contract(symbol, exp, strike, type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        n.putNull("note");
        return n;
    }

    /** AAPL 2026-12-18 200 P close. */
    private ObjectNode aaplPut(String action, int contracts, String net, String nyTime) {
        return closeBody(action, "AAPL", "2026-12-18", "200", "PUT", contracts, net, nyTime);
    }

    private JsonNode closeOk(ObjectNode body) throws Exception {
        return send(CLOSES, body, 201);
    }

    private String closeRejected(ObjectNode body, String code) throws Exception {
        long trades = count("option_trade");
        long ledger = count("cash_ledger_entry");
        JsonNode summary = getJson("/api/cash/summary");
        JsonNode err = send(CLOSES, body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("details")).isEmpty();
        assertThat(count("option_trade")).isEqualTo(trades);
        assertThat(count("cash_ledger_entry")).isEqualTo(ledger);
        assertThat(getJson("/api/cash/summary")).isEqualTo(summary);
        return err.get("message").asText();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary");
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private static List<String> covered(JsonNode trade) {
        List<String> out = new ArrayList<>();
        trade.get("coveredLots").forEach(l -> out.add(l.get("tradeId").asText() + " " + l.get("action").asText()
                + " " + l.get("tradedAtLocal").asText() + " " + l.get("contracts").asText() + " "
                + l.get("netAmount").asText() + " " + l.get("frozen").asText()));
        return out;
    }

    private JsonNode positions() throws Exception {
        return getJson("/api/option-positions");
    }

    private static JsonNode position(JsonNode positions, String label) {
        for (JsonNode p : positions.get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private long l1;
    private long l2;

    private void scenarioA() throws Exception {
        deposit("80000.00", "2026-10-26T10:00");
        l1 = open("STO", "AAPL", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-03T10:30");
        l2 = open("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00");
        assertSummary("81058.70", "60000.00", "21058.70");
        JsonNode p = position(positions(), "AAPL 2026-12-18 200 P");
        assertThat(ints(p.get("allowedCloseContracts"))).containsExactly(2, 3);
        assertThat(p.get("closeAction").asText()).isEqualTo("BTC");
        assertThat(p.get("closable").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_closeEarliestWholeLotReleasesFrozen() throws Exception {
        scenarioA();
        JsonNode t = closeOk(aaplPut("BTC", 2, "241.30", "2026-11-05T10:00"));

        assertThat(t.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(t.get("action").asText()).isEqualTo("BTC");
        assertThat(t.get("contracts").asInt()).isEqualTo(2);
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("netAmount").asText()).isEqualTo("241.30");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-05T10:00:00");
        assertThat(t.get("cashChange").asText()).isEqualTo("-241.30");
        assertThat(t.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(covered(t)).containsExactly(l1 + " STO 2026-11-03T10:30:00 2 698.70 40000.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("457.40");
        assertThat(getJson("/api/option-trades/" + t.get("id").asLong())).isEqualTo(t);

        assertSummary("80817.40", "20000.00", "60817.40");
        JsonNode row = getJson("/api/cash/ledger").get("items").get(0);
        assertThat(row.get("sourceType").asText()).isEqualTo("OPTION_CLOSE");
        assertThat(row.get("sourceId").asLong()).isEqualTo(t.get("id").asLong());
        assertThat(row.get("amount").asText()).isEqualTo("-241.30");
        assertThat(row.get("frozenChange").asText()).isEqualTo("-40000.00");
        assertThat(row.get("description").asText()).isEqualTo("BTC 2 张 AAPL 2026-12-18 200 P");

        JsonNode ps = positions();
        JsonNode p = position(ps, "AAPL 2026-12-18 200 P");
        assertThat(p.get("direction").asText()).isEqualTo("SHORT");
        assertThat(p.get("contracts").asInt()).isEqualTo(1);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("360.00");
        assertThat(p.get("averageNetPerContract").asText()).isEqualTo("360.00");
        assertThat(p.get("frozenCash").asText()).isEqualTo("20000.00");
        assertThat(p.get("lotCount").asInt()).isEqualTo(1);
        assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(l2);
        assertThat(ints(p.get("allowedCloseContracts"))).containsExactly(1);
        assertThat(ps.get("frozenTotal").asText()).isEqualTo("20000.00");

        JsonNode list = getJson("/api/option-trades");
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(t.get("id").asLong());
        assertThat(first).isEqualTo(t);
        JsonNode btcOnly = getJson("/api/option-trades?action=BTC");
        assertThat(btcOnly.get("totalItems").asLong()).isEqualTo(1);
        // opens carry null close fields
        JsonNode openItem = list.get("items").get(1);
        assertThat(openItem.get("coveredLots").isNull()).isTrue();
        assertThat(openItem.get("realizedPnl").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- AC 2-4

    @Test
    void ac2_noPartialCloseOfALot() throws Exception {
        scenarioA();
        assertThat(closeRejected(aaplPut("BTC", 1, "120.65", "2026-11-05T10:00"), "CLOSE_NOT_WHOLE_LOTS"))
                .isEqualTo("必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张");
        assertThat(position(positions(), "AAPL 2026-12-18 200 P").get("contracts").asInt()).isEqualTo(3);
    }

    @Test
    void ac3_closeSeveralLotsPnlAsAWhole() throws Exception {
        scenarioA();
        JsonNode t = closeOk(aaplPut("BTC", 3, "400.00", "2026-11-05T10:00"));
        assertThat(t.get("frozenChange").asText()).isEqualTo("-60000.00");
        assertThat(covered(t)).containsExactly(
                l1 + " STO 2026-11-03T10:30:00 2 698.70 40000.00",
                l2 + " STO 2026-11-04T11:00:00 1 360.00 20000.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("658.70");
        assertSummary("80658.70", "0.00", "80658.70");
        JsonNode ps = positions();
        assertThat(ps.get("positionCount").asInt()).isZero();
        assertThat(ps.get("frozenTotal").asText()).isEqualTo("0.00");
    }

    @Test
    void ac4_exceedsWrongDirectionNoPosition() throws Exception {
        scenarioA();
        assertThat(closeRejected(aaplPut("BTC", 4, "1.00", "2026-11-05T10:00"), "CLOSE_EXCEEDS_POSITION"))
                .isEqualTo("平仓 4 张超过当时持仓 3 张");
        assertThat(closeRejected(aaplPut("STC", 2, "1.00", "2026-11-05T10:00"), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（3 张），应使用 Buy to Close（BTC）");
        assertThat(closeRejected(closeBody("BTC", "AAPL", "2026-12-18", "205", "PUT", 1, "1.00",
                "2026-11-05T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("该合约（AAPL 2026-12-18 205 P）在成交时没有持仓，无法平仓");
        assertThat(closeRejected(aaplPut("BTC", 2, "1.00", "2026-11-03T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓");
    }

    // ---------------------------------------------------------------- AC 5-8

    @Test
    void ac5_closeAllThenReopenOtherSide() throws Exception {
        scenarioA();
        closeOk(aaplPut("BTC", 2, "241.30", "2026-11-05T10:00"));
        JsonNode t = closeOk(aaplPut("BTC", 1, "150.00", "2026-11-06T10:00"));
        assertThat(t.get("realizedPnl").asText()).isEqualTo("210.00");
        assertSummary("80667.40", "0.00", "80667.40");
        assertThat(position(positions(), "AAPL 2026-12-18 200 P")).isNull();

        open("BTO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "300.00", "2026-11-06T11:00");
        JsonNode p = position(positions(), "AAPL 2026-12-18 200 P");
        assertThat(p.get("direction").asText()).isEqualTo("LONG");
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("-300.00");
        assertThat(p.get("closeAction").asText()).isEqualTo("STC");
        assertSummary("80367.40", "0.00", "80367.40");
    }

    @Test
    void ac6_releaseFrozenBeforeCashCheck() throws Exception {
        deposit("20000.00", "2026-10-26T10:00");
        open("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30");
        assertSummary("20349.35", "20000.00", "349.35");

        JsonNode t = closeOk(aaplPut("BTC", 1, "500.00", "2026-11-05T10:00"));
        assertThat(t.get("realizedPnl").asText()).isEqualTo("-150.65");
        assertSummary("19849.35", "0.00", "19849.35");

        emptyDatabase();
        deposit("20000.00", "2026-10-26T10:00");
        open("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30");
        assertThat(closeRejected(aaplPut("BTC", 1, "20349.36", "2026-11-05T10:00"), "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 20,349.36，可用 349.35 + 释放冻结 20,000.00 = 20,349.35");
        JsonNode exact = closeOk(aaplPut("BTC", 1, "20349.35", "2026-11-05T10:00"));
        assertThat(exact.get("realizedPnl").asText()).isEqualTo("-20000.00");
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    void ac7_sellToCloseLongCall() throws Exception {
        deposit("10000.00", "2026-10-26T10:00");
        open("BTO", "AAPL", "2026-12-18", "210", "CALL", 1, 100, "512.65", "2026-11-03T10:30");
        assertSummary("9487.35", "0.00", "9487.35");
        ObjectNode call = closeBody("STC", "AAPL", "2026-12-18", "210", "CALL", 1, "830.00", "2026-11-05T10:00");

        assertThat(closeRejected(with(call, n -> n.put("action", "BTC")), "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），应使用 Sell to Close（STC）");

        JsonNode t = closeOk(call);
        assertThat(t.get("cashChange").asText()).isEqualTo("830.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("317.35");
        assertSummary("10317.35", "0.00", "10317.35");
        assertThat(positions().get("positionCount").asInt()).isZero();
        assertThat(getJson("/api/cash/ledger").get("items").get(0).get("description").asText())
                .isEqualTo("STC 1 张 AAPL 2026-12-18 210 C");
    }

    @Test
    void ac8_noCloseAfterExpirationButOnExpirationDay() throws Exception {
        deposit("50000.00", "2026-10-26T10:00");
        open("STO", "TSLA", "2026-11-06", "250", "PUT", 1, 100, "300.00", "2026-11-02T10:00");
        assertSummary("50300.00", "25000.00", "25300.00");
        JsonNode p = position(positions(), "TSLA 2026-11-06 250 P");
        assertThat(p.get("closable").asBoolean()).isFalse();

        ObjectNode late = closeBody("BTC", "TSLA", "2026-11-06", "250", "PUT", 1, "10.00", "2026-11-09T10:00");
        JsonNode err = send(CLOSES, late, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").get(0).get("field").asText()).isEqualTo("tradedAtLocal");
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("到期日（2026-11-06）之后不能平仓");

        JsonNode t = closeOk(with(late, n -> n.put("tradedAtLocal", "2026-11-06T16:30")));
        assertThat(t.get("realizedPnl").asText()).isEqualTo("290.00");
        assertSummary("50290.00", "0.00", "50290.00");
        assertThat(position(positions(), "TSLA 2026-11-06 250 P")).isNull();
    }

    @Test
    void ac9_expiresTodayIsClosable() throws Exception {
        deposit("1000.00", "2026-10-26T10:00");
        open("BTO", "SPY", "2026-11-11", "600", "CALL", 1, 100, "50.00", "2026-11-02T10:00");
        JsonNode p = position(positions(), "SPY 2026-11-11 600 C");
        assertThat(p.get("closable").asBoolean()).isTrue();
        assertThat(p.get("closeAction").asText()).isEqualTo("STC");
        assertThat(ints(p.get("allowedCloseContracts"))).containsExactly(1);
    }

    // ---------------------------------------------------------------- AC 10 (Q39)

    @Test
    void ac10_backfilledCloseRematchesOrIsRejected() throws Exception {
        deposit("80000.00", "2026-10-26T10:00");
        long a = open("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "349.35", "2026-11-03T10:30");
        long b = open("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00");
        JsonNode later = closeOk(aaplPut("BTC", 1, "100.00", "2026-11-06T10:00"));
        assertThat(later.get("realizedPnl").asText()).isEqualTo("249.35");
        assertSummary("80609.35", "20000.00", "60609.35");

        assertThat(closeRejected(aaplPut("BTC", 2, "200.00", "2026-11-05T10:00"), "NO_POSITION_TO_CLOSE"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 1 张将没有持仓可平");

        JsonNode backfill = closeOk(aaplPut("BTC", 1, "120.00", "2026-11-05T10:00"));
        assertThat(backfill.get("realizedPnl").asText()).isEqualTo("229.35");
        assertThat(backfill.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(a);

        JsonNode reread = getJson("/api/option-trades/" + later.get("id").asLong());
        assertThat(reread.get("realizedPnl").asText()).isEqualTo("260.00");
        assertThat(reread.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(b);
        assertSummary("80489.35", "0.00", "80489.35");
        assertThat(position(positions(), "AAPL 2026-12-18 200 P")).isNull();
    }

    @Test
    void q39_rematchCorrectsLaterReleasedFrozen() throws Exception {
        // strike 12.345, multiplier 1: lot A (2) freezes 24.69, B and C (1 each) 12.35 (Q28)
        deposit("1000.00", "2026-10-26T10:00");
        open("STO", "F", "2026-12-18", "12.345", "PUT", 2, 1, "1.00", "2026-11-02T10:00");
        open("STO", "F", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-03T10:00");
        open("STO", "F", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-04T10:00");
        ObjectNode close2 = closeBody("BTC", "F", "2026-12-18", "12.345", "PUT", 2, "0.20", "2026-11-06T10:00");
        JsonNode later = closeOk(close2);
        assertThat(later.get("frozenChange").asText()).isEqualTo("-24.69");
        assertSummary("1001.80", "24.70", "977.10");

        closeOk(with(close2, n -> n.put("tradedAtLocal", "2026-11-05T10:00")));
        JsonNode reread = getJson("/api/option-trades/" + later.get("id").asLong());
        assertThat(reread.get("frozenChange").asText()).isEqualTo("-24.70");
        assertThat(reread.get("coveredLots")).hasSize(2);
        assertSummary("1001.60", "0.00", "1001.60");
        assertThat(positions().get("frozenTotal").asText()).isEqualTo("0.00");
    }

    @Test
    void backfilledOpenMustNotBreakLaterWholeLotClose() throws Exception {
        scenarioA();
        closeOk(aaplPut("BTC", 2, "241.30", "2026-11-05T10:00"));
        // an earlier 1-contract lot would make the existing 2-contract close cover 1 + ... -> not whole lots
        JsonNode err = send("/api/option-trades", with(json.createObjectNode(), n -> {
            n.setAll(contract("AAPL", "2026-12-18", "200", "PUT"));
            n.put("action", "STO");
            n.put("contracts", 1);
            n.put("netAmount", "100.00");
            n.put("tradedAtLocal", "2026-11-02T10:00");
        }), 422);
        assertThat(err.get("code").asText()).isEqualTo("CLOSE_NOT_WHOLE_LOTS");
        assertThat(err.get("message").asText()).isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 "
                + "的 BTC 2 张将不是整笔开仓（最早一笔是 1 张，可平 1 张、3 张或 4 张）");
    }

    // ---------------------------------------------------------------- AC 11

    @Test
    void ac11_fieldValidationAndInheritedMultiplier() throws Exception {
        scenarioA();
        ObjectNode base = aaplPut("BTC", 2, "241.30", "2026-11-05T10:00");
        for (var change : List.<Consumer<ObjectNode>>of(
                n -> n.put("contracts", 0), n -> n.put("contracts", -1), n -> n.put("contracts", new BigDecimal("1.5")))) {
            assertValidation(with(base, change), "contracts", "张数必须是正整数");
        }
        assertValidation(with(base, n -> n.put("netAmount", "0")), "netAmount", "净额必须大于 0");
        assertValidation(with(base, n -> n.put("netAmount", "-10.00")), "netAmount", "净额必须大于 0");
        assertValidation(with(base, n -> n.put("netAmount", "241.305")), "netAmount", "净额最多 2 位小数");
        assertValidation(with(base, n -> n.put("tradedAtLocal", "2026-11-11T10:01")), "tradedAtLocal",
                "成交时间不能晚于当前时间");
        assertValidation(with(base, n -> n.put("note", "x".repeat(501))), "note", "备注最多 500 个字符");
        assertValidation(with(base, n -> n.put("action", "BTO")), "action",
                "本功能只支持平仓（BTC / STC），开仓请使用开仓功能");
        assertThat(count("option_trade")).isEqualTo(2);

        emptyDatabase();
        deposit("5000.00", "2026-10-26T10:00");
        open("STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-11-03T10:30");
        assertSummary("5034.35", "2000.00", "3034.35");
        // an extra multiplier property is ignored
        JsonNode t = closeOk(with(closeBody("BTC", "XYZ", "2026-12-18", "200", "PUT", 1, "10.00", "2026-11-05T10:00"),
                n -> n.put("multiplier", 100)));
        assertThat(t.get("multiplier").asInt()).isEqualTo(10);
        assertThat(t.get("frozenChange").asText()).isEqualTo("-2000.00");
        assertThat(t.get("realizedPnl").asText()).isEqualTo("24.35");
        assertThat(getJson("/api/cash/ledger").get("items").get(0).get("description").asText())
                .isEqualTo("BTC 1 张 XYZ 2026-12-18 200 P（multiplier 10）");
        assertSummary("5024.35", "0.00", "5024.35");
    }

    @Test
    void openEndpointStillRejectsClosingActions() throws Exception {
        ObjectNode n = contract("AAPL", "2026-12-18", "200", "PUT");
        n.put("action", "BTC");
        n.put("contracts", 1);
        n.put("netAmount", "1.00");
        n.put("tradedAtLocal", "2026-11-05T10:00");
        JsonNode err = send("/api/option-trades", n, 400);
        assertThat(err.get("details").get(0).get("message").asText())
                .isEqualTo("本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
    }

    private void assertValidation(ObjectNode body, String field, String message) throws Exception {
        JsonNode err = send(CLOSES, body, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        List<String> got = new ArrayList<>();
        err.get("details").forEach(d -> got.add(d.get("field").asText() + ":" + d.get("message").asText()));
        assertThat(got).containsExactly(field + ":" + message);
    }

    private static ObjectNode with(ObjectNode n, Consumer<ObjectNode> change) {
        ObjectNode copy = n.deepCopy();
        change.accept(copy);
        return copy;
    }
}
