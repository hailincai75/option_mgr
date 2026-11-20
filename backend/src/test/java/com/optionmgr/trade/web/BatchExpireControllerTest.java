package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * FEAT-015 acceptance criteria (backend part), scenario S. Fictional tickers only. Most tests run at 2026-11-23 10:00
 * New York (Monday after the Friday 2026-11-20 expiration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(com.optionmgr.account.DefaultAccountMockMvc.class)
class BatchExpireControllerTest {

    static final Instant MON_10AM = Instant.parse("2026-11-23T15:00:00Z");

    static final class TestClock extends Clock {
        private volatile Instant now = MON_10AM;

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
        TestClock batchExpireTestClock() {
            return new TestClock();
        }
    }

    static final String TRADES = "/api/option-trades";
    static final String BATCH = "/api/option-trades/expirations/batch";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private String account = "1";

    @BeforeEach
    void setUp() {
        cleanUp();
        account = "1";
        clock.set(MON_10AM);
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

    private JsonNode send(MockHttpServletRequestBuilder req, Object body, int expectedStatus) throws Exception {
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

    private void transferInShares(String symbol, int shares, String amount, String date) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", date);
        send(post("/api/stock-trades"), n, 201);
    }

    private ObjectNode trade(String action, String symbol, String exp, String strike, String type, int contracts,
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

    private long open(String action, String symbol, String exp, String strike, String type, int contracts,
                      String net, String nyTime) throws Exception {
        return send(post(TRADES), trade(action, symbol, exp, strike, type, contracts, net, nyTime), 201)
                .get("id").asLong();
    }

    private ObjectNode item(String symbol, String exp, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    private ObjectNode batch(ObjectNode... items) {
        ObjectNode n = json.createObjectNode();
        ArrayNode a = n.putArray("items");
        for (ObjectNode i : items) {
            a.add(i);
        }
        return n;
    }

    private ObjectNode xyz45() {
        return item("XYZ", "2026-11-20", "45", "PUT", 2);
    }

    private ObjectNode xyz42(int contracts) {
        return item("XYZ", "2026-11-20", "42", "PUT", contracts);
    }

    private ObjectNode qrtv() {
        return item("QRTV", "2026-11-20", "30", "CALL", 1);
    }

    private ObjectNode ixqz() {
        return item("IXQZ", "2026-11-20", "9", "CALL", 1);
    }

    private ObjectNode xyz40() {
        return item("XYZ", "2026-11-27", "40", "PUT", 1);
    }

    /** Scenario S (default account). */
    private void scenarioS() throws Exception {
        clock.set(Instant.parse("2026-11-06T20:00:00Z"));
        deposit("50000.00", "2026-11-02T10:00");
        transferInShares("IXQZ", 100, "750.00", "2026-10-01");
        open("STO", "XYZ", "2026-11-20", "45", "PUT", 2, "240.00", "2026-11-03T10:00");
        open("STO", "XYZ", "2026-11-20", "42", "PUT", 1, "80.00", "2026-11-03T11:00");
        open("BTO", "QRTV", "2026-11-20", "30", "CALL", 1, "60.00", "2026-11-04T10:00");
        open("STO", "IXQZ", "2026-11-20", "9", "CALL", 1, "25.00", "2026-11-05T10:00");
        open("STO", "XYZ", "2026-11-27", "40", "PUT", 1, "70.00", "2026-11-06T10:00");
        clock.set(MON_10AM);
        assertSummary("50355.00", "17200.00", "33155.00");
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

    private List<String> expirableLabels() throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode p : getJson("/api/option-positions").get("positions")) {
            if (p.get("expirable").asBoolean()) {
                out.add(p.get("contractLabel").asText());
            }
        }
        return out;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    private JsonNode rejected(ObjectNode body) throws Exception {
        JsonNode err = send(post(BATCH), body, 422);
        assertThat(err.get("code").asText()).isEqualTo("BATCH_EXPIRATION_REJECTED");
        assertThat(err.get("status").asInt()).isEqualTo(422);
        assertThat(err.get("path").asText()).isEqualTo(BATCH);
        assertThat(err.get("details")).isEmpty();
        assertThat(err.get("message").asText())
                .isEqualTo("有 " + err.get("failures").size() + " 个持仓不能作废，整批未保存");
        return err;
    }

    private int expiryCount() throws Exception {
        return getJson(TRADES + "?action=EXP").get("totalItems").asInt();
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_expirableRowsAreTheExistingFlagInQ35Order() throws Exception {
        scenarioS();
        assertThat(expirableLabels()).containsExactly("IXQZ 2026-11-20 9 C", "QRTV 2026-11-20 30 C",
                "XYZ 2026-11-20 42 P", "XYZ 2026-11-20 45 P");
        JsonNode p = position("XYZ 2026-11-20 45 P");
        assertThat(p.get("expiryContracts").asInt()).isEqualTo(2);
        assertThat(p.get("openingNetTotal").asText()).isEqualTo("240.00");
        assertThat(p.get("frozenCash").asText()).isEqualTo("9000.00");
        assertThat(position("QRTV 2026-11-20 30 C").get("openingNetTotal").asText()).isEqualTo("-60.00");
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_expireAllInProcessingOrderRegardlessOfRequestOrder() throws Exception {
        scenarioS();
        JsonNode r = send(post(BATCH), batch(xyz45(), xyz42(1), qrtv(), ixqz()), 201);

        assertThat(r.get("count").asInt()).isEqualTo(4);
        assertThat(r.get("realizedPnlTotal").asText()).isEqualTo("285.00");
        assertThat(r.get("frozenChangeTotal").asText()).isEqualTo("-13200.00");
        JsonNode e = r.get("expirations");
        assertThat(texts(e, "contractLabel")).containsExactly("IXQZ 2026-11-20 9 C", "QRTV 2026-11-20 30 C",
                "XYZ 2026-11-20 42 P", "XYZ 2026-11-20 45 P");
        assertThat(texts(e, "realizedPnl")).containsExactly("25.00", "-60.00", "80.00", "240.00");
        assertThat(texts(e, "frozenChange")).containsExactly("0.00", "0.00", "-4200.00", "-9000.00");
        assertThat(texts(e, "expiredDirection")).containsExactly("SHORT", "LONG", "SHORT", "SHORT");
        assertThat(texts(e, "action")).containsOnly("EXP");
        assertThat(texts(e, "tradedAtLocal")).containsOnly("2026-11-20T16:00:00");
        assertThat(texts(e, "netAmount")).containsOnly("0.00");
        assertThat(e.get(0).get("ledgerEntryId").isNull()).isTrue();
        assertThat(e.get(2).get("ledgerEntryId").isNull()).isFalse();
        assertThat(e.get(3).get("coveredLots").get(0).get("contracts").asInt()).isEqualTo(2);
        long previous = 0;
        for (JsonNode x : e) {
            assertThat(x.get("id").asLong()).isGreaterThan(previous);
            previous = x.get("id").asLong();
        }

        // every record is a normal single expiry
        JsonNode single = getJson(TRADES + "/" + e.get(3).get("id").asLong());
        assertThat(single.toString()).isEqualTo(e.get(3).toString());

        assertThat(expirableLabels()).isEmpty();
        assertSummary("50355.00", "4000.00", "46355.00");
        assertThat(expiryCount()).isEqualTo(4);
        JsonNode closed = getJson("/api/closed-trades?action=EXP");
        assertThat(closed.get("filteredCount").asInt()).isEqualTo(4);
        assertThat(closed.get("filteredRealizedPnlTotal").asText()).isEqualTo("285.00");
        JsonNode ixqzStock = null;
        for (JsonNode s : getJson("/api/stock-positions").get("positions")) {
            if (s.get("symbol").asText().equals("IXQZ")) {
                ixqzStock = s;
            }
        }
        assertThat(ixqzStock.get("coveredShares").asLong()).isZero();
        assertThat(ixqzStock.get("availableShares").asLong()).isEqualTo(100);
        // no audit for the batch itself
        assertThat(getJson("/api/audit-log?size=100").get("totalItems").asInt()).isZero();
    }

    // ---------------------------------------------------------------- AC 3

    @Test
    void ac3_uncheckedRowStaysAndCanBeHandledAlone() throws Exception {
        scenarioS();
        JsonNode r = send(post(BATCH), batch(ixqz(), qrtv(), xyz42(1)), 201);
        assertThat(r.get("count").asInt()).isEqualTo(3);
        assertThat(r.get("realizedPnlTotal").asText()).isEqualTo("45.00");
        assertThat(r.get("frozenChangeTotal").asText()).isEqualTo("-4200.00");
        assertThat(expirableLabels()).containsExactly("XYZ 2026-11-20 45 P");
    }

    // ---------------------------------------------------------------- AC 4

    @Test
    void ac4_oneChangedPositionRejectsTheWholeBatch() throws Exception {
        scenarioS();
        open("STO", "XYZ", "2026-11-20", "42", "PUT", 1, "50.00", "2026-11-19T10:00");

        JsonNode err = rejected(batch(ixqz(), qrtv(), xyz42(1), xyz45()));
        JsonNode f = err.get("failures");
        assertThat(f).hasSize(1);
        assertThat(f.get(0).get("index").asInt()).isEqualTo(2);
        assertThat(f.get(0).get("symbol").asText()).isEqualTo("XYZ");
        assertThat(f.get(0).get("expirationDate").asText()).isEqualTo("2026-11-20");
        assertThat(f.get(0).get("strike").asText()).isEqualTo("42");
        assertThat(f.get(0).get("optionType").asText()).isEqualTo("PUT");
        assertThat(f.get(0).get("contractLabel").asText()).isEqualTo("XYZ 2026-11-20 42 P");
        assertThat(f.get(0).get("code").asText()).isEqualTo("POSITION_CHANGED");
        assertThat(f.get(0).get("message").asText()).isEqualTo("持仓已变化（当前 2 张），请刷新后重试");
        assertThat(expiryCount()).isZero();
        assertSummary("50405.00", "21400.00", "29005.00");

        assertThat(position("XYZ 2026-11-20 42 P").get("openingNetTotal").asText()).isEqualTo("130.00");
        JsonNode r = send(post(BATCH), batch(ixqz(), qrtv(), xyz42(2), xyz45()), 201);
        assertThat(r.get("count").asInt()).isEqualTo(4);
        assertThat(r.get("realizedPnlTotal").asText()).isEqualTo("335.00");
        assertThat(r.get("frozenChangeTotal").asText()).isEqualTo("-17400.00");
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void ac5_everyFailureIsReturnedInProcessingOrderWithTheSingleExpireMessage() throws Exception {
        scenarioS();
        // a close stored before FEAT-012 at 16:10 on the expiration date (written around the validation)
        long id = send(post(TRADES + "/closes"),
                trade("STC", "QRTV", "2026-11-20", "30", "CALL", 1, "20.00", "2026-11-20T15:50"), 201)
                .get("id").asLong();
        OffsetDateTime late = OffsetDateTime.parse("2026-11-20T21:10:00Z");
        jdbc.update("UPDATE option_trade SET traded_at = ? WHERE id = ?", late, id);
        jdbc.update("UPDATE cash_ledger_entry SET occurred_at = ? WHERE source_type = 'OPTION_CLOSE' AND source_id = ?",
                late, id);

        JsonNode err = rejected(batch(xyz45(), xyz42(2), qrtv(), ixqz()));
        JsonNode f = err.get("failures");
        assertThat(texts(f, "contractLabel")).containsExactly("QRTV 2026-11-20 30 C", "XYZ 2026-11-20 42 P");
        assertThat(texts(f, "index")).containsExactly("2", "1");
        assertThat(texts(f, "code")).containsExactly("EXPIRY_BLOCKED_BY_LATE_TRADE", "POSITION_CHANGED");
        assertThat(f.get(0).get("message").asText()).isEqualTo("2026-11-20 16:10 的 STC 1 张晚于到期日美东 16:00，"
                + "与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它");
        assertThat(f.get(1).get("message").asText()).isEqualTo("持仓已变化（当前 1 张），请刷新后重试");
        assertThat(expiryCount()).isZero();
        assertThat(position("IXQZ 2026-11-20 9 C")).isNotNull();
        assertThat(position("XYZ 2026-11-20 45 P")).isNotNull();
    }

    @Test
    void ac5_emptyOrDuplicateRequestsAreRejectedAndNothingIsSaved() throws Exception {
        scenarioS();
        JsonNode err = send(post(BATCH), batch(), 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").toString())
                .isEqualTo("[{\"field\":\"items\",\"message\":\"请至少选择一个持仓\"}]");
        err = send(post(BATCH), json.createObjectNode(), 400);
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("请至少选择一个持仓");

        // same contract after normalization: lower-case symbol, 45.00 == 45
        err = send(post(BATCH), batch(xyz45(), qrtv(), item("xyz", "2026-11-20", "45.00", "PUT", 2)), 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("details").toString())
                .isEqualTo("[{\"field\":\"items[2]\",\"message\":\"同一合约（XYZ 2026-11-20 45 P）不能出现两次\"}]");
        assertThat(expiryCount()).isZero();
    }

    @Test
    void fieldErrorsCarryTheItemIndexAndAreReportedTogether() throws Exception {
        scenarioS();
        ObjectNode bad = item("XYZ", "2026-11-20", "-1", "PUT", 2);
        bad.remove("contracts");
        ObjectNode extra = ixqz();
        extra.put("note", "ignored");
        extra.put("netAmount", "99.00");
        JsonNode err = send(post(BATCH), batch(extra, bad, qrtv(), qrtv()), 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(err.get("details").toString()).isEqualTo("[{\"field\":\"items[1].contracts\",\"message\":\"张数不能为空\"},"
                + "{\"field\":\"items[1].strike\",\"message\":\"行权价必须大于 0\"},"
                + "{\"field\":\"items[3]\",\"message\":\"同一合约（QRTV 2026-11-20 30 C）不能出现两次\"}]");

        ObjectNode tooMany = json.createObjectNode();
        ArrayNode a = tooMany.putArray("items");
        for (int i = 0; i < 501; i++) {
            a.add(item("XYZ", "2026-11-20", String.valueOf(i + 1), "PUT", 1));
        }
        err = send(post(BATCH), tooMany, 400);
        assertThat(err.get("details").get(0).get("message").asText()).isEqualTo("一次最多作废 500 个持仓");

        err = send(post(BATCH), batch(item("XYZ", "2026-11-20", "45", "PIT", 2)), 400);
        assertThat(err.get("code").asText()).isEqualTo("BAD_REQUEST");

        // ignored properties: the expiry is still made by the system
        JsonNode r = send(post(BATCH), batch(extra), 201);
        assertThat(r.get("expirations").get(0).get("note").isNull()).isTrue();
        assertThat(r.get("expirations").get(0).get("netAmount").asText()).isEqualTo("0.00");
    }

    // ---------------------------------------------------------------- AC 6

    @Test
    void ac6_expirationDayBeforeAndAfter1730() throws Exception {
        scenarioS();
        clock.set(Instant.parse("2026-11-20T21:30:00Z")); // 16:30
        assertThat(expirableLabels()).isEmpty();
        JsonNode err = rejected(batch(xyz45(), xyz42(1), qrtv(), ixqz()));
        assertThat(err.get("failures")).hasSize(4);
        assertThat(texts(err.get("failures"), "code")).containsOnly("EXPIRY_TOO_EARLY");
        assertThat(texts(err.get("failures"), "message")).containsOnly("到期日（2026-11-20）美东 17:30 起才能作废");
        assertThat(expiryCount()).isZero();

        clock.set(Instant.parse("2026-11-20T22:45:00Z")); // 17:45
        assertThat(expirableLabels()).hasSize(4);
        JsonNode r = send(post(BATCH), batch(xyz45(), xyz42(1), qrtv(), ixqz()), 201);
        assertThat(r.get("realizedPnlTotal").asText()).isEqualTo("285.00");
        assertThat(r.get("frozenChangeTotal").asText()).isEqualTo("-13200.00");
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void ac7_differentExpirationDatesTogether() throws Exception {
        scenarioS();
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        JsonNode r = send(post(BATCH), batch(xyz40(), xyz45(), xyz42(1), qrtv(), ixqz()), 201);
        assertThat(r.get("count").asInt()).isEqualTo(5);
        assertThat(r.get("realizedPnlTotal").asText()).isEqualTo("355.00");
        assertThat(r.get("frozenChangeTotal").asText()).isEqualTo("-17200.00");
        assertThat(texts(r.get("expirations"), "contractLabel")).containsExactly("IXQZ 2026-11-20 9 C",
                "QRTV 2026-11-20 30 C", "XYZ 2026-11-20 42 P", "XYZ 2026-11-20 45 P", "XYZ 2026-11-27 40 P");
        assertThat(texts(r.get("expirations"), "tradedAtLocal")).containsExactly("2026-11-20T16:00:00",
                "2026-11-20T16:00:00", "2026-11-20T16:00:00", "2026-11-20T16:00:00", "2026-11-27T16:00:00");
        assertSummary("50355.00", "0.00", "50355.00");
        assertThat(getJson("/api/pnl-summary").get("byMonth").toString()).contains("2026-11");
    }

    // ---------------------------------------------------------------- AC 8

    @Test
    void ac8_deleteOneExpiryLikeASingleOne() throws Exception {
        scenarioS();
        JsonNode r = send(post(BATCH), batch(xyz45(), xyz42(1), qrtv(), ixqz()), 201);
        long id = r.get("expirations").get(2).get("id").asLong();
        call(delete(TRADES + "/" + id), 204);
        assertSummary("50355.00", "8200.00", "42155.00");
        assertThat(expiryCount()).isEqualTo(3);
        assertThat(expirableLabels()).containsExactly("XYZ 2026-11-20 42 P");
        JsonNode audit = getJson("/api/audit-log?size=100");
        assertThat(audit.get("totalItems").asInt()).isEqualTo(1);
        assertThat(audit.get("items").get(0).get("summary").asText()).isEqualTo("到期作废 XYZ 2026-11-20 42 P 1 张");
    }

    // ---------------------------------------------------------------- AC 9

    @Test
    void ac9_accountsAreIsolated() throws Exception {
        scenarioS();
        ObjectNode n = json.createObjectNode();
        n.put("name", "小账户");
        String small = String.valueOf(send(post("/api/accounts"), n, 201).get("id").asLong());
        account = small;
        clock.set(Instant.parse("2026-11-06T20:00:00Z"));
        deposit("5000.00", "2026-11-02T10:00");
        open("STO", "XYZ", "2026-11-20", "45", "PUT", 1, "100.00", "2026-11-04T10:00");
        clock.set(MON_10AM);

        account = "1";
        send(post(BATCH), batch(xyz45(), xyz42(1), qrtv(), ixqz()), 201);

        account = small;
        assertThat(expirableLabels()).containsExactly("XYZ 2026-11-20 45 P");
        assertSummary("5100.00", "4500.00", "600.00");

        account = "1";
        JsonNode err = rejected(batch(item("XYZ", "2026-11-20", "45", "PUT", 1)));
        assertThat(err.get("failures").get(0).get("code").asText()).isEqualTo("NO_POSITION_TO_EXPIRE");
        assertThat(err.get("failures").get(0).get("message").asText())
                .isEqualTo("该合约（XYZ 2026-11-20 45 P）没有持仓，无法作废");
    }
}
