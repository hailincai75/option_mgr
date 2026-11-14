package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.CoverageService;
import com.optionmgr.trade.service.OptionTradeService;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * FEAT-039 acceptance criteria (backend part): covered calls. Fictional tickers only; all times New York. The clock
 * is 2026-11-13 10:00 unless a test sets it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class CoveredCallControllerTest {

    static final Instant NOV_13_10AM = Instant.parse("2026-11-13T15:00:00Z");
    static final Instant NOV_23_10AM = Instant.parse("2026-11-23T15:00:00Z");
    static final Instant DEC_21_11AM = Instant.parse("2026-12-21T16:00:00Z");

    static final class TestClock extends Clock {
        private volatile Instant now = NOV_13_10AM;

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
        TestClock coveredCallTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String STOCKS = "/api/stock-trades";
    static final String EXP = "2026-12-18";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    OptionTradeService optionTrades;

    @Autowired
    CoverageService coverage;

    private String account = "1";
    private long s1;
    private long c1;
    private long c2;

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
        clock.set(NOV_13_10AM);
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

    private void rejected(MockHttpServletRequestBuilder req, ObjectNode body, String message) throws Exception {
        JsonNode error = body == null ? call(req, 422) : send(req, body, 422);
        assertThat(error.get("code").asText()).isEqualTo("COVERAGE_INSUFFICIENT");
        assertThat(error.get("message").asText()).isEqualTo(message);
        assertThat(error.get("details")).isEmpty();
    }

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        send(post("/api/cash/transfers"), n, 201);
    }

    private void newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        account = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
    }

    private ObjectNode optionBody(String action, String symbol, String expiration, String strike, String type,
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

    private ObjectNode stoCall(String strike, int contracts, String net, String nyTime) {
        return optionBody("STO", "IXQZ", EXP, strike, "CALL", contracts, net, nyTime, null);
    }

    private long open(ObjectNode body) throws Exception {
        return send(post(TRADES), body, 201).get("id").asLong();
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

    private ObjectNode editOption(int contracts, String net, String nyTime) {
        ObjectNode n = json.createObjectNode();
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return n;
    }

    private long btc(String strike, int contracts, String net, String nyTime) throws Exception {
        ObjectNode n = optionBody("BTC", "IXQZ", EXP, strike, "CALL", contracts, net, nyTime, null);
        return send(post(TRADES + "/closes"), n, 201).get("id").asLong();
    }

    private JsonNode stockPosition(String symbol) throws Exception {
        for (JsonNode p : call(get(URI.create("/api/stock-positions")), 200).get("positions")) {
            if (p.get("symbol").asText().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    private void assertStock(String symbol, long held, long covered, long available) throws Exception {
        JsonNode p = stockPosition(symbol);
        assertThat(p).as(symbol + " position").isNotNull();
        assertThat(p.get("shares").asLong()).isEqualTo(held);
        assertThat(p.get("coveredShares").asLong()).isEqualTo(covered);
        assertThat(p.get("availableShares").asLong()).isEqualTo(available);
    }

    private JsonNode optionRow(String label) throws Exception {
        for (JsonNode p : call(get(URI.create("/api/option-positions")), 200).get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    /** Scenario D in the current account. */
    private void scenarioD() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        s1 = stock("TRANSFER_IN", "IXQZ", 200, "1600.00", "2026-11-02");
        c1 = open(stoCall("10", 1, "50.00", "2026-11-03T10:00"));
        c2 = open(stoCall("11", 1, "30.00", "2026-11-04T10:00"));
    }

    /** Scenario L: L1 entered "before FEAT-039" (LEGACY), no stock. */
    private long scenarioL() throws Exception {
        deposit("2000.00", "2026-11-02T10:00");
        return optionTrades.open(Long.parseLong(account), new OptionTradeService.OpenCommand(
                new ContractKey("IXQZ", LocalDate.parse(EXP), new BigDecimal("10"), OptionType.CALL), TradeAction.STO,
                2, 100, new BigDecimal("90.00"), LocalDateTime.parse("2026-11-03T10:00"), null), true).trade().getId();
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_sellingACallNeedsAvailableShares() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        stock("TRANSFER_IN", "IXQZ", 200, "1600.00", "2026-11-02");
        c1 = open(stoCall("10", 1, "50.00", "2026-11-03T10:00"));
        rejected(post(TRADES), stoCall("11", 2, "30.00", "2026-11-04T10:00"),
                "需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）");
        c2 = open(stoCall("11", 1, "30.00", "2026-11-04T10:00"));

        JsonNode row = optionRow("IXQZ 2026-12-18 10 C");
        assertThat(row.get("coveredShares").asLong()).isEqualTo(100);
        assertThat(row.get("uncoveredContracts").asInt()).isZero();
        assertThat(row.get("uncoveredShortfallAt").isNull()).isTrue();
        assertThat(row.get("lots").get(0).get("coverage").asText()).isEqualTo("CHECKED");
        assertThat(row.get("lots").get(0).get("requiredShares").asLong()).isEqualTo(100);
        assertThat(optionRow("IXQZ 2026-12-18 11 C").get("coveredShares").asLong()).isEqualTo(100);
        assertStock("IXQZ", 200, 200, 0);
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_coveredSharesAreLockedUntilTheCallIsBoughtBack() throws Exception {
        scenarioD();
        rejected(post(STOCKS), stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T10:00"),
                "卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");
        long btc = btc("11", 1, "10.00", "2026-11-05T11:00");
        assertStock("IXQZ", 200, 100, 100);
        rejected(post(STOCKS), stockBody("SELL", "IXQZ", 100, "1000.00", "2026-11-05T10:30"),
                "卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");
        stock("SELL", "IXQZ", 100, "1000.00", "2026-11-05T12:00");
        assertStock("IXQZ", 100, 100, 0);

        rejected(delete(TRADES + "/" + btc), null,
                "删除后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
        rejected(put(TRADES + "/" + btc), editOption(1, "10.00", "2026-11-05T12:30"),
                "修改后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isZero();
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_backfilledSaleThatUncoversLaterCallsIsRejected() throws Exception {
        scenarioD();
        rejected(post(STOCKS), stockBody("SELL", "IXQZ", 100, "900.00", "2026-11-03T12:00"),
                "补录后 2026-11-04 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
    }

    @Test
    void ac3_editingOrDeletingTheSharesIsRejected() throws Exception {
        scenarioD();
        ObjectNode edit = json.createObjectNode();
        edit.put("shares", 150);
        edit.put("amount", "1600.00");
        edit.put("tradeDate", "2026-11-02");
        rejected(put(STOCKS + "/" + s1), edit,
                "修改后 2026-11-04 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 150 股");
        rejected(delete(STOCKS + "/" + s1), null,
                "删除后 2026-11-03 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    @Test
    void ac3_editingACallRechecksItsCoverage() throws Exception {
        scenarioD();
        rejected(put(TRADES + "/" + c2), editOption(2, "30.00", "2026-11-04T10:00"),
                "需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）");
        rejected(put(TRADES + "/" + c1), editOption(1, "50.00", "2026-11-01T10:00"),
                "需要 100 股 IXQZ，可用 0 股（持有 0 股）");
        send(put(TRADES + "/" + c1), editOption(1, "55.00", "2026-11-03T10:00"), 200);
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_expiryAndCallAssignmentReleaseShares() throws Exception {
        scenarioD();
        clock.set(DEC_21_11AM);
        ObjectNode assign = json.createObjectNode();
        assign.put("symbol", "IXQZ");
        assign.put("expirationDate", EXP);
        assign.put("strike", "11");
        assign.put("optionType", "CALL");
        assign.put("contracts", 1);
        assign.put("assignmentDate", EXP);
        send(post(TRADES + "/assignments"), assign, 201);
        assertStock("IXQZ", 100, 100, 0);

        ObjectNode expire = json.createObjectNode();
        expire.put("symbol", "IXQZ");
        expire.put("expirationDate", EXP);
        expire.put("strike", "10");
        expire.put("optionType", "CALL");
        expire.put("contracts", 1);
        long exp = send(post(TRADES + "/expirations"), expire, 201).get("id").asLong();
        assertStock("IXQZ", 100, 0, 100);

        stock("SELL", "IXQZ", 100, "1050.00", "2026-12-21T10:00");
        rejected(delete(TRADES + "/" + exp), null,
                "删除后 2026-12-21 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    @Test
    void ac4_sharesStayLockedBeforeTheExpiryIsRecorded() throws Exception {
        scenarioD();
        clock.set(DEC_21_11AM);
        ObjectNode assign = json.createObjectNode();
        assign.put("symbol", "IXQZ");
        assign.put("expirationDate", EXP);
        assign.put("strike", "11");
        assign.put("optionType", "CALL");
        assign.put("contracts", 1);
        assign.put("assignmentDate", EXP);
        send(post(TRADES + "/assignments"), assign, 201);
        rejected(post(STOCKS), stockBody("SELL", "IXQZ", 100, "1000.00", "2026-12-18T15:00"),
                "卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_putAssignmentAddsSharesForAWheel() throws Exception {
        newAccount("小账户");
        deposit("5000.00", "2026-11-02T10:00");
        open(optionBody("STO", "QRTV", "2026-11-20", "40", "PUT", 1, "80.00", "2026-11-03T10:00", null));
        ObjectNode call45 = optionBody("STO", "QRTV", EXP, "45", "CALL", 1, "60.00", "2026-11-10T10:00", null);
        rejected(post(TRADES), call45, "需要 100 股 QRTV，可用 0 股（持有 0 股）");

        clock.set(NOV_23_10AM);
        ObjectNode assign = json.createObjectNode();
        assign.put("symbol", "QRTV");
        assign.put("expirationDate", "2026-11-20");
        assign.put("strike", "40");
        assign.put("optionType", "PUT");
        assign.put("contracts", 1);
        assign.put("assignmentDate", "2026-11-20");
        long asg = send(post(TRADES + "/assignments"), assign, 201).get("id").asLong();
        call45.put("tradedAtLocal", "2026-11-20T15:00");
        rejected(post(TRADES), call45, "需要 100 股 QRTV，可用 0 股（持有 0 股）");
        call45.put("tradedAtLocal", "2026-11-23T09:45");
        open(call45);
        assertStock("QRTV", 100, 100, 0);
        rejected(delete(TRADES + "/" + asg), null,
                "删除后 2026-11-23 09:45 起 QRTV 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
    }

    // ---------------------------------------------------------------- AC 6 / 7

    @Test
    void ac6_legacyLotDoesNotBlockOtherOperations() throws Exception {
        long l1 = scenarioL();
        JsonNode row = optionRow("IXQZ 2026-12-18 10 C");
        assertThat(row.get("coveredShares").asLong()).isZero();
        assertThat(row.get("uncoveredContracts").asInt()).isEqualTo(2);
        assertThat(row.get("uncoveredShares").asLong()).isEqualTo(200);
        assertThat(row.get("availableShares").asLong()).isZero();
        assertThat(row.get("lots").get(0).get("coverage").asText()).isEqualTo("LEGACY");

        stock("BUY", "IXQZ", 100, "800.00", "2026-11-10T10:00");
        assertStock("IXQZ", 100, 0, 100);
        assertThat(optionRow("IXQZ 2026-12-18 10 C").get("uncoveredContracts").asInt()).isEqualTo(2);
        open(stoCall("12", 1, "20.00", "2026-11-10T11:00"));
        assertStock("IXQZ", 100, 100, 0);

        ObjectNode note = editOption(2, "90.00", "2026-11-03T10:00");
        note.put("note", "旧的");
        send(put(TRADES + "/" + l1), note, 200);
        assertThat(optionRow("IXQZ 2026-12-18 10 C").get("lots").get(0).get("coverage").asText())
                .isEqualTo("LEGACY");
        rejected(put(TRADES + "/" + l1), editOption(1, "90.00", "2026-11-03T10:00"),
                "需要 100 股 IXQZ，可用 0 股（持有 0 股）");
    }

    @Test
    void ac7_legacyLotIsIncludedOnceSharesCoverItFromItsOpening() throws Exception {
        scenarioL();
        stock("BUY", "IXQZ", 100, "800.00", "2026-11-10T10:00");
        open(stoCall("12", 1, "20.00", "2026-11-10T11:00"));

        long t1 = stock("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-04");
        JsonNode row = optionRow("IXQZ 2026-12-18 10 C");
        assertThat(row.get("uncoveredContracts").asInt()).isEqualTo(2);
        assertThat(row.get("uncoveredShortfallAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertThat(row.get("uncoveredShortfallHeldShares").asLong()).isZero();
        assertThat(row.get("lots").get(0).get("shortfallAtLocal").asText()).isEqualTo("2026-11-03T10:00:00");
        assertStock("IXQZ", 300, 100, 200);

        call(delete(STOCKS + "/" + t1), 204);
        long t2 = stock("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-01");
        row = optionRow("IXQZ 2026-12-18 10 C");
        assertThat(row.get("coveredShares").asLong()).isEqualTo(200);
        assertThat(row.get("uncoveredContracts").asInt()).isZero();
        assertThat(row.get("uncoveredShortfallAt").isNull()).isTrue();
        assertStock("IXQZ", 300, 300, 0);

        rejected(post(STOCKS), stockBody("SELL", "IXQZ", 100, "900.00", "2026-11-12T10:00"),
                "卖出 100 股超过可用股数 0 股（持有 300 股，其中 300 股被 call 覆盖），请先平掉对应的 call");
        rejected(delete(STOCKS + "/" + t2), null,
                "删除后 2026-11-03 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 0 股");
    }

    @Test
    void inclusionCausedByAnEditIsAnAuditSideEffect() throws Exception {
        long l1 = scenarioL();
        long t1 = stock("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-04");
        ObjectNode edit = json.createObjectNode();
        edit.put("shares", 200);
        edit.put("amount", "1500.00");
        edit.put("tradeDate", "2026-11-01");
        send(put(STOCKS + "/" + t1), edit, 200);
        JsonNode audit = call(get(URI.create("/api/audit-log?objectType=STOCK_TRADE&objectId=" + t1)), 200)
                .get("items").get(0);
        JsonNode effect = audit.get("sideEffects").get(0);
        assertThat(effect.get("field").asText()).isEqualTo("coverage");
        assertThat(effect.get("tradeId").asLong()).isEqualTo(l1);
        assertThat(effect.get("text").asText()).isEqualTo("STO #" + l1 + " 纳入覆盖检查");
        assertStock("IXQZ", 200, 200, 0);
    }

    @Test
    void editingALegacyLotsSizeIncludesItWhenCovered() throws Exception {
        long l1 = scenarioL();
        // shares arrive only after L1's opening: L1 stays LEGACY until it is moved after them
        stock("BUY", "IXQZ", 100, "800.00", "2026-11-05T10:00");
        assertThat(optionRow("IXQZ 2026-12-18 10 C").get("uncoveredContracts").asInt()).isEqualTo(2);
        send(put(TRADES + "/" + l1), editOption(1, "90.00", "2026-11-05T11:00"), 200);
        assertThat(optionRow("IXQZ 2026-12-18 10 C").get("lots").get(0).get("coverage").asText())
                .isEqualTo("CHECKED");
        JsonNode effect = call(get(URI.create("/api/audit-log?objectType=OPTION_TRADE&objectId=" + l1)), 200)
                .get("items").get(0).get("sideEffects").get(0);
        assertThat(effect.get("text").asText()).isEqualTo("STO #" + l1 + " 纳入覆盖检查");
        assertStock("IXQZ", 100, 100, 0);
    }

    @Test
    void startupInclusionCoversLegacyLotsThatHaveShares() throws Exception {
        deposit("2000.00", "2026-11-02T10:00");
        stock("TRANSFER_IN", "IXQZ", 200, "1500.00", "2026-11-01");
        long l1 = scenarioLLot();
        // simulate the migration: the lot is LEGACY again, then the startup pass runs
        jdbc.update("UPDATE option_trade SET coverage_status = 'LEGACY' WHERE id = ?", l1);
        coverage.includeLegacyEverywhere();
        assertThat(jdbc.queryForObject("SELECT coverage_status FROM option_trade WHERE id = ?", String.class, l1))
                .isEqualTo("CHECKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isZero();
    }

    private long scenarioLLot() {
        return optionTrades.open(Long.parseLong(account), new OptionTradeService.OpenCommand(
                new ContractKey("IXQZ", LocalDate.parse(EXP), new BigDecimal("10"), OptionType.CALL), TradeAction.STO,
                2, 100, new BigDecimal("90.00"), LocalDateTime.parse("2026-11-03T10:00"), null), true).trade().getId();
    }

    // ---------------------------------------------------------------- AC 8 / 9

    @Test
    void ac8_shortPutStillFreezesAndCallsShowCoverage() throws Exception {
        scenarioD();
        open(optionBody("STO", "IXQZ", EXP, "40", "PUT", 1, "70.00", "2026-11-04T11:00", null));
        JsonNode positions = call(get(URI.create("/api/option-positions")), 200);
        assertThat(positions.get("frozenTotal").asText()).isEqualTo("4000.00");
        assertThat(optionRow("IXQZ 2026-12-18 40 P").get("frozenCash").asText()).isEqualTo("4000.00");
        assertThat(optionRow("IXQZ 2026-12-18 40 P").get("coveredShares").isNull()).isTrue();
        assertThat(optionRow("IXQZ 2026-12-18 10 C").get("coveredShares").asLong()).isEqualTo(100);
        assertThat(optionRow("IXQZ 2026-12-18 11 C").get("coveredShares").asLong()).isEqualTo(100);
    }

    @Test
    void ac9_multiplierAndAccountIsolation() throws Exception {
        scenarioD();
        stock("BUY", "IXQZ", 10, "85.00", "2026-11-04T11:00");
        assertStock("IXQZ", 210, 200, 10);
        ObjectNode m10 = optionBody("STO", "IXQZ", EXP, "13", "CALL", 1, "5.00", "2026-11-04T12:00", 10);
        open(m10);
        assertThat(optionRow("IXQZ 2026-12-18 13 C").get("coveredShares").asLong()).isEqualTo(10);
        assertStock("IXQZ", 210, 210, 0);
        rejected(post(TRADES), m10, "需要 10 股 IXQZ，可用 0 股（持有 210 股，其中 210 股已覆盖其它 call）");

        newAccount("小账户");
        deposit("5000.00", "2026-11-02T10:00");
        rejected(post(TRADES), stoCall("10", 1, "50.00", "2026-11-05T10:00"),
                "需要 100 股 IXQZ，可用 0 股（持有 0 股）");
        account = "1";
        assertStock("IXQZ", 210, 210, 0);
    }

    // ---------------------------------------------------------------- edge cases

    @Test
    void sameInstantLaterEnteredBuyDoesNotCoverAnEarlierCall() throws Exception {
        deposit("10000.00", "2026-11-02T10:00");
        long t = stock("TRANSFER_IN", "IXQZ", 100, "800.00", "2026-11-02");
        long call = open(stoCall("10", 1, "50.00", "2026-11-03T10:00"));
        stock("BUY", "IXQZ", 100, "800.00", "2026-11-03T10:00");
        // without the transfer, the call is not covered at its own point (the buy was entered after it)
        rejected(delete(STOCKS + "/" + t), null,
                "删除后 2026-11-03 10:00 起 IXQZ 不足以覆盖卖出的 call：需要 100 股，持有 0 股");
        // a new call at the same instant comes after the buy
        open(stoCall("11", 1, "30.00", "2026-11-03T10:00"));
        assertThat(call).isPositive();
    }

    @Test
    void deletingAShortCallIsNeverRejectedForCoverage() throws Exception {
        scenarioD();
        call(delete(TRADES + "/" + c2), 204);
        assertStock("IXQZ", 200, 100, 100);
    }
}
