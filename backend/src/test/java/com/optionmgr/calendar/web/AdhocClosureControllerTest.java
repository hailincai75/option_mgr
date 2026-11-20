package com.optionmgr.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * FEAT-042 acceptance criteria (backend part) through the real HTTP API and database. Fetches are injected with
 * {@link HolidayFetchService#process} (the same processing as a real fetch; the e2e test support uses it too), so
 * nothing reaches Finnhub. Scenario: fictional QRTV / IXQZ; account A = default account 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdhocClosureControllerTest {

    static final Instant NOV30 = Instant.parse("2026-11-30T15:00:00Z");
    static final Instant DEC1 = Instant.parse("2026-12-01T13:00:00Z");
    static final Instant DEC2 = Instant.parse("2026-12-02T13:00:00Z");
    static final String MSG_TAIL = " 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日";

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
        MovableClock adhocClosureTestClock() {
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
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM option_close_lot");
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

    // ---------------------------------------------------------------- Finnhub data

    static List<String> baseEntries() {
        List<String> e = new ArrayList<>();
        e.add(FakeFinnhub.entry("New Year's Day", "2023-01-02", ""));
        e.add(FakeFinnhub.entry("Christmas", "2024-12-25", ""));
        e.add(FakeFinnhub.entry("National Mourning Day", "2025-01-09", ""));
        e.add(FakeFinnhub.entry("Juneteenth", "2025-06-19", ""));
        e.add(FakeFinnhub.entry("Thanksgiving Day", "2026-11-26", ""));
        e.add(FakeFinnhub.entry("Thanksgiving Day", "2026-11-27", "09:30-13:00"));
        e.add(FakeFinnhub.entry("Christmas", "2026-12-25", ""));
        e.add(FakeFinnhub.entry("Good Friday", "2027-03-26", ""));
        return e;
    }

    static String nov30Data() {
        return FakeFinnhub.response(baseEntries().toArray(String[]::new));
    }

    static String dec1Data() {
        List<String> e = baseEntries();
        e.add(FakeFinnhub.entry("National Day of Mourning", "2026-12-04", ""));
        return FakeFinnhub.response(e.toArray(String[]::new));
    }

    private void fetch(Instant at, String body) {
        clock.set(at);
        assertThat(fetches.process(new FetchAttempt.Response(200, body)).lastAttemptResult()).hasToString("SUCCESS");
    }

    /** AC 1 + AC 2 fetches (positions must be opened before 2026-12-04 becomes a non-trading day). */
    private void detectBoth() {
        fetch(NOV30, nov30Data());
        fetch(DEC1, dec1Data());
    }

    // ---------------------------------------------------------------- HTTP helpers

    private record Res(int status, JsonNode body, String raw) {
    }

    private Res call(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Res(r.getResponse().getStatus(), raw.isEmpty() ? null : json.readTree(raw), raw);
    }

    private JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        Res r = call(req);
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

    private void deposit(long account, String amount) throws Exception {
        deposit(account, amount, "2026-11-20T10:00");
    }

    private void deposit(long account, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        assertThat(postJson(account, "/api/cash/transfers", n).status()).isEqualTo(201);
    }

    private void legacyShortPut(long account, String symbol, String exp, String strike) {
        trades.open(account, new OptionTradeService.OpenCommand(
                new ContractKey(symbol, LocalDate.parse(exp), new BigDecimal(strike), OptionType.PUT),
                TradeAction.STO, 1, 100, new BigDecimal("50.00"), LocalDateTime.parse("2025-01-02T10:00"), null), true);
    }

    private void trade(long account, String action, String symbol, String exp, String strike, String type,
                       int contracts, String net) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", net);
        n.put("tradedAtLocal", action.endsWith("O") ? "2026-11-25T10:00" : "2026-12-01T10:00");
        String url = action.endsWith("O") ? "/api/option-trades" : "/api/option-trades/closes";
        Res r = postJson(account, url, n);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    /** Account A (id 1): P1 STO 1 QRTV 2026-12-04 50P, P2 BTO 2 IXQZ 2026-12-04 9C, P3 STO 1 QRTV 2026-12-18 45P. */
    private void accountA() throws Exception {
        deposit(1, "20000.00");
        trade(1, "STO", "QRTV", "2026-12-04", "50", "PUT", 1, "120.00");
        trade(1, "BTO", "IXQZ", "2026-12-04", "9", "CALL", 2, "80.00");
        trade(1, "STO", "QRTV", "2026-12-18", "45", "PUT", 1, "150.00");
    }

    private JsonNode alerts(long account) throws Exception {
        return ok(get("/api/adhoc-closure-alerts").header(AccountRef.HEADER, String.valueOf(account))).get("alerts");
    }

    private JsonNode positions(long account) throws Exception {
        return ok(get("/api/option-positions").header(AccountRef.HEADER, String.valueOf(account))).get("positions");
    }

    private JsonNode position(long account, String label) throws Exception {
        for (JsonNode p : positions(account)) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label);
    }

    private JsonNode closures(String query) throws Exception {
        return ok(get("/api/trading-calendar/adhoc-closures" + query)).get("closures");
    }

    // ---------------------------------------------------------------- AC

    @Test
    void ac1_firstFetchRecordsOnlyTheMourningDay() throws Exception {
        // Q134: 2025-01-09 is a one-time day in Jollyday, not a recurring holiday -> ad-hoc
        fetch(NOV30, nov30Data());
        JsonNode c = closures("");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("date").asText()).isEqualTo("2025-01-09");
        assertThat(c.get(0).get("eventName").asText()).isEqualTo("National Mourning Day");
        assertThat(c.get(0).get("detectedOn").asText()).isEqualTo("2026-11-30");
        assertThat(c.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get(0).get("revokedOn").isNull()).isTrue();
    }

    @Test
    void ac1_aClosureTheRulesDoNotKnowAtAllIsRecordedOnTheFirstFetch() throws Exception {
        fetch(NOV30, FakeFinnhub.response(FakeFinnhub.entry("Fictional Closure", "2025-03-12", ""),
                FakeFinnhub.entry("Thanksgiving Day", "2026-11-26", ""),
                FakeFinnhub.entry("Thanksgiving Day", "2026-11-27", "09:30-13:00")));
        JsonNode c = closures("");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("date").asText()).isEqualTo("2025-03-12");
        assertThat(c.get(0).get("eventName").asText()).isEqualTo("Fictional Closure");
        assertThat(c.get(0).get("detectedOn").asText()).isEqualTo("2026-11-30");
        assertThat(c.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get(0).get("revokedOn").isNull()).isTrue();
    }

    @Test
    void ac2_newClosureDetectedOnce_detectionDateKept() throws Exception {
        detectBoth();
        fetch(DEC2, dec1Data());
        JsonNode c = closures("");
        assertThat(c).hasSize(2);
        assertThat(c.get(0).get("detectedOn").asText()).isEqualTo("2026-11-30");
        assertThat(c.get(1).get("date").asText()).isEqualTo("2026-12-04");
        assertThat(c.get(1).get("eventName").asText()).isEqualTo("National Day of Mourning");
        assertThat(c.get(1).get("detectedOn").asText()).isEqualTo("2026-12-01");
        assertThat(closures("?status=ACTIVE")).hasSize(2);
        assertThat(closures("?status=REVOKED")).isEmpty();
    }

    @Test
    void ac3_alertAndTagsForAccountA() throws Exception {
        accountA();
        detectBoth();
        JsonNode a = alerts(1);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("date").asText()).isEqualTo("2026-12-04");
        assertThat(a.get(0).get("eventName").asText()).isEqualTo("National Day of Mourning");
        assertThat(a.get(0).get("affectedPositionCount").asInt()).isEqualTo(2);
        assertThat(a.get(0).get("message").asText())
                .isEqualTo("2026-12-04 临时休市（National Day of Mourning），你有 2" + MSG_TAIL);
        for (String label : List.of("QRTV 2026-12-04 50 P", "IXQZ 2026-12-04 9 C")) {
            JsonNode p = position(1, label);
            assertThat(p.get("expiresOnAdhocClosure").asBoolean()).as(label).isTrue();
            assertThat(p.get("adhocClosureEventName").asText()).isEqualTo("National Day of Mourning");
        }
        JsonNode p3 = position(1, "QRTV 2026-12-18 45 P");
        assertThat(p3.get("expiresOnAdhocClosure").asBoolean()).isFalse();
        assertThat(p3.get("adhocClosureEventName").isNull()).isTrue();
    }

    @Test
    void ac4_otherAccountHasNoAlert() throws Exception {
        accountA();
        long b = newAccount("账户B");
        deposit(b, "10000.00");
        trade(b, "STO", "QRTV", "2026-12-18", "45", "PUT", 1, "150.00");
        detectBoth();
        assertThat(alerts(b)).isEmpty();
        assertThat(position(b, "QRTV 2026-12-18 45 P").get("expiresOnAdhocClosure").asBoolean()).isFalse();
    }

    @Test
    void ac5_alertCountDropsAndDisappearsWhenPositionsAreClosed() throws Exception {
        accountA();
        detectBoth();
        clock.set(Instant.parse("2026-12-01T16:00:00Z"));
        trade(1, "STC", "IXQZ", "2026-12-04", "9", "CALL", 2, "60.00");
        JsonNode a = alerts(1);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(a.get(0).get("message").asText()).contains("你有 1 个持仓");
        trade(1, "BTC", "QRTV", "2026-12-04", "50", "PUT", 1, "40.00");
        assertThat(alerts(1)).isEmpty();
    }

    @Test
    void ac6_twoLinesByDateIncludingALegacyPosition() throws Exception {
        long c = newAccount("账户C");
        deposit(c, "20000.00", "2024-12-02T10:00");
        // stored before FEAT-009 (no trading-day check), as the e2e legacy test support does
        legacyShortPut(c, "QRTV", "2025-01-09", "40");
        trade(c, "STO", "IXQZ", "2026-12-04", "8", "PUT", 1, "30.00");
        detectBoth();
        JsonNode a = alerts(c);
        assertThat(a).hasSize(2);
        assertThat(a.get(0).get("date").asText()).isEqualTo("2025-01-09");
        assertThat(a.get(0).get("affectedPositionCount").asInt()).isEqualTo(1);
        assertThat(a.get(0).get("message").asText())
                .isEqualTo("2025-01-09 临时休市（National Mourning Day），你有 1" + MSG_TAIL);
        assertThat(a.get(1).get("date").asText()).isEqualTo("2026-12-04");
        assertThat(a.get(1).get("affectedPositionCount").asInt()).isEqualTo(1);
    }

    @Test
    void ac7_dteRemainingTradingDaysAndExpiryUnchanged() throws Exception {
        accountA();
        fetch(NOV30, nov30Data());
        clock.set(DEC1);
        JsonNode before = position(1, "QRTV 2026-12-04 50 P");
        fetch(DEC1, dec1Data());
        JsonNode after = position(1, "QRTV 2026-12-04 50 P");
        assertThat(after.get("dte")).isEqualTo(before.get("dte"));
        assertThat(after.get("expirationStatus")).isEqualTo(before.get("expirationStatus"));
        assertThat(after.get("expiryAt")).isEqualTo(before.get("expiryAt"));
        assertThat(after.get("expirable")).isEqualTo(before.get("expirable"));
        assertThat(after.get("expirationDate").asText()).isEqualTo("2026-12-04");
        // FEAT-009: 12-04 itself is now a non-trading day, (12-01, 12-04] = 12-02, 12-03
        assertThat(after.get("remainingTradingDays").asInt()).isEqualTo(2);
    }

    @Test
    void ac8_removedByALaterFetch_revokedAndAlertGone() throws Exception {
        accountA();
        detectBoth();
        fetch(DEC2, nov30Data());
        JsonNode c = closures("?status=REVOKED");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("date").asText()).isEqualTo("2026-12-04");
        assertThat(c.get(0).get("detectedOn").asText()).isEqualTo("2026-12-01");
        assertThat(c.get(0).get("revokedOn").asText()).isEqualTo("2026-12-02");
        assertThat(alerts(1)).isEmpty();
        assertThat(position(1, "QRTV 2026-12-04 50 P").get("expiresOnAdhocClosure").asBoolean()).isFalse();
        JsonNode days = ok(get("/api/trading-calendar/non-trading-days?from=2026-12-01&to=2026-12-06")).get("days");
        assertThat(days.findValuesAsText("date")).doesNotContain("2026-12-04");
    }

    @Test
    void ac9_failedFetchChangesNothing() throws Exception {
        accountA();
        detectBoth();
        clock.set(DEC2);
        assertThat(fetches.process(new FetchAttempt.Response(500, null)).lastAttemptResult()).hasToString("HTTP_ERROR");
        assertThat(fetches.process(new FetchAttempt.Response(200, "not json")).lastAttemptResult())
                .hasToString("INVALID_RESPONSE");
        assertThat(closures("?status=ACTIVE")).hasSize(2);
        assertThat(alerts(1).get(0).get("affectedPositionCount").asInt()).isEqualTo(2);
        assertThat(ok(get("/api/trading-calendar/status")).get("stale").asBoolean()).isTrue();
    }

    @Test
    void errors() throws Exception {
        Res noAccount = call(get("/api/adhoc-closure-alerts"));
        assertThat(noAccount.status()).isEqualTo(400);
        assertThat(noAccount.body().get("code").asText()).isEqualTo("ACCOUNT_REQUIRED");
        Res missing = call(get("/api/adhoc-closure-alerts").header(AccountRef.HEADER, "99"));
        assertThat(missing.status()).isEqualTo(404);
        Res badStatus = call(get("/api/trading-calendar/adhoc-closures?status=active"));
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(badStatus.body().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(badStatus.body().get("details").get(0).get("field").asText()).isEqualTo("status");
        // closures list needs no account header
        assertThat(closures("")).isEmpty();
    }
}
