package com.optionmgr.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.calendar.FakeFinnhub;
import com.optionmgr.calendar.service.HolidayFetchService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionTradeService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-009 acceptance criteria (backend part) through the real HTTP API and database, with a local fake Finnhub
 * and a temporary .env holding a fake key. Scenario "today": Monday 2026-11-23 10:00 New York.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TradingCalendarControllerTest {

    static final Instant SCENARIO_NOW = Instant.parse("2026-11-23T15:00:00Z");
    static final String FAKE_KEY = "test-key-123";

    static final FakeFinnhub FINNHUB = new FakeFinnhub();
    static final Path ENV_DIR;

    static {
        try {
            ENV_DIR = Files.createTempDirectory("feat009-env");
            Files.writeString(ENV_DIR.resolve(".env"), "FINNHUB_API_KEY=" + FAKE_KEY + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void finnhub(DynamicPropertyRegistry r) {
        r.add("optionmgr.finnhub.base-url", FINNHUB::baseUrl);
        r.add("optionmgr.finnhub.env-files", () -> ENV_DIR.resolve(".env").toString());
    }

    @AfterAll
    static void stopFake() throws IOException {
        FINNHUB.close();
        Files.deleteIfExists(ENV_DIR.resolve(".env"));
        Files.deleteIfExists(ENV_DIR);
    }

    static final class MovableClock extends Clock {
        private volatile Instant now = SCENARIO_NOW;

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
        MovableClock calendarTestClock() {
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
        clock.set(SCENARIO_NOW);
        FINNHUB.clearReceived();
    }

    @AfterEach
    void tearDown() {
        clean();
        clock.set(SCENARIO_NOW);
    }

    void clean() {
        jdbc.update("DELETE FROM option_trade");
        jdbc.update("DELETE FROM cash_ledger_entry");
        jdbc.update("DELETE FROM cash_transfer");
        jdbc.update("DELETE FROM market_holiday");
        jdbc.update("UPDATE market_holiday_fetch_status SET last_attempt_at = NULL, last_attempt_result = NULL, "
                + "last_failure_detail = NULL, last_success_at = NULL");
    }

    // ---------------------------------------------------------------- helpers

    private void fetchScenario() {
        FINNHUB.respond(200, FakeFinnhub.scenario());
        assertThat(fetches.fetchNow().lastAttemptResult()).hasToString("SUCCESS");
    }

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

    private MockHttpServletRequestBuilder account(MockHttpServletRequestBuilder b) {
        return b.header(AccountRef.HEADER, "1");
    }

    private Res postJson(String url, ObjectNode body) throws Exception {
        return call(account(post(url)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private void deposit(String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        assertThat(postJson("/api/cash/transfers", n).status()).isEqualTo(201);
    }

    private ObjectNode trade(String action, String symbol, String exp, String strike, String type, String net,
                             String nyTime) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", 1);
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        return n;
    }

    /** Account A of the scenario: P1 QRTV 2026-11-27 50P, P2 QRTV 2026-12-18 45P; available 10,770.00. */
    private void scenarioAccount() throws Exception {
        deposit("20000.00", "2026-11-19T10:00");
        assertThat(postJson("/api/option-trades",
                trade("STO", "QRTV", "2026-11-27", "50", "PUT", "120.00", "2026-11-20T10:00")).status()).isEqualTo(201);
        assertThat(postJson("/api/option-trades",
                trade("STO", "QRTV", "2026-12-18", "45", "PUT", "150.00", "2026-11-20T10:00")).status()).isEqualTo(201);
        assertThat(availableCash()).isEqualTo("10770.00");
    }

    private String availableCash() throws Exception {
        return ok(account(get("/api/cash/summary"))).get("availableCash").asText();
    }

    private JsonNode position(String exp) throws Exception {
        for (JsonNode p : ok(account(get("/api/option-positions"))).get("positions")) {
            if (p.get("expirationDate").asText().equals(exp)) {
                return p;
            }
        }
        throw new AssertionError("no position expiring " + exp);
    }

    private List<String> nonTradingDays(String from, String to) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode d : ok(get("/api/trading-calendar/non-trading-days").param("from", from).param("to", to))
                .get("days")) {
            out.add(d.get("date").asText() + " " + d.get("type").asText() + " " + d.get("name").asText() + " "
                    + d.get("source").asText());
        }
        return out;
    }

    private JsonNode status() throws Exception {
        return ok(get("/api/trading-calendar/status"));
    }

    // ---------------------------------------------------------------- acceptance criteria

    @Test
    void ac1_remainingTradingDaysAndNoWarning() throws Exception {
        fetchScenario();
        scenarioAccount();
        JsonNode p1 = position("2026-11-27");
        assertThat(p1.get("dte").asInt()).isEqualTo(4);
        assertThat(p1.get("remainingTradingDays").asInt()).isEqualTo(3);
        JsonNode p2 = position("2026-12-18");
        assertThat(p2.get("dte").asInt()).isEqualTo(25);
        assertThat(p2.get("remainingTradingDays").asInt()).isEqualTo(18);
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("warning").isNull()).isTrue();
    }

    @Test
    void ac2_expirationDayAndExpired() throws Exception {
        fetchScenario();
        scenarioAccount();
        clock.set(Instant.parse("2026-11-27T15:00:00Z"));
        JsonNode p1 = position("2026-11-27");
        assertThat(p1.get("dte").asInt()).isZero();
        assertThat(p1.get("remainingTradingDays").asInt()).isZero();
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        p1 = position("2026-11-27");
        assertThat(p1.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(p1.get("daysPastExpiration").asInt()).isEqualTo(3);
        assertThat(p1.has("remainingTradingDays")).isTrue();
        assertThat(p1.get("remainingTradingDays").isNull()).isTrue();
    }

    @Test
    void ac3_pickerMonthsWithoutAccountHeader() throws Exception {
        fetchScenario();
        assertThat(nonTradingDays("2026-11-01", "2026-11-30")).containsExactly(
                "2026-11-01 WEEKEND 星期日 null",
                "2026-11-07 WEEKEND 星期六 null", "2026-11-08 WEEKEND 星期日 null",
                "2026-11-14 WEEKEND 星期六 null", "2026-11-15 WEEKEND 星期日 null",
                "2026-11-21 WEEKEND 星期六 null", "2026-11-22 WEEKEND 星期日 null",
                "2026-11-26 HOLIDAY Thanksgiving Day FINNHUB",
                "2026-11-28 WEEKEND 星期六 null", "2026-11-29 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2027-03-01", "2027-03-31"))
                .contains("2027-03-26 HOLIDAY Good Friday FINNHUB")
                .noneMatch(s -> s.startsWith("2027-03-25"));
    }

    @Test
    void ac4_nationalMourningDayFromFinnhub() throws Exception {
        fetchScenario();
        JsonNode r = ok(get("/api/trading-calendar/non-trading-days").param("from", "2025-01-06").param("to", "2025-01-12"));
        assertThat(r.get("from").asText()).isEqualTo("2025-01-06");
        assertThat(r.get("to").asText()).isEqualTo("2025-01-12");
        assertThat(r.get("days").get(1).get("source").isNull()).isTrue();
        assertThat(nonTradingDays("2025-01-06", "2025-01-12")).containsExactly(
                "2025-01-09 HOLIDAY National Mourning Day FINNHUB",
                "2025-01-11 WEEKEND 星期六 null", "2025-01-12 WEEKEND 星期日 null");
    }

    @Test
    void ac5_rulesYearThenFinnhubOnceReturned() throws Exception {
        fetchScenario();
        assertThat(nonTradingDays("2028-04-10", "2028-04-16")).containsExactly(
                "2028-04-14 HOLIDAY Good Friday RULES",
                "2028-04-15 WEEKEND 星期六 null", "2028-04-16 WEEKEND 星期日 null");
        assertThat(status().get("coveredYears").toString()).isEqualTo("[2023,2024,2025,2026,2027]");

        FINNHUB.respond(200, FakeFinnhub.response(FakeFinnhub.entry("Good Friday", "2028-04-14", "")));
        fetches.fetchNow();
        assertThat(nonTradingDays("2028-04-10", "2028-04-16")).containsExactly(
                "2028-04-14 HOLIDAY Good Friday FINNHUB",
                "2028-04-15 WEEKEND 星期六 null", "2028-04-16 WEEKEND 星期日 null");
        // Q127: years not in the new response are kept
        assertThat(status().get("coveredYears").toString()).isEqualTo("[2023,2024,2025,2026,2027,2028]");
        assertThat(nonTradingDays("2025-01-09", "2025-01-09")).containsExactly(
                "2025-01-09 HOLIDAY National Mourning Day FINNHUB");
    }

    @Test
    void ac6_openingTradeExpiringOnAHolidayIsRejectedAndNothingSaved() throws Exception {
        fetchScenario();
        scenarioAccount();
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class);
        Res r = postJson("/api/option-trades",
                trade("BTO", "IXQZ", "2027-03-26", "30", "CALL", "80.00", "2026-11-23T09:50"));
        assertThat(r.status()).as(r.raw()).isEqualTo(422);
        assertThat(r.body().get("code").asText()).isEqualTo("EXPIRATION_NOT_TRADING_DAY");
        assertThat(r.body().get("message").asText()).isEqualTo("到期日 2027-03-26 不是美股交易日（Good Friday）");
        assertThat(r.body().get("details")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class)).isEqualTo(before);
        assertThat(availableCash()).isEqualTo("10770.00");

        // weekend: same code, reason 星期六
        Res sat = postJson("/api/option-trades",
                trade("STO", "IXQZ", "2026-11-28", "30", "PUT", "80.00", "2026-11-23T09:50"));
        assertThat(sat.status()).isEqualTo(422);
        assertThat(sat.body().get("message").asText()).isEqualTo("到期日 2026-11-28 不是美股交易日（星期六）");
        // an early close is a trading day
        assertThat(postJson("/api/option-trades",
                trade("BTO", "IXQZ", "2026-11-27", "30", "CALL", "80.00", "2026-11-23T09:50")).status()).isEqualTo(201);
    }

    @Test
    void fieldErrorsComeBeforeTheTradingDayCheck() throws Exception {
        fetchScenario();
        ObjectNode b = trade("BTO", "IXQZ", "2027-03-26", "30", "CALL", "80.00", "2026-11-24T09:50"); // future time
        Res r = postJson("/api/option-trades", b);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void ac7_noKeyNeverSucceededRulesStillApply() throws Exception {
        assertThat(status().get("stale").asBoolean()).isFalse(); // never attempted
        fetches.process(new com.optionmgr.calendar.service.FetchAttempt.NoKey());
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("lastAttemptResult").asText()).isEqualTo("NO_KEY");
        assertThat(s.get("warning").asText()).isEqualTo("休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）");
        assertThat(s.get("lastSuccessAt").isNull()).isTrue();
        assertThat(s.get("lastSuccessAtLocal").isNull()).isTrue();
        assertThat(s.get("coveredYears")).isEmpty();
        assertThat(nonTradingDays("2026-11-26", "2026-11-26")).containsExactly("2026-11-26 HOLIDAY Thanksgiving Day RULES");
        // entering and positions still work
        scenarioAccount();
        assertThat(position("2026-11-27").get("remainingTradingDays").asInt()).isEqualTo(3);
    }

    @Test
    void ac8_failureKeepsStoredDataAndShowsLastSuccessUntilTheNextSuccess() throws Exception {
        clock.set(Instant.parse("2026-11-20T13:00:00Z")); // 08:00 New York
        fetchScenario();
        clock.set(Instant.parse("2026-11-21T13:00:00Z"));
        FINNHUB.respond(500, "{\"error\":\"boom\"}");
        fetches.fetchNow();
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(s.get("lastAttemptResult").asText()).isEqualTo("HTTP_ERROR");
        assertThat(s.get("lastFailureDetail").asText()).isEqualTo("HTTP 500");
        assertThat(s.get("lastAttemptAt").asText()).isEqualTo("2026-11-21T13:00:00Z");
        assertThat(s.get("lastSuccessAt").asText()).isEqualTo("2026-11-20T13:00:00Z");
        assertThat(s.get("lastSuccessAtLocal").asText()).isEqualTo("2026-11-20T08:00:00");
        assertThat(s.get("warning").asText()).isEqualTo("休市日数据未更新（上次成功：2026-11-20 08:00 美东）");
        assertThat(nonTradingDays("2025-01-09", "2025-01-09")).containsExactly(
                "2025-01-09 HOLIDAY National Mourning Day FINNHUB");

        // invalid body: also a failure, data kept
        FINNHUB.respond(200, "<html>maintenance</html>");
        fetches.fetchNow();
        assertThat(status().get("lastAttemptResult").asText()).isEqualTo("INVALID_RESPONSE");
        assertThat(status().get("coveredYears").toString()).isEqualTo("[2023,2024,2025,2026,2027]");

        clock.set(Instant.parse("2026-11-22T13:00:00Z"));
        fetchScenario();
        s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("warning").isNull()).isTrue();
        assertThat(s.get("lastFailureDetail").isNull()).isTrue();
    }

    @Test
    void ac9_keyOnlyInTheHeaderAndNowhereElse(CapturedOutput output) throws Exception {
        fetchScenario();
        assertThat(FINNHUB.received()).singleElement().satisfies(r -> {
            assertThat(r.token()).isEqualTo(FAKE_KEY);
            assertThat(r.uri()).doesNotContain(FAKE_KEY);
        });
        FINNHUB.respond(500, "x");
        fetches.fetchNow();
        scenarioAccount();
        for (String url : List.of("/api/trading-calendar/status", "/api/option-positions", "/api/option-trades")) {
            assertThat(call(account(get(url))).raw()).doesNotContain(FAKE_KEY);
        }
        assertThat(call(get("/api/trading-calendar/non-trading-days").param("from", "2026-11-01")
                .param("to", "2026-11-30")).raw()).doesNotContain(FAKE_KEY);
        assertThat(output.getAll()).contains("Market holiday fetch failed").doesNotContain(FAKE_KEY);
        assertThat(jdbc.queryForList("SELECT * FROM market_holiday_fetch_status").toString()).doesNotContain(FAKE_KEY);
    }

    @Test
    void ac10_storedTradeExpiringOnASaturdayIsCountedAndCanBeClosed() throws Exception {
        fetchScenario();
        clock.set(Instant.parse("2026-11-16T15:00:00Z"));
        deposit("5000.00", "2026-11-10T10:00");
        // stored before FEAT-009 (no trading-day check), as the e2e legacy test support does
        trades.open(1, new OptionTradeService.OpenCommand(
                new ContractKey("IXQZ", LocalDate.of(2026, 11, 21), new BigDecimal("9"), OptionType.PUT),
                TradeAction.STO, 1, 100, new BigDecimal("30.00"), LocalDateTime.parse("2026-11-12T10:00"), null), true);
        JsonNode p = position("2026-11-21");
        assertThat(p.get("dte").asInt()).isEqualTo(5);
        assertThat(p.get("remainingTradingDays").asInt()).isEqualTo(4);
        Res close = postJson("/api/option-trades/closes",
                trade("BTC", "IXQZ", "2026-11-21", "9", "PUT", "10.00", "2026-11-16T09:55"));
        assertThat(close.status()).as(close.raw()).isEqualTo(201);
    }

    @Test
    void q127_aYearInTheNewResponseIsReplacedWhole() throws Exception {
        fetchScenario();
        // 2026 comes back without Thanksgiving: 11-26 becomes a trading day; 2025 not returned -> kept
        FINNHUB.respond(200, FakeFinnhub.response(FakeFinnhub.entry("Christmas", "2026-12-25", "")));
        fetches.fetchNow();
        assertThat(nonTradingDays("2026-11-26", "2026-11-26")).isEmpty();
        assertThat(nonTradingDays("2026-12-25", "2026-12-25")).containsExactly("2026-12-25 HOLIDAY Christmas FINNHUB");
        assertThat(nonTradingDays("2025-01-09", "2025-01-09")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_holiday WHERE YEAR(holiday_date) = 2026",
                Long.class)).isEqualTo(1);
    }

    @Test
    void testProfileNeverFetchesOnItsOwn(@Autowired org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeansOfType(com.optionmgr.calendar.service.HolidayFetchScheduler.class)).isEmpty();
        assertThat(FINNHUB.received()).isEmpty();
    }

    // ---------------------------------------------------------------- parameter errors

    @Test
    void parameterErrors() throws Exception {
        Res missing = call(get("/api/trading-calendar/non-trading-days").param("to", "2026-11-30"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(missing.body().get("details").toString())
                .isEqualTo("[{\"field\":\"from\",\"message\":\"请填写日期\"}]");

        Res bad = call(get("/api/trading-calendar/non-trading-days").param("from", "2026-02-30").param("to", "2026/11/01"));
        assertThat(bad.body().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(bad.body().get("details").toString()).isEqualTo(
                "[{\"field\":\"from\",\"message\":\"日期格式不正确（YYYY-MM-DD）\"},"
                        + "{\"field\":\"to\",\"message\":\"日期格式不正确（YYYY-MM-DD）\"}]");

        Res reversed = call(get("/api/trading-calendar/non-trading-days").param("from", "2026-12-01").param("to", "2026-11-30"));
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(reversed.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(reversed.body().get("details").toString())
                .isEqualTo("[{\"field\":\"to\",\"message\":\"开始日期不能晚于结束日期\"}]");

        // 366 days apart is allowed, 367 is not
        assertThat(call(get("/api/trading-calendar/non-trading-days").param("from", "2027-01-01")
                .param("to", "2028-01-02")).status()).isEqualTo(200);
        Res tooLong = call(get("/api/trading-calendar/non-trading-days").param("from", "2027-01-01").param("to", "2028-01-03"));
        assertThat(tooLong.body().get("details").toString())
                .isEqualTo("[{\"field\":\"to\",\"message\":\"日期范围最多 366 天\"}]");
    }
}
