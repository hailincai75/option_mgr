package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
 * FEAT-001 acceptance criteria 1-15 (backend part) through the real HTTP API and database.
 * "Now" defaults to 2026-11-12T15:00:00Z (10:00 New York) so the examples' trade times are in the past;
 * single tests move the clock. Each test starts from an empty database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class OptionTradeControllerTest {

    static final Instant DEFAULT_NOW = Instant.parse("2026-11-12T15:00:00Z");

    /** A clock the tests can move. */
    static final class MutableClock extends Clock {
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
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

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

    // ---------------------------------------------------------------- helpers

    /** Defaults: AAPL 2026-12-18, 1 contract, multiplier 100, traded 2026-11-10 10:30 New York. */
    private ObjectNode trade(String action, String strike, String type, String netAmount) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "AAPL");
        n.put("expirationDate", "2026-12-18");
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", netAmount);
        n.put("tradedAtLocal", "2026-11-10T10:30");
        n.put("multiplier", 100);
        n.putNull("note");
        return n;
    }

    private MvcResult postTrade(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/option-trades").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private JsonNode send(ObjectNode body, int expectedStatus) throws Exception {
        MvcResult r = postTrade(body);
        String raw = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return json.readTree(raw);
    }

    private JsonNode ok(ObjectNode body) throws Exception {
        return send(body, 201);
    }

    /** Asserts a 422 with the given code, empty details, and returns the message. */
    private String rejected(ObjectNode body, String code) throws Exception {
        long trades = tradeCount();
        long entries = ledgerCount();
        JsonNode err = send(body, 422);
        assertThat(err.get("code").asText()).isEqualTo(code);
        assertThat(err.get("details")).isEmpty();
        assertThat(err.get("path").asText()).isEqualTo("/api/option-trades");
        assertThat(tradeCount()).as("no trade saved").isEqualTo(trades);
        assertThat(ledgerCount()).as("no ledger entry saved").isEqualTo(entries);
        return err.get("message").asText();
    }

    /** Asserts a 400 VALIDATION_ERROR naming exactly {@code field}; nothing saved. */
    private JsonNode invalid(ObjectNode body, String field) throws Exception {
        long trades = tradeCount();
        JsonNode err = send(body, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        List<String> fields = new ArrayList<>();
        err.get("details").forEach(d -> fields.add(d.get("field").asText()));
        assertThat(fields).as(err.toString()).isNotEmpty().containsOnly(field);
        assertThat(tradeCount()).isEqualTo(trades);
        return err;
    }

    private void deposit(String amount, String nyTime) throws Exception {
        transfer("DEPOSIT", amount, nyTime);
    }

    private void transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        MvcResult r = mvc.perform(post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(n))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
    }

    private JsonNode getJson(String url, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(get(url)).andReturn();
        String raw = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expectedStatus);
        return json.readTree(raw);
    }

    private void assertSummary(String total, String frozen, String available) throws Exception {
        JsonNode s = getJson("/api/cash/summary", 200);
        assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                s.get("availableCash").asText())).containsExactly(total, frozen, available);
    }

    private JsonNode ledgerItems() throws Exception {
        return getJson("/api/cash/ledger", 200).get("items");
    }

    private long tradeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class);
    }

    private long ledgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_entry", Long.class);
    }

    private static ObjectNode with(ObjectNode n, Consumer<ObjectNode> change) {
        ObjectNode copy = n.deepCopy();
        change.accept(copy);
        return copy;
    }

    private ObjectNode stoPut200() {
        return trade("STO", "200", "PUT", "349.35");
    }

    // ---------------------------------------------------------------- AC 1-3

    @Test
    void ac1_stoPutSucceedsAndFreezesCollateral() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");

        JsonNode t = ok(with(stoPut200(), n -> n.put("note", "第一笔 CSP")));

        assertThat(t.get("tradedAt").asText()).isEqualTo("2026-11-10T15:30:00Z");
        assertThat(t.get("tradedAtLocal").asText()).isEqualTo("2026-11-10T10:30:00");
        assertThat(t.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(t.get("expirationDate").asText()).isEqualTo("2026-12-18");
        assertThat(t.get("strike").asText()).isEqualTo("200");
        assertThat(t.get("optionType").asText()).isEqualTo("PUT");
        assertThat(t.get("action").asText()).isEqualTo("STO");
        assertThat(t.get("contracts").isInt()).isTrue();
        assertThat(t.get("contracts").asInt()).isEqualTo(1);
        assertThat(t.get("multiplier").asInt()).isEqualTo(100);
        assertThat(t.get("netAmount").asText()).isEqualTo("349.35");
        assertThat(t.get("netAmount").isTextual()).isTrue();
        assertThat(t.get("cashChange").asText()).isEqualTo("349.35");
        assertThat(t.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("AAPL 2026-12-18 200 P");
        assertThat(t.get("note").asText()).isEqualTo("第一笔 CSP");
        assertThat(t.get("createdAt").asText()).isEqualTo("2026-11-12T15:00:00Z");
        assertThat(jdbc.queryForObject("SELECT traded_at FROM option_trade", Instant.class))
                .isEqualTo(Instant.parse("2026-11-10T15:30:00Z"));

        assertSummary("30349.35", "20000.00", "10349.35");

        JsonNode rows = ledgerItems();
        assertThat(rows).hasSize(2);
        JsonNode row = rows.get(0);
        assertThat(row.get("occurredAt").asText()).isEqualTo("2026-11-10T15:30:00Z");
        assertThat(row.get("sourceType").asText()).isEqualTo("OPTION_OPEN");
        assertThat(row.get("sourceId").asLong()).isEqualTo(t.get("id").asLong());
        assertThat(row.get("id").asLong()).isEqualTo(t.get("ledgerEntryId").asLong());
        assertThat(row.get("amount").asText()).isEqualTo("349.35");
        assertThat(row.get("frozenChange").asText()).isEqualTo("20000.00");
        assertThat(row.get("balanceAfter").asText()).isEqualTo("30349.35");
        assertThat(row.get("description").asText())
                .isEqualTo("STO 1 张 AAPL 2026-12-18 200 P")
                .contains("STO", "AAPL", "2026-12-18", "200", "P", "1 张");
        assertThat(row.get("remark").asText()).isEqualTo("第一笔 CSP");
        assertThat(rows.get(1).get("frozenChange").asText()).isEqualTo("0.00");

        // query by id returns the same body
        assertThat(getJson("/api/option-trades/" + t.get("id").asLong(), 200)).isEqualTo(t);
    }

    @Test
    void ac2_severalContractsAndDecimalStrike() throws Exception {
        deposit("5000.00", "2026-11-02T10:00");

        JsonNode t = ok(with(trade("STO", "12.5", "PUT", "48.70"), n -> {
            n.put("symbol", "F");
            n.put("contracts", 2);
        }));

        assertThat(t.get("strike").asText()).isEqualTo("12.5");
        assertThat(t.get("frozenChange").asText()).isEqualTo("2500.00");
        assertThat(t.get("contractLabel").asText()).isEqualTo("F 2026-12-18 12.5 P");
        assertSummary("5048.70", "2500.00", "2548.70");
        assertThat(ledgerItems().get(0).get("description").asText()).isEqualTo("STO 2 张 F 2026-12-18 12.5 P");
    }

    @Test
    void ac3_btoSucceeds() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");

        JsonNode t = ok(trade("BTO", "210", "CALL", "512.65"));

        assertThat(t.get("cashChange").asText()).isEqualTo("-512.65");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("29487.35", "0.00", "29487.35");
        JsonNode row = ledgerItems().get(0);
        assertThat(row.get("amount").asText()).isEqualTo("-512.65");
        assertThat(row.get("frozenChange").asText()).isEqualTo("0.00");
        assertThat(row.get("description").asText()).isEqualTo("BTO 1 张 AAPL 2026-12-18 210 C");
    }

    // ---------------------------------------------------------------- AC 4-6 cash

    @Test
    void ac4_btoOverAvailableIsRejectedThenExactAmountSucceeds() throws Exception {
        deposit("500.00", "2026-11-02T10:00");

        String msg = rejected(trade("BTO", "210", "CALL", "512.65"), "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足：需要 512.65，可用 500.00");
        assertSummary("500.00", "0.00", "500.00");
        assertThat(tradeCount()).isZero();
        assertThat(ledgerCount()).isEqualTo(1);

        ok(trade("BTO", "210", "CALL", "500.00"));
        assertSummary("0.00", "0.00", "0.00");
    }

    @Test
    void ac5_frozenCashCannotBeUsedForBuying() throws Exception {
        deposit("20500.00", "2026-11-02T10:00");
        ok(stoPut200());
        assertSummary("20849.35", "20000.00", "849.35");

        ObjectNode msft = with(trade("BTO", "450", "CALL", "900.00"), n -> {
            n.put("symbol", "MSFT");
            n.put("tradedAtLocal", "2026-11-11T10:00");
        });
        assertThat(rejected(msft, "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 900.00，可用 849.35");

        ok(with(msft, n -> n.put("netAmount", "849.35")));
        assertSummary("20000.00", "20000.00", "0.00");
    }

    @Test
    void ac6_putCollateralDoesNotCountThePremium() throws Exception {
        deposit("19999.99", "2026-11-02T10:00");

        String msg = rejected(stoPut200(), "CASH_INSUFFICIENT");
        assertThat(msg).isEqualTo("可用现金不足以担保卖出 put：需要 20,000.00，可用 19,999.99");
        assertSummary("19999.99", "0.00", "19999.99");

        emptyDatabase();
        deposit("20000.00", "2026-11-02T10:00");
        ok(stoPut200());
        assertSummary("20349.35", "20000.00", "349.35");
    }

    // ---------------------------------------------------------------- AC 7-10 positions

    @Test
    void ac7_sameContractOpenedTwiceIsTwoTrades() throws Exception {
        deposit("50000.00", "2026-11-02T10:00");
        JsonNode first = ok(stoPut200());
        JsonNode second = ok(with(stoPut200(), n -> {
            n.put("netAmount", "360.00");
            n.put("tradedAtLocal", "2026-11-11T11:00");
        }));

        assertThat(second.get("id").asLong()).isNotEqualTo(first.get("id").asLong());
        assertThat(tradeCount()).isEqualTo(2);
        assertSummary("50709.35", "40000.00", "10709.35");
    }

    @Test
    void ac8_longShortConflict() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");
        ok(stoPut200());

        ObjectNode bto = with(trade("BTO", "200", "PUT", "300.00"), n -> n.put("tradedAtLocal", "2026-11-11T10:00"));
        String msg = rejected(bto, "POSITION_DIRECTION_CONFLICT");
        assertThat(msg).isEqualTo("该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张），不能买入开仓；"
                + "如要平仓请使用 Buy to Close（BTC）");
        assertSummary("30349.35", "20000.00", "10349.35");

        // a different strike is a different contract
        ok(with(bto, n -> n.put("strike", "205")));

        // long call, then STO the same call: still Q7 (hint Sell to Close)
        ok(with(trade("BTO", "210", "CALL", "512.65"), n -> n.put("tradedAtLocal", "2026-11-11T10:30")));
        ObjectNode sto = with(trade("STO", "210", "CALL", "500.00"), n -> n.put("tradedAtLocal", "2026-11-11T11:00"));
        assertThat(rejected(sto, "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；"
                        + "如要平仓请使用 Sell to Close（STC）");
    }

    /** FEAT-001 AC9 was "STO call rejected" (Q16); superseded by FEAT-038: allowed, nothing frozen. */
    @Test
    void ac9_stoCallIsAllowedSinceFeat038() throws Exception {
        deposit("100000.00", "2026-11-02T10:00");
        // FEAT-039: the call needs 100 covering shares (a transfer-in has no cash effect)
        assertThat(mvc.perform(post("/api/stock-trades").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TRANSFER_IN\",\"symbol\":\"AAPL\",\"shares\":100,\"amount\":\"15000.00\","
                        + "\"tradeDate\":\"2026-11-02\"}")).andReturn().getResponse().getStatus()).isEqualTo(201);

        JsonNode t = ok(trade("STO", "250", "CALL", "120.00"));
        assertThat(t.get("cashChange").asText()).isEqualTo("120.00");
        assertThat(t.get("frozenChange").asText()).isEqualTo("0.00");
        assertSummary("100120.00", "0.00", "100120.00");
    }

    @Test
    void ac10_symbolIsNormalized() throws Exception {
        deposit("30000.00", "2026-11-02T10:00");
        ok(stoPut200());

        ObjectNode bto = with(trade("BTO", "200", "PUT", "300.00"), n -> {
            n.put("symbol", " aapl ");
            n.put("tradedAtLocal", "2026-11-11T10:00");
        });
        assertThat(rejected(bto, "POSITION_DIRECTION_CONFLICT")).contains("AAPL 2026-12-18 200 P", "Buy to Close");

        deposit("40000.00", "2026-11-03T10:00");
        JsonNode msft = ok(with(trade("STO", "400", "PUT", "500.00"), n -> n.put("symbol", " msft ")));
        assertThat(msft.get("symbol").asText()).isEqualTo("MSFT");
        assertThat(getJson("/api/option-trades/" + msft.get("id").asLong(), 200).get("symbol").asText())
                .isEqualTo("MSFT");
        assertThat(jdbc.queryForObject("SELECT symbol FROM option_trade WHERE id = ?", String.class,
                msft.get("id").asLong())).isEqualTo("MSFT");
    }

    // ---------------------------------------------------------------- AC 11 time

    @Test
    void ac11_tradeTimeIsNewYorkTimeStoredAsUtc() throws Exception {
        deposit("30000.00", "2026-07-01T10:00");

        JsonNode summer = ok(with(trade("BTO", "210", "CALL", "100.00"), n -> n.put("tradedAtLocal", "2026-07-15T10:30")));
        assertThat(summer.get("tradedAt").asText()).isEqualTo("2026-07-15T14:30:00Z");
        assertThat(summer.get("tradedAtLocal").asText()).isEqualTo("2026-07-15T10:30:00");
        JsonNode read = getJson("/api/option-trades/" + summer.get("id").asLong(), 200);
        assertThat(read.get("tradedAt").asText()).isEqualTo("2026-07-15T14:30:00Z");
        assertThat(read.get("tradedAtLocal").asText()).isEqualTo("2026-07-15T10:30:00");

        JsonNode winter = ok(trade("BTO", "210", "CALL", "100.00"));
        assertThat(winter.get("tradedAt").asText()).isEqualTo("2026-11-10T15:30:00Z");
    }

    // ---------------------------------------------------------------- AC 12 validation

    @Test
    void ac12_fieldValidation() throws Exception {
        deposit("100000.00", "2026-11-02T10:00");
        ObjectNode base = stoPut200();

        for (String bad : new String[] {"0", "-1", "1.5"}) {
            JsonNode err = invalid(with(base, n -> n.put("contracts", new java.math.BigDecimal(bad))), "contracts");
            assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("张数必须是正整数");
        }
        invalid(with(base, n -> n.put("contracts", "1.5")), "contracts");
        invalid(with(base, n -> n.putNull("contracts")), "contracts");

        assertThat(invalid(with(base, n -> n.put("netAmount", "0")), "netAmount")
                .get("details").get(0).get("message").asText()).isEqualTo("净额必须大于 0");
        invalid(with(base, n -> n.put("netAmount", "-10.00")), "netAmount");
        assertThat(invalid(with(base, n -> n.put("netAmount", "349.355")), "netAmount")
                .get("details").get(0).get("message").asText()).isEqualTo("净额最多 2 位小数");

        invalid(with(base, n -> n.put("strike", "0")), "strike");
        invalid(with(base, n -> n.put("strike", "-5")), "strike");
        assertThat(invalid(with(base, n -> n.put("strike", "200.1234")), "strike")
                .get("details").get(0).get("message").asText()).isEqualTo("行权价最多 3 位小数");

        invalid(with(base, n -> n.put("multiplier", 0)), "multiplier");
        assertThat(invalid(with(base, n -> n.put("multiplier", new java.math.BigDecimal("10.5"))), "multiplier")
                .get("details").get(0).get("message").asText()).isEqualTo("multiplier 必须是正整数");

        assertThat(invalid(with(base, n -> n.put("symbol", "")), "symbol")
                .get("details").get(0).get("message").asText()).isEqualTo("标的不能为空");
        invalid(with(base, n -> n.put("symbol", "   ")), "symbol");
        assertThat(invalid(with(base, n -> n.put("symbol", "AA PL")), "symbol")
                .get("details").get(0).get("message").asText()).isEqualTo("标的只能包含字母、数字和点");
        invalid(with(base, n -> n.put("symbol", "$AAPL")), "symbol");
        assertThat(invalid(with(base, n -> n.put("symbol", "ABCDEFGHIJK")), "symbol")
                .get("details").get(0).get("message").asText()).isEqualTo("标的最多 10 个字符");

        assertThat(invalid(with(base, n -> n.put("note", "x".repeat(501))), "note")
                .get("details").get(0).get("message").asText()).isEqualTo("备注最多 500 个字符");

        assertThat(invalid(with(base, n -> n.put("expirationDate", "2026-11-09")), "expirationDate")
                .get("details").get(0).get("message").asText()).isEqualTo("到期日不能早于成交日（2026-11-10）");

        assertThat(invalid(with(base, n -> n.put("action", "BTC")), "action")
                .get("details").get(0).get("message").asText())
                .isEqualTo("本功能只支持开仓（BTO / STO），平仓请使用平仓功能");

        // future trade time: now 2026-11-10 09:45 New York
        clock.set(Instant.parse("2026-11-10T14:45:00Z"));
        assertThat(invalid(with(base, n -> n.put("tradedAtLocal", "2026-11-10T10:00")), "tradedAtLocal")
                .get("details").get(0).get("message").asText()).isEqualTo("成交时间不能晚于当前时间");
        ok(with(base, n -> n.put("tradedAtLocal", "2026-11-10T09:45")));   // equal to now is allowed

        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(tradeCount()).isEqualTo(1);
    }

    @Test
    void ac12_zeroDteIsAllowed() throws Exception {
        clock.set(Instant.parse("2026-12-18T16:00:00Z"));   // 11:00 New York
        deposit("30000.00", "2026-11-02T10:00");

        JsonNode t = ok(with(stoPut200(), n -> n.put("tradedAtLocal", "2026-12-18T10:00")));
        assertThat(t.get("expirationDate").asText()).isEqualTo("2026-12-18");
    }

    @Test
    void unparsableValuesAreBadRequests() throws Exception {
        ObjectNode base = stoPut200();
        for (var change : List.<Consumer<ObjectNode>>of(
                n -> n.put("optionType", "C"),
                n -> n.put("action", "BUY"),
                n -> n.put("strike", "abc"),
                n -> n.put("expirationDate", "2026-02-30"),
                n -> n.put("tradedAtLocal", "2026-11-10T10:30:00Z"))) {
            JsonNode err = send(with(base, change), 400);
            assertThat(err.get("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(err.get("details")).hasSize(1);
        }
        assertThat(tradeCount()).isZero();
    }

    // ---------------------------------------------------------------- AC 13-14 backfill (Q19)

    @Test
    void ac13_backfilledBtoIsReplayedInTimeOrder() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        transfer("WITHDRAWAL", "9000.00", "2026-11-05T10:00");
        ObjectNode bto = with(trade("BTO", "210", "CALL", "1500.00"), n -> n.put("tradedAtLocal", "2026-11-03T10:00"));

        assertThat(rejected(bto, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00");

        ok(with(bto, n -> n.put("netAmount", "800.00")));
        assertSummary("200.00", "0.00", "200.00");

        ObjectNode early = with(bto, n -> {
            n.put("netAmount", "100.00");
            n.put("tradedAtLocal", "2026-11-01T10:00");
        });
        assertThat(rejected(early, "CASH_INSUFFICIENT")).isEqualTo("可用现金不足：需要 100.00，可用 0.00");
    }

    @Test
    void ac14_backfilledStoPutAffectsLaterWithdrawal() throws Exception {
        deposit("25000.00", "2026-11-02T10:00");
        transfer("WITHDRAWAL", "5500.00", "2026-11-05T10:00");
        ObjectNode sto = with(stoPut200(), n -> n.put("tradedAtLocal", "2026-11-03T10:00"));

        assertThat(rejected(sto, "CASH_INSUFFICIENT"))
                .isEqualTo("可用现金不足以担保卖出 put：需要 20,000.00；2026-11-05 10:00 时可用现金将变为 −150.65");

        emptyDatabase();
        deposit("25000.00", "2026-11-02T10:00");
        transfer("WITHDRAWAL", "4000.00", "2026-11-05T10:00");
        ok(sto);
        assertSummary("21349.35", "20000.00", "1349.35");
    }

    // ---------------------------------------------------------------- AC 15 multiplier (Q23)

    @Test
    void ac15_multiplierMustMatchOpenPosition() throws Exception {
        deposit("5000.00", "2026-11-02T10:00");
        ObjectNode xyz = with(trade("STO", "200", "PUT", "34.35"), n -> {
            n.put("symbol", "XYZ");
            n.put("multiplier", 10);
        });

        JsonNode t = ok(xyz);
        assertThat(t.get("multiplier").asInt()).isEqualTo(10);
        assertThat(t.get("frozenChange").asText()).isEqualTo("2000.00");
        assertSummary("5034.35", "2000.00", "3034.35");
        assertThat(ledgerItems().get(0).get("description").asText())
                .isEqualTo("STO 1 张 XYZ 2026-12-18 200 P（multiplier 10）");

        String msg = rejected(with(xyz, n -> {
            n.put("multiplier", 100);
            n.put("tradedAtLocal", "2026-11-11T10:00");
        }), "MULTIPLIER_MISMATCH");
        assertThat(msg).isEqualTo("该合约（XYZ 2026-12-18 200 P）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同");
    }

    // ---------------------------------------------------------------- other contract details

    @Test
    void multiplierDefaultsTo100AndStrikeIsComparedByValue() throws Exception {
        deposit("50000.00", "2026-11-02T10:00");
        ObjectNode noMultiplier = stoPut200();
        noMultiplier.remove("multiplier");
        assertThat(ok(noMultiplier).get("multiplier").asInt()).isEqualTo(100);

        // "200.000" is the same contract as "200": BTO conflicts with the short
        ObjectNode bto = with(trade("BTO", "200.000", "PUT", "300.00"), n -> n.put("tradedAtLocal", "2026-11-11T10:00"));
        rejected(bto, "POSITION_DIRECTION_CONFLICT");
    }

    @Test
    void backfillMustNotCreateALaterConflict() throws Exception {
        deposit("50000.00", "2026-11-02T10:00");
        ok(with(stoPut200(), n -> n.put("tradedAtLocal", "2026-11-11T11:00")));

        ObjectNode earlierBto = with(trade("BTO", "200", "PUT", "300.00"), n -> n.put("tradedAtLocal", "2026-11-10T10:00"));
        assertThat(rejected(earlierBto, "POSITION_DIRECTION_CONFLICT"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-11 11:00 的 STO 将与多头持仓（1 张）冲突；"
                        + "一个合约任一时刻只能纯多或纯空");

        ObjectNode earlierSto = with(stoPut200(), n -> {
            n.put("multiplier", 10);
            n.put("tradedAtLocal", "2026-11-10T10:00");
        });
        assertThat(rejected(earlierSto, "MULTIPLIER_MISMATCH"))
                .isEqualTo("补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-11 11:00 的交易 multiplier 为 100，"
                        + "与当时持仓的 multiplier 10 不一致");
    }

    @Test
    void blankNoteIsStoredAsNullAndSymbolWithDotIsAccepted() throws Exception {
        deposit("50000.00", "2026-11-02T10:00");
        JsonNode t = ok(with(trade("BTO", "500", "CALL", "100.00"), n -> {
            n.put("symbol", "brk.b");
            n.put("note", "   ");
        }));
        assertThat(t.get("symbol").asText()).isEqualTo("BRK.B");
        assertThat(t.get("note").isNull()).isTrue();
    }

    @Test
    void getUnknownOrInvalidId() throws Exception {
        JsonNode notFound = getJson("/api/option-trades/99", 404);
        assertThat(notFound.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(notFound.get("message").asText()).isEqualTo("Option trade 99 not found");

        JsonNode bad = getJson("/api/option-trades/abc", 400);
        assertThat(bad.get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(bad.get("details").get(0).get("field").asText()).isEqualTo("id");
    }
}
