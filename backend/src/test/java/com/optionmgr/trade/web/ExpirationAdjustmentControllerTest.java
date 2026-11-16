package com.optionmgr.trade.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.calendar.FakeFinnhub;
import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.HolidayFetchService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionTradeService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-043 acceptance criteria (backend part) through the real HTTP API and database. Scenario of the requirement:
 * today 2026-12-01 10:00 New York, 2026-12-04 detected as an ad-hoc closure; fictional QRTV / IXQZ; account A = 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpirationAdjustmentControllerTest {

    static final Instant NOV30 = Instant.parse("2026-11-30T15:00:00Z");
    /** 2026-12-01 10:00 New York. */
    static final Instant DEC1 = Instant.parse("2026-12-01T15:00:00Z");
    static final String URL = "/api/option-trades/expiration-adjustments";
    static final String P1 = "QRTV 2026-12-04 50 P";
    static final String P1_NEW = "QRTV 2026-12-03 50 P";
    static final String P2 = "IXQZ 2026-12-04 9 C";
    static final String P3 = "QRTV 2026-12-18 45 P";

    static final class MovableClock extends Clock {
        private volatile Instant now = NOV30;

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
        MovableClock expirationAdjustmentTestClock() {
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

    @Autowired
    HolidayFetchService fetches;

    @Autowired
    OptionTradeService trades;

    /** ids of P1's records (STO, STO, BTC) and of P2. */
    long[] p1Ids;
    long p2Id;

    @BeforeEach
    void setUp() {
        clean();
        clock.set(NOV30);
    }

    @AfterEach
    void tearDown() {
        clean();
        clock.set(NOV30);
    }

    void clean() {
        jdbc.update("DELETE FROM expiration_adjustment");
        jdbc.update("DELETE FROM audit_record_object");
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM cash_account WHERE id <> 1");
        jdbc.update("DELETE FROM account WHERE id <> 1");
        jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 2");
        jdbc.update("DELETE FROM market_holiday");
        jdbc.update("DELETE FROM adhoc_closure");
        jdbc.update("UPDATE market_holiday_fetch_status SET last_attempt_at = NULL, last_attempt_result = NULL, "
                + "last_failure_detail = NULL, last_success_at = NULL");
    }

    // ---------------------------------------------------------------- data

    static List<String> baseEntries() {
        List<String> e = new ArrayList<>();
        e.add(FakeFinnhub.entry("Christmas", "2024-12-25", ""));
        e.add(FakeFinnhub.entry("National Mourning Day", "2025-01-09", ""));
        e.add(FakeFinnhub.entry("Juneteenth", "2025-06-19", ""));
        e.add(FakeFinnhub.entry("Thanksgiving Day", "2026-11-26", ""));
        e.add(FakeFinnhub.entry("Thanksgiving Day", "2026-11-27", "09:30-13:00"));
        e.add(FakeFinnhub.entry("Christmas", "2026-12-25", ""));
        return e;
    }

    private void fetch(Instant at, List<String> entries) {
        clock.set(at);
        assertThat(fetches.process(new FetchAttempt.Response(200, FakeFinnhub.response(entries.toArray(String[]::new))))
                .lastAttemptResult()).hasToString("SUCCESS");
    }

    /** 2025-01-09 recorded on 11-30, 2026-12-04 on 12-01; the clock stays at 2026-12-01 10:00. */
    private void detect() {
        fetch(NOV30, baseEntries());
        List<String> e = baseEntries();
        e.add(FakeFinnhub.entry("National Day of Mourning", "2026-12-04", ""));
        fetch(DEC1, e);
    }

    private record Res(int status, JsonNode body, String raw) {
    }

    private Res call(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Res(r.getResponse().getStatus(), raw.isEmpty() ? null : json.readTree(raw), raw);
    }

    private JsonNode ok(long account, String url) throws Exception {
        Res r = call(get(url).header(AccountRef.HEADER, String.valueOf(account)));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r.body();
    }

    private Res postJson(long account, String url, ObjectNode body) throws Exception {
        return call(post(url).header(AccountRef.HEADER, String.valueOf(account)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private long newAccount(String name) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("name", name);
        Res r = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(n)));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private void deposit(long account, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        assertThat(postJson(account, "/api/cash/transfers", n).status()).isEqualTo(201);
    }

    private long trade(long account, String action, String symbol, String exp, String strike, String type,
                       int contracts, String net, String at) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", at);
        String url = action.endsWith("O") ? "/api/option-trades" : "/api/option-trades/closes";
        Res r = postJson(account, url, n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** Account A: P1 (3 records, short 1), P2 (BTO 2), P3; then both closures detected. */
    private void scenario() throws Exception {
        clock.set(DEC1);
        deposit(1, "20000.00", "2026-11-19T10:00");
        p1Ids = new long[] {
                trade(1, "STO", "QRTV", "2026-12-04", "50", "PUT", 1, "120.00", "2026-11-20T10:00"),
                trade(1, "STO", "QRTV", "2026-12-04", "50", "PUT", 1, "100.00", "2026-11-24T10:00"),
                trade(1, "BTC", "QRTV", "2026-12-04", "50", "PUT", 1, "40.00", "2026-11-25T10:00")};
        p2Id = trade(1, "BTO", "IXQZ", "2026-12-04", "9", "CALL", 2, "60.00", "2026-11-25T11:00");
        trade(1, "STO", "QRTV", "2026-12-18", "45", "PUT", 1, "150.00", "2026-11-25T12:00");
        detect();
    }

    private ObjectNode adjustment(String symbol, String exp, String strike, String type, String newDate) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("newExpirationDate", newDate);
        return n;
    }

    private ObjectNode p1To(String newDate) {
        return adjustment("QRTV", "2026-12-04", "50", "PUT", newDate);
    }

    private JsonNode position(long account, String label) throws Exception {
        for (JsonNode p : ok(account, "/api/option-positions").get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    private void assertRejected(Res r, int status, String code, String message) {
        assertThat(r.status()).as(r.raw()).isEqualTo(status);
        assertThat(r.body().get("code").asText()).isEqualTo(code);
        if (message != null) {
            assertThat(r.body().get("message").asText()).isEqualTo(message);
        }
        assertThat(r.body().get("path").asText()).isEqualTo(URL);
    }

    private void assertNothingAdjusted() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expiration_adjustment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade WHERE has_audit_log", Long.class)).isZero();
    }

    // ---------------------------------------------------------------- AC

    @Test
    void ac1_ac2_onlyClosureRowsAreAdjustable_withDialogData() throws Exception {
        scenario();
        JsonNode p1 = position(1, P1);
        assertThat(p1.get("expirationAdjustable").asBoolean()).isTrue();
        assertThat(p1.get("expirationAdjustmentRecordCount").asInt()).isEqualTo(3);
        assertThat(p1.get("defaultNewExpirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(p1.get("newExpirationDateMin").asText()).isEqualTo("2026-11-25");
        assertThat(p1.get("newExpirationDateMax").asText()).isEqualTo("2026-12-03");
        assertThat(p1.get("adhocClosureEventName").asText()).isEqualTo("National Day of Mourning");
        JsonNode p2 = position(1, P2);
        assertThat(p2.get("expirationAdjustable").asBoolean()).isTrue();
        assertThat(p2.get("expirationAdjustmentRecordCount").asInt()).isEqualTo(1);
        JsonNode p3 = position(1, P3);
        assertThat(p3.get("expirationAdjustable").asBoolean()).isFalse();
        assertThat(p3.get("expirationAdjustmentRecordCount").asInt()).isZero();
        assertThat(p3.get("defaultNewExpirationDate").isNull()).isTrue();
        assertThat(p3.get("newExpirationDateMin").isNull()).isTrue();
        assertThat(p3.get("newExpirationDateMax").isNull()).isTrue();
    }

    @Test
    void ac3_adjustP1_newDateDteAndCashUnchanged() throws Exception {
        scenario();
        JsonNode cashBefore = ok(1, "/api/cash/summary");
        ObjectNode body = p1To("2026-12-03");
        body.put("note", "  交易所改为 12-03 到期 ");
        Res r = postJson(1, URL, body);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("symbol").asText()).isEqualTo("QRTV");
        assertThat(b.get("strike").asText()).isEqualTo("50");
        assertThat(b.get("optionType").asText()).isEqualTo("PUT");
        assertThat(b.get("oldExpirationDate").asText()).isEqualTo("2026-12-04");
        assertThat(b.get("newExpirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(b.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(b.get("tradeIds").toString()).isEqualTo(p1IdsJson());
        assertThat(b.get("auditId").asLong()).isPositive();
        JsonNode pos = b.get("position");
        assertThat(pos.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(pos.get("dte").asInt()).isEqualTo(2);
        assertThat(pos.get("remainingTradingDays").asInt()).isEqualTo(2);
        assertThat(pos.get("frozenCash").asText()).isEqualTo("5000.00");
        assertThat(pos.get("expiresOnAdhocClosure").asBoolean()).isFalse();
        assertThat(pos.get("expirationAdjustable").asBoolean()).isFalse();
        // FEAT-048: the position's lots carry the per-lot valuation fields, status / label = the row's
        for (JsonNode lot : pos.get("lots")) {
            assertThat(lot.get("unrealizedStatus").asText()).isEqualTo(pos.get("unrealizedStatus").asText());
            assertThat(lot.get("unrealizedLabel")).isEqualTo(pos.get("unrealizedLabel"));
            assertThat(lot.has("marketValue")).isTrue();
            assertThat(lot.has("unrealizedPnl")).isTrue();
            assertThat(lot.has("unrealizedPnlPercent")).isTrue();
        }

        assertThat(position(1, P1)).isNull();
        JsonNode p1 = position(1, P1_NEW);
        assertThat(p1.get("dte").asInt()).isEqualTo(2);
        assertThat(p1.get("expiresOnAdhocClosure").asBoolean()).isFalse();
        assertThat(ok(1, "/api/cash/summary")).isEqualTo(cashBefore);
        JsonNode alerts = ok(1, "/api/adhoc-closure-alerts").get("alerts");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(alerts.get(0).get("message").asText()).contains("你有 1 个持仓在该日到期");
    }

    @Test
    void ac4_tradesClosedTradesAuditAndLedgerShowTheNewContract() throws Exception {
        scenario();
        assertThat(postJson(1, URL, p1To("2026-12-03")).status()).isEqualTo(200);

        for (JsonNode t : ok(1, "/api/option-trades?symbol=QRTV&size=100").get("items")) {
            if (t.get("strike").asText().equals("50")) {
                assertThat(t.get("contractLabel").asText()).isEqualTo(P1_NEW);
                assertThat(t.get("hasAuditLog").asBoolean()).isTrue();
            } else {
                assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
            }
        }
        JsonNode closed = ok(1, "/api/closed-trades").get("items");
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(closed.get(0).get("realizedPnl").asText()).isEqualTo("80.00");
        assertThat(closed.get(0).get("hasAuditLog").asBoolean()).isTrue();

        JsonNode audit = ok(1, "/api/audit-log").get("items");
        assertThat(audit).hasSize(1);
        JsonNode a = audit.get(0);
        assertThat(a.get("operation").asText()).isEqualTo("ADJUST_EXPIRATION");
        assertThat(a.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(a.get("objectId").asLong()).isEqualTo(p1Ids[0]);
        assertThat(a.get("summary").asText()).isEqualTo("调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）");
        assertThat(a.get("before").get("contractLabel").asText()).isEqualTo(P1);
        assertThat(a.get("after").get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(a.get("after").get("tradeIds").toString()).isEqualTo(p1IdsJson());
        assertThat(a.get("changes")).hasSize(1);
        assertThat(a.get("changes").get(0).get("text").asText()).isEqualTo("到期日：2026-12-04 → 2026-12-03");
        assertThat(a.get("sideEffects")).isEmpty();
        JsonNode adj = a.get("expirationAdjustment");
        assertThat(adj.get("oldExpirationDate").asText()).isEqualTo("2026-12-04");
        assertThat(adj.get("newExpirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(adj.get("adhocClosureEventName").asText()).isEqualTo("National Day of Mourning");
        assertThat(adj.get("tradeIds").toString()).isEqualTo(p1IdsJson());
        assertThat(adj.get("note").isNull()).isTrue();
        // every changed record's "历史" finds it; other trades do not
        for (long id : p1Ids) {
            assertThat(ok(1, "/api/audit-log?objectType=OPTION_TRADE&objectId=" + id).get("items"))
                    .as("trade " + id).hasSize(1);
        }
        assertThat(ok(1, "/api/audit-log?objectType=OPTION_TRADE&objectId=" + p2Id).get("items")).isEmpty();
        assertThat(ok(1, "/api/audit-log?objectType=CASH_TRANSFER&objectId=" + p1Ids[1]).get("items")).isEmpty();

        boolean found = false;
        for (JsonNode e : ok(1, "/api/cash/ledger?size=100").get("items")) {
            assertThat(e.get("description").asText()).doesNotContain(P1);
            found |= e.get("description").asText().contains(P1_NEW);
        }
        assertThat(found).isTrue();
    }

    @Test
    void ac5_adjustingP2TooRemovesTheAlert() throws Exception {
        scenario();
        assertThat(postJson(1, URL, p1To("2026-12-03")).status()).isEqualTo(200);
        Res r = postJson(1, URL, adjustment("IXQZ", "2026-12-04", "9", "CALL", "2026-12-03"));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(ok(1, "/api/adhoc-closure-alerts").get("alerts")).isEmpty();
    }

    @Test
    void ac6_contractNotOnAClosureIsRejected() throws Exception {
        scenario();
        assertRejected(postJson(1, URL, adjustment("QRTV", "2026-12-18", "45", "PUT", "2026-12-17")), 422,
                "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");
        assertNothingAdjusted();
        assertThat(position(1, P3)).isNotNull();
    }

    @Test
    void ac7_newDateNotATradingDay() throws Exception {
        scenario();
        assertRejected(postJson(1, URL, p1To("2026-12-05")), 422, "NEW_EXPIRATION_NOT_TRADING_DAY",
                "新到期日 2026-12-05 不是美股交易日");
        assertNothingAdjusted();
    }

    @Test
    void ac8_expiredContractCannotBeAdjusted() throws Exception {
        long c = newAccount("账户C");
        clock.set(DEC1);
        deposit(c, "10000.00", "2024-12-02T10:00");
        legacyShortPut(c, "QRTV", "2025-01-09", "40");
        detect();
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "QRTV");
        exp.put("expirationDate", "2025-01-09");
        exp.put("strike", "40");
        exp.put("optionType", "PUT");
        exp.put("contracts", 1);
        assertThat(postJson(c, "/api/option-trades/expirations", exp).status()).isEqualTo(201);
        assertThat(ok(c, "/api/option-positions").get("positions")).isEmpty();
        assertRejected(postJson(c, URL, adjustment("QRTV", "2025-01-09", "40", "PUT", "2025-01-08")), 422,
                "CONTRACT_ALREADY_SETTLED", "合约已作废 / 被指派 / 行权，不能调整到期日");
    }

    @Test
    void ac9_expiredUnprocessedPositionIsAdjustedThenExpiresOnTheNewDate() throws Exception {
        long d = newAccount("账户D");
        clock.set(DEC1);
        deposit(d, "10000.00", "2024-12-02T10:00");
        legacyShortPut(d, "IXQZ", "2025-01-09", "8");
        detect();
        JsonNode before = position(d, "IXQZ 2025-01-09 8 P");
        assertThat(before.get("expirationAdjustable").asBoolean()).isTrue();
        assertThat(before.get("defaultNewExpirationDate").asText()).isEqualTo("2025-01-08");
        Res r = postJson(d, URL, adjustment("IXQZ", "2025-01-09", "8", "PUT", "2025-01-08"));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        JsonNode p = position(d, "IXQZ 2025-01-08 8 P");
        assertThat(p.get("daysPastExpiration").asInt()).isEqualTo(692);
        assertThat(p.get("expirable").asBoolean()).isTrue();
        assertThat(p.get("expiryAtLocal").asText()).isEqualTo("2025-01-08T16:00:00");
        ObjectNode exp = json.createObjectNode();
        exp.put("symbol", "IXQZ");
        exp.put("expirationDate", "2025-01-08");
        exp.put("strike", "8");
        exp.put("optionType", "PUT");
        exp.put("contracts", 1);
        Res e = postJson(d, "/api/option-trades/expirations", exp);
        assertThat(e.status()).as(e.raw()).isEqualTo(201);
        assertThat(e.body().get("tradedAt").asText()).isEqualTo("2025-01-08T21:00:00Z");
    }

    // ---------------------------------------------------------------- further checks of the contract

    @Test
    void unknownContractIs404_andFieldErrorsAre400() throws Exception {
        scenario();
        assertRejected(postJson(1, URL, adjustment("QRTV", "2026-12-04", "55", "PUT", "2026-12-03")), 404,
                "NOT_FOUND", "该账户没有合约 QRTV 2026-12-04 55 P 的记录");
        ObjectNode missing = p1To("2026-12-03");
        missing.remove("newExpirationDate");
        missing.put("note", "x".repeat(501));
        Res r = postJson(1, URL, missing);
        assertRejected(r, 400, "VALIDATION_ERROR", null);
        assertThat(r.body().get("details").toString()).contains("新到期日不能为空").contains("备注最多 500 个字符");
        assertNothingAdjusted();
    }

    @Test
    void otherAccountsAreNotTouched() throws Exception {
        scenario();
        long b = newAccount("账户B");
        assertRejected(postJson(b, URL, p1To("2026-12-03")), 404, "NOT_FOUND", null);
        assertThat(postJson(1, URL, p1To("2026-12-03")).status()).isEqualTo(200);
        assertThat(ok(b, "/api/audit-log?objectType=OPTION_TRADE").get("items")).isEmpty();
    }

    @Test
    void newDateOutOfRangeAndExistingTarget() throws Exception {
        scenario();
        assertRejected(postJson(1, URL, p1To("2026-12-07")), 422, "NEW_EXPIRATION_OUT_OF_RANGE",
                "新到期日必须早于当前到期日 2026-12-04");
        assertRejected(postJson(1, URL, p1To("2026-11-24")), 422, "NEW_EXPIRATION_OUT_OF_RANGE",
                "新到期日不能早于该合约最后一笔记录的成交日期 2026-11-25");
        // Q132: a contract on the new date already exists
        trade(1, "STO", "QRTV", "2026-12-02", "50", "PUT", 1, "30.00", "2026-11-25T13:00");
        assertRejected(postJson(1, URL, p1To("2026-12-02")), 422, "TARGET_CONTRACT_EXISTS",
                "该账户已有合约 QRTV 2026-12-02 50 P 的记录，不能合并");
        assertNothingAdjusted();
    }

    @Test
    void anAdjustedContractCannotBeAdjustedAgain_evenIfTheNewDateBecomesAClosure() throws Exception {
        scenario();
        assertThat(postJson(1, URL, p1To("2026-12-03")).status()).isEqualTo(200);
        // ordinary case: the new date is a trading day, not a closure
        assertRejected(postJson(1, URL, adjustment("QRTV", "2026-12-03", "50", "PUT", "2026-12-02")), 422,
                "EXPIRATION_NOT_ADHOC_CLOSURE", null);
        // the new date is later detected as an ad-hoc closure as well
        List<String> e = baseEntries();
        e.add(FakeFinnhub.entry("National Day of Mourning", "2026-12-04", ""));
        e.add(FakeFinnhub.entry("Second Closure", "2026-12-03", ""));
        fetch(DEC1, e);
        JsonNode p = position(1, P1_NEW);
        assertThat(p.get("expiresOnAdhocClosure").asBoolean()).isTrue();
        assertThat(p.get("expirationAdjustable").asBoolean()).isFalse();
        assertRejected(postJson(1, URL, adjustment("QRTV", "2026-12-03", "50", "PUT", "2026-12-02")), 422,
                "EXPIRATION_ALREADY_ADJUSTED", "该合约已调整过到期日，不能再次调整");
    }

    @Test
    void normalEditAuditStillFilteredByObject() throws Exception {
        scenario();
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "125.00");
        edit.put("tradedAtLocal", "2026-11-20T10:00");
        edit.putNull("note");
        Res r = call(put("/api/option-trades/" + p1Ids[0]).header(AccountRef.HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(postJson(1, URL, p1To("2026-12-03")).status()).isEqualTo(200);
        JsonNode one = ok(1, "/api/audit-log?objectType=OPTION_TRADE&objectId=" + p1Ids[0]).get("items");
        assertThat(one).hasSize(2);
        assertThat(one.get(1).get("operation").asText()).isEqualTo("UPDATE");
        assertThat(one.get(1).get("expirationAdjustment").isNull()).isTrue();
        JsonNode two = ok(1, "/api/audit-log?objectType=OPTION_TRADE&objectId=" + p1Ids[1]).get("items");
        assertThat(two).hasSize(1);
        assertThat(ok(1, "/api/audit-log?objectType=OPTION_TRADE&objectId=" + p1Ids[1] + "&size=1").get("totalItems").asInt())
                .isEqualTo(1);
    }

    private String p1IdsJson() {
        return "[" + p1Ids[0] + "," + p1Ids[1] + "," + p1Ids[2] + "]";
    }

    private void legacyShortPut(long account, String symbol, String exp, String strike) {
        trades.open(account, new OptionTradeService.OpenCommand(
                new ContractKey(symbol, LocalDate.parse(exp), new BigDecimal(strike), OptionType.PUT),
                TradeAction.STO, 1, 100, new BigDecimal("50.00"), LocalDateTime.parse("2024-12-20T10:00"), null), true);
    }
}
