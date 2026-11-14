package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * QA system tests for FEAT-009 美股交易日历, end to end over REST on a real HTTP port (random port, never 8080).
 *
 * <p>How Finnhub is faked (contract docs/api/FEAT-009.md「测试支持」): the class runs with profiles {@code test}
 * + {@code e2e}. Under {@code e2e} the backend never reads {@code FINNHUB_API_KEY} and never calls the real Finnhub
 * (startup and daily fetch are off); every "fetch" in these tests is {@code POST /api/test-support/trading-calendar/
 * fetch} with a fake Finnhub response, processed exactly like a real one. "Now" is fixed with
 * {@code PUT /api/test-support/clock}; {@code POST /api/test-support/reset} (before and after every test) empties the
 * database, the holiday data and the fetch status and restores the system clock. In-memory H2 {@code e2e} only.
 *
 * <p>Scenario (requirement): today 2026-11-23 (Mon) 10:00 New York; the fake Finnhub returns 2023–2027 (incl.
 * 2025-01-09 National Mourning Day, 2026-11-26 Thanksgiving Day, 2026-11-27 09:30-13:00, 2027-03-26 Good Friday).
 * Account A (default account 1): STO 1 QRTV 2026-11-27 50P net 120.00 (P1), STO 1 QRTV 2026-12-18 45P net 150.00
 * (P2), both at 2026-11-20 10:00; available cash 10,770.00. All tickers are fictional.
 *
 * <p>Not covered here (need a non-e2e startup; see the test report): AC 7's "gradlew bootRun without .env starts
 * normally" and AC 9 (key only in the X-Finnhub-Token header, never in URL / log / responses).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@DisplayName("FEAT-009 美股交易日历（系统测试：假 Finnhub + 假时钟）")
class Feat009TradingCalendarSystemTest {

    static final String NTD = "/api/trading-calendar/non-trading-days";
    static final String STATUS = "/api/trading-calendar/status";
    static final String FETCH = "/api/test-support/trading-calendar/fetch";
    static final String CLOCK = "/api/test-support/clock";
    static final String RESET = "/api/test-support/reset";
    static final String LEGACY = "/api/test-support/legacy-option-trades";
    static final String TRADES = "/api/option-trades";
    static final String POSITIONS = "/api/option-positions";

    static final String P1 = "QRTV 2026-11-27 50 P";
    static final String P2 = "QRTV 2026-12-18 45 P";

    static final String NEVER_SUCCEEDED = "休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）";

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** X-Account-Id of the business calls; null = omit. */
    String accountId = "1";

    @BeforeEach
    void resetBefore() {
        accountId = "1";
        reset();
    }

    @AfterEach
    void resetAfter() {
        reset();
    }

    // =================================================================================================== fake Finnhub

    /** One Finnhub {@code data[]} row; {@code hours} "" = full-day closure, "09:30-13:00" = early close. */
    record H(String name, String date, String hours) {
    }

    static H closed(String name, String date) {
        return new H(name, date, "");
    }

    static H early(String name, String date) {
        return new H(name, date, "09:30-13:00");
    }

    /** What the fake Finnhub returns per year (NYSE calendar as published). */
    static final Map<Integer, List<H>> FINNHUB = new LinkedHashMap<>();

    static {
        FINNHUB.put(2023, List.of(
                closed("New Year's Day", "2023-01-02"), closed("Martin Luther King Jr. Day", "2023-01-16"),
                closed("Washington's Birthday", "2023-02-20"), closed("Good Friday", "2023-04-07"),
                closed("Memorial Day", "2023-05-29"), closed("Juneteenth", "2023-06-19"),
                early("Independence Day", "2023-07-03"), closed("Independence Day", "2023-07-04"),
                closed("Labor Day", "2023-09-04"), closed("Thanksgiving Day", "2023-11-23"),
                early("Thanksgiving Day", "2023-11-24"), closed("Christmas", "2023-12-25")));
        FINNHUB.put(2024, List.of(
                closed("New Year's Day", "2024-01-01"), closed("Martin Luther King Jr. Day", "2024-01-15"),
                closed("Washington's Birthday", "2024-02-19"), closed("Good Friday", "2024-03-29"),
                closed("Memorial Day", "2024-05-27"), closed("Juneteenth", "2024-06-19"),
                early("Independence Day", "2024-07-03"), closed("Independence Day", "2024-07-04"),
                closed("Labor Day", "2024-09-02"), closed("Thanksgiving Day", "2024-11-28"),
                early("Thanksgiving Day", "2024-11-29"), early("Christmas", "2024-12-24"),
                closed("Christmas", "2024-12-25")));
        FINNHUB.put(2025, List.of(
                closed("New Year's Day", "2025-01-01"), closed("National Mourning Day", "2025-01-09"),
                closed("Martin Luther King Jr. Day", "2025-01-20"), closed("Washington's Birthday", "2025-02-17"),
                closed("Good Friday", "2025-04-18"), closed("Memorial Day", "2025-05-26"),
                closed("Juneteenth", "2025-06-19"), early("Independence Day", "2025-07-03"),
                closed("Independence Day", "2025-07-04"), closed("Labor Day", "2025-09-01"),
                closed("Thanksgiving Day", "2025-11-27"), early("Thanksgiving Day", "2025-11-28"),
                early("Christmas", "2025-12-24"), closed("Christmas", "2025-12-25")));
        FINNHUB.put(2026, List.of(
                closed("New Year's Day", "2026-01-01"), closed("Martin Luther King Jr. Day", "2026-01-19"),
                closed("Washington's Birthday", "2026-02-16"), closed("Good Friday", "2026-04-03"),
                closed("Memorial Day", "2026-05-25"), closed("Juneteenth", "2026-06-19"),
                closed("Independence Day", "2026-07-03"), closed("Labor Day", "2026-09-07"),
                closed("Thanksgiving Day", "2026-11-26"), early("Thanksgiving Day", "2026-11-27"),
                early("Christmas", "2026-12-24"), closed("Christmas", "2026-12-25")));
        FINNHUB.put(2027, List.of(
                closed("New Year's Day", "2027-01-01"), closed("Martin Luther King Jr. Day", "2027-01-18"),
                closed("Washington's Birthday", "2027-02-15"), closed("Good Friday", "2027-03-26"),
                closed("Memorial Day", "2027-05-31"), closed("Juneteenth", "2027-06-18"),
                closed("Independence Day", "2027-07-05"), closed("Labor Day", "2027-09-06"),
                closed("Thanksgiving Day", "2027-11-25"), early("Thanksgiving Day", "2027-11-26"),
                closed("Christmas", "2027-12-24")));
        FINNHUB.put(2028, List.of(
                closed("Martin Luther King Jr. Day", "2028-01-17"), closed("Washington's Birthday", "2028-02-21"),
                closed("Good Friday", "2028-04-14"), closed("Memorial Day", "2028-05-29"),
                closed("Juneteenth", "2028-06-19"), closed("Independence Day", "2028-07-04"),
                closed("Labor Day", "2028-09-04"), closed("Thanksgiving Day", "2028-11-23"),
                early("Thanksgiving Day", "2028-11-24"), closed("Christmas", "2028-12-25")));
    }

    static List<H> years(int from, int to) {
        List<H> out = new ArrayList<>();
        for (int y = from; y <= to; y++) {
            out.addAll(FINNHUB.get(y));
        }
        return out;
    }

    /** The scenario's Finnhub response: 2023–2027. */
    static List<H> scenarioData() {
        return years(2023, 2027);
    }

    static List<H> plus(List<H> base, H... extra) {
        List<H> out = new ArrayList<>(base);
        out.addAll(List.of(extra));
        return out;
    }

    static List<H> without(List<H> base, String... dates) {
        List<String> drop = List.of(dates);
        return base.stream().filter(h -> !drop.contains(h.date())).toList();
    }

    ObjectNode finnhubBody(List<H> rows) {
        ObjectNode body = json.createObjectNode();
        ArrayNode data = body.putArray("data");
        for (H h : rows) {
            ObjectNode o = data.addObject();
            o.put("eventName", h.name());
            o.put("atDate", h.date());
            o.put("tradingHour", h.hours());
            o.put("postMarket", "");
        }
        body.put("exchange", "US");
        body.put("timezone", "America/New_York");
        return body;
    }

    /** Fake fetch with HTTP 200 + this data; must be processed as SUCCESS. Returns the status body. */
    JsonNode fetchOk(List<H> rows) {
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", finnhubBody(rows));
        JsonNode s = fetchRaw(req.toString());
        assertThat(s.get("lastAttemptResult").asText()).as("fetch result %s", s).isEqualTo("SUCCESS");
        return s;
    }

    /** Fake fetch with an arbitrary request body (already JSON); asserts 200 and returns the status body. */
    JsonNode fetchRaw(String requestJson) {
        String saved = accountId;
        accountId = null; // test-support needs no X-Account-Id
        Resp r = exchange(HttpMethod.POST, FETCH, requestJson);
        accountId = saved;
        assertThat(r.status()).as("POST %s %s -> %s", FETCH, requestJson, r.body()).isEqualTo(200);
        assertStatusShape(r.body());
        return r.body();
    }

    JsonNode fetchHttpError(int httpStatus) {
        return fetchRaw("{\"httpStatus\":" + httpStatus + ",\"body\":null}");
    }

    JsonNode fetchNoKey() {
        return fetchRaw("{\"noKey\":true}");
    }

    // =================================================================================================== clock/reset

    void reset() {
        String saved = accountId;
        accountId = null;
        Resp r = exchange(HttpMethod.POST, RESET, null);
        accountId = saved;
        assertThat(r.status()).as("POST %s", RESET).isEqualTo(204);
    }

    void setNow(String instant) {
        Instant.parse(instant);
        String saved = accountId;
        accountId = null;
        Resp r = exchange(HttpMethod.PUT, CLOCK, "{\"now\":\"" + instant + "\"}");
        accountId = saved;
        assertThat(r.status()).as("PUT %s %s -> %s", CLOCK, instant, r.body()).isEqualTo(204);
    }

    // =================================================================================================== HTTP

    record Resp(int status, JsonNode body) {
    }

    Resp get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    Resp post(String path, String body) {
        return exchange(HttpMethod.POST, path, body);
    }

    Resp exchange(HttpMethod method, String path, String rawBody) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (accountId != null) {
            h.set("X-Account-Id", accountId);
        }
        if (rawBody != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        HttpEntity<byte[]> entity = new HttpEntity<>(
                rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8), h);
        ResponseEntity<byte[]> r = http.exchange(path, method, entity, byte[].class);
        JsonNode body = null;
        if (r.getBody() != null && r.getBody().length > 0) {
            String text = new String(r.getBody(), StandardCharsets.UTF_8);
            try {
                body = json.readTree(text);
            } catch (Exception e) {
                throw new AssertionError("response is not JSON: " + text);
            }
        }
        return new Resp(r.getStatusCode().value(), body);
    }

    void assertErrorShape(Resp r, int status, String code, String path) {
        assertThat(r.status()).as("HTTP status, body=%s", r.body()).isEqualTo(status);
        JsonNode b = r.body();
        assertThat(b).as("error body").isNotNull();
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("path").asText()).isEqualTo(path);
        assertThat(b.get("message").asText()).isNotBlank();
        Instant.parse(b.get("timestamp").asText());
        assertThat(b.get("details").isArray()).isTrue();
    }

    static List<String> details(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText() + "=" + d.get("message").asText()));
        return out;
    }

    // =================================================================================================== calendar reads

    Resp nonTradingDaysResp(String from, String to) {
        return get(NTD + "?from=" + from + "&to=" + to);
    }

    /** "date type name source" per returned day, in response order. */
    List<String> nonTradingDays(String from, String to) {
        Resp r = nonTradingDaysResp(from, to);
        assertThat(r.status()).as("GET non-trading-days %s..%s -> %s", from, to, r.body()).isEqualTo(200);
        assertThat(r.body().get("from").asText()).isEqualTo(from);
        assertThat(r.body().get("to").asText()).isEqualTo(to);
        assertThat(r.body().get("days").isArray()).isTrue();
        List<String> out = new ArrayList<>();
        for (JsonNode d : r.body().get("days")) {
            assertThat(d.has("source")).as("source is always present (null for weekends)").isTrue();
            out.add(d.get("date").asText() + " " + d.get("type").asText() + " " + d.get("name").asText() + " "
                    + (d.get("source").isNull() ? "null" : d.get("source").asText()));
        }
        return out;
    }

    /** Just the dates of {@link #nonTradingDays}. */
    List<String> nonTradingDates(String from, String to) {
        return nonTradingDays(from, to).stream().map(s -> s.substring(0, 10)).toList();
    }

    JsonNode status() {
        Resp r = get(STATUS);
        assertThat(r.status()).isEqualTo(200);
        assertStatusShape(r.body());
        return r.body();
    }

    /** Exactly the contract's 8 fields (nothing else, in particular no key). */
    void assertStatusShape(JsonNode s) {
        List<String> names = new ArrayList<>();
        s.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("stale", "warning", "lastAttemptAt", "lastAttemptResult",
                "lastFailureDetail", "lastSuccessAt", "lastSuccessAtLocal", "coveredYears");
        assertThat(s.get("stale").isBoolean()).isTrue();
        assertThat(s.get("coveredYears").isArray()).isTrue();
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    List<Integer> coveredYears(JsonNode s) {
        List<Integer> out = new ArrayList<>();
        s.get("coveredYears").forEach(y -> {
            assertThat(y.isInt()).isTrue();
            out.add(y.asInt());
        });
        return out;
    }

    // =================================================================================================== business data

    void deposit(String amount, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", at);
        n.putNull("remark");
        Resp r = post("/api/cash/transfers", n.toString());
        assertThat(r.status()).as("deposit %s -> %s", amount, r.body()).isEqualTo(201);
    }

    ObjectNode trade(String action, int contracts, String symbol, String expiration, String strike, String type,
                     String netAmount, String tradedAtLocal) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        n.put("netAmount", netAmount);
        n.put("tradedAtLocal", tradedAtLocal);
        n.put("multiplier", 100);
        n.putNull("note");
        return n;
    }

    long okTrade(ObjectNode body) {
        Resp r = post(TRADES, body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** Opening must be rejected with 422 EXPIRATION_NOT_TRADING_DAY, nothing stored; returns the message. */
    String notTradingDay(ObjectNode body) {
        State before = state();
        Resp r = post(TRADES, body.toString());
        assertErrorShape(r, 422, "EXPIRATION_NOT_TRADING_DAY", TRADES);
        assertThat(r.body().get("details")).as("details").isEmpty();
        assertThat(state()).as("nothing stored after %s", body).isEqualTo(before);
        return r.body().get("message").asText();
    }

    ObjectNode closeBody(String action, int contracts, String symbol, String expiration, String strike,
                         String type, String net, String at) {
        ObjectNode n = trade(action, contracts, symbol, expiration, strike, type, net, at);
        n.remove("multiplier");
        return n;
    }

    ObjectNode contractBody(String symbol, String expiration, String strike, String type, int contracts) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("contracts", contracts);
        return n;
    }

    long createdId(Resp r, String what) {
        assertThat(r.status()).as("%s -> %s", what, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    record State(String total, String frozen, String available, long trades, long ledger) {
    }

    State state() {
        JsonNode s = cashSummary();
        Long trades = jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class);
        Resp l = get("/api/cash/ledger?page=1&size=1");
        assertThat(l.status()).isEqualTo(200);
        return new State(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText(),
                trades == null ? 0 : trades, l.body().get("totalItems").asLong());
    }

    JsonNode cashSummary() {
        Resp r = get("/api/cash/summary");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    String available() {
        return cashSummary().get("availableCash").asText();
    }

    JsonNode positions() {
        Resp r = get(POSITIONS);
        assertThat(r.status()).as("GET positions -> %s", r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode position(String label) {
        for (JsonNode p : positions().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label + " in " + positions());
    }

    /** "dte / remainingTradingDays / expirationStatus / daysPastExpiration"; checks the JSON type of the new field. */
    String days(String label) {
        JsonNode p = position(label);
        assertThat(p.has("remainingTradingDays")).as("remainingTradingDays present (null, not omitted)").isTrue();
        JsonNode rtd = p.get("remainingTradingDays");
        assertThat(rtd.isNull() || rtd.isInt()).as("remainingTradingDays is integer or null: %s", rtd).isTrue();
        return p.get("dte").asInt() + " / " + (rtd.isNull() ? "null" : rtd.asInt()) + " / "
                + p.get("expirationStatus").asText() + " / " + p.get("daysPastExpiration").asInt();
    }

    long newAccount(String name) {
        String saved = accountId;
        accountId = null;
        Resp r = post("/api/accounts", "{\"name\":\"" + name + "\"}");
        accountId = saved;
        assertThat(r.status()).as("create account -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /**
     * Requirement scenario: fetch 2023–2027 at 2026-11-23 08:00 NY (13:00Z), then now = 2026-11-23 10:00 NY;
     * deposit 20,000.00; P1, P2 → available 10,770.00 (20,270.00 − frozen 9,500.00).
     */
    void scenario() {
        setNow("2026-11-23T13:00:00Z");
        fetchOk(scenarioData());
        setNow("2026-11-23T15:00:00Z");
        openScenarioTrades();
    }

    void openScenarioTrades() {
        deposit("20000.00", "2026-11-19T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-11-27", "50", "PUT", "120.00", "2026-11-20T10:00"));
        okTrade(trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "150.00", "2026-11-20T10:00"));
        assertThat(available()).isEqualTo("10770.00");
    }

    // =================================================================================================== AC 1–2

    @Test
    @DisplayName("AC1 场景：P1 DTE 4、剩余交易日 3（11-26 感恩节不算、11-27 提前收盘算）；P2 DTE 25、剩余交易日 18；状态不 stale")
    void ac1RemainingTradingDays() {
        scenario();
        JsonNode all = positions();
        assertThat(all.get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
        assertThat(days(P2)).isEqualTo("25 / 18 / ACTIVE / 0");

        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("warning").isNull()).isTrue();
        assertThat(text(s.get("lastAttemptResult"))).isEqualTo("SUCCESS");
        assertThat(text(s.get("lastAttemptAt"))).isEqualTo("2026-11-23T13:00:00Z");
        assertThat(text(s.get("lastSuccessAt"))).isEqualTo("2026-11-23T13:00:00Z");
        assertThat(text(s.get("lastSuccessAtLocal"))).isEqualTo("2026-11-23T08:00:00");
        assertThat(s.get("lastFailureDetail").isNull()).isTrue();
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027);
    }

    @Test
    @DisplayName("AC2 今天 2026-11-27：P1 DTE 0、剩余 0（今天到期）、P2 剩余 15；今天 11-30：P1 已过期 3 天、剩余 null，P2 剩余 14")
    void ac2ExpiresTodayAndExpired() {
        scenario();
        setNow("2026-11-27T15:00:00Z");
        assertThat(days(P1)).isEqualTo("0 / 0 / EXPIRES_TODAY / 0");
        assertThat(days(P2)).isEqualTo("21 / 15 / ACTIVE / 0");

        setNow("2026-11-30T15:00:00Z");
        assertThat(days(P1)).isEqualTo("-3 / null / EXPIRED / 3");
        assertThat(days(P2)).isEqualTo("18 / 14 / ACTIVE / 0");
    }

    // =================================================================================================== Q125

    @Test
    @DisplayName("Q125 今天是周末 / 假日时今天不计：周六 11-28 → P2 15；感恩节 11-26 → P1 1、P2 16；跨感恩节 + 圣诞 + 元旦 → 17")
    void q125TodayOnWeekendOrHoliday() {
        scenario();
        okTrade(trade("STO", 1, "QRTV", "2027-01-15", "40", "PUT", "90.00", "2026-11-23T09:30"));

        setNow("2026-11-28T15:00:00Z"); // Saturday
        assertThat(days(P1)).isEqualTo("-1 / null / EXPIRED / 1");
        assertThat(days(P2)).isEqualTo("20 / 15 / ACTIVE / 0");

        setNow("2026-11-26T15:00:00Z"); // Thanksgiving
        assertThat(days(P1)).isEqualTo("1 / 1 / ACTIVE / 0");
        assertThat(days(P2)).isEqualTo("22 / 16 / ACTIVE / 0");

        // (12-21, 2027-01-15]: 12-24 early close counts, 12-25 and 2027-01-01 do not → 3 + 4 + 10
        setNow("2026-12-21T15:00:00Z");
        assertThat(days("QRTV 2027-01-15 40 P")).isEqualTo("25 / 17 / ACTIVE / 0");
    }

    @Test
    @DisplayName("Q125 今天按美东日期：11-23 23:30 美东（UTC 11-24 04:30）仍是 11-23 → P1 3；11-24 00:00 美东 → 2；16:00 之后不变")
    void q125TodayIsNewYorkDate() {
        scenario();
        setNow("2026-11-23T22:00:00Z"); // 17:00 NY, after the close: today is still not counted
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
        setNow("2026-11-24T04:30:00Z"); // 23:30 NY 11-23
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
        setNow("2026-11-24T05:00:00Z"); // 00:00 NY 11-24
        assertThat(days(P1)).isEqualTo("3 / 2 / ACTIVE / 0");
    }

    @Test
    @DisplayName("Q125 规则年份（2028，Finnhub 未覆盖）：今天 2028-04-10，到期 04-21 → 8（04-14 Good Friday 按规则不算）")
    void q125RulesYear() {
        setNow("2028-04-10T12:00:00Z");
        fetchOk(scenarioData());
        setNow("2028-04-10T14:00:00Z"); // 10:00 EDT
        deposit("10000.00", "2028-04-03T10:00");
        okTrade(trade("STO", 1, "IXQZ", "2028-04-21", "20", "PUT", "35.00", "2028-04-07T10:00"));
        assertThat(days("IXQZ 2028-04-21 20 P")).isEqualTo("11 / 8 / ACTIVE / 0");
    }

    @Test
    @DisplayName("Q125 / AC10 FEAT-009 之前已存的周六到期持仓（IXQZ 2026-11-21 9P）：今天 11-16 → DTE 5、剩余 4；平仓不被拒绝")
    void ac10LegacyWeekendExpiration() {
        setNow("2026-11-16T13:00:00Z");
        fetchOk(scenarioData());
        setNow("2026-11-16T15:00:00Z");
        deposit("5000.00", "2026-11-12T10:00");
        long id = okTrade(trade("STO", 1, "IXQZ", "2026-11-20", "9", "PUT", "30.00", "2026-11-13T10:00"));
        // "entered before FEAT-009": a weekend expiration can no longer be created over the API, so move it via JDBC
        assertThat(jdbc.update("UPDATE option_trade SET expiration_date = DATE '2026-11-21' WHERE id = ?", id))
                .isEqualTo(1);
        assertThat(days("IXQZ 2026-11-21 9 P")).isEqualTo("5 / 4 / ACTIVE / 0");

        Resp r = post(TRADES + "/closes",
                closeBody("BTC", 1, "IXQZ", "2026-11-21", "9", "PUT", "10.00", "2026-11-16T09:50").toString());
        createdId(r, "BTC legacy weekend position");
        assertThat(positions().get("positions")).isEmpty();
    }

    @Test
    @DisplayName("边界：跨账户 —— 账户 B 的同一合约剩余交易日相同；日历接口带不带 X-Account-Id 结果相同")
    void crossAccount() {
        scenario();
        long b = newAccount("FEAT009 账户B");
        accountId = String.valueOf(b);
        deposit("6000.00", "2026-11-19T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-11-27", "50", "PUT", "110.00", "2026-11-20T11:00"));
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
        List<String> withB = nonTradingDays("2026-11-01", "2026-11-30");
        accountId = null;
        assertThat(nonTradingDays("2026-11-01", "2026-11-30")).isEqualTo(withB);
        accountId = "999999"; // not an account: ignored, not validated
        assertThat(nonTradingDays("2026-11-01", "2026-11-30")).isEqualTo(withB);
        assertThat(get(STATUS).status()).isEqualTo(200);
        accountId = "1";
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
    }

    @Test
    @DisplayName("股票持仓不加 remainingTradingDays")
    void stockPositionsUnchanged() {
        scenario();
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", "QRTV");
        n.put("shares", 100);
        n.put("amount", "1.00");
        n.put("tradeDate", "2026-11-20");
        n.putNull("note");
        createdId(post("/api/stock-trades", n.toString()), "TRANSFER_IN");
        Resp r = get("/api/stock-positions");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("positions")).hasSize(1);
        for (JsonNode p : r.body().get("positions")) {
            assertThat(p.has("remainingTradingDays")).isFalse();
        }
    }

    // =================================================================================================== AC 3–5: non-trading days

    @Test
    @DisplayName("AC3（接口部分）2026-11 与 2027-03：周末 + 11-26 / 03-26 不可选；11-27 提前收盘、03-25 不在结果中")
    void ac3MonthsForThePicker() {
        scenario();
        assertThat(nonTradingDays("2026-11-01", "2026-11-30")).containsExactly(
                "2026-11-01 WEEKEND 星期日 null",
                "2026-11-07 WEEKEND 星期六 null",
                "2026-11-08 WEEKEND 星期日 null",
                "2026-11-14 WEEKEND 星期六 null",
                "2026-11-15 WEEKEND 星期日 null",
                "2026-11-21 WEEKEND 星期六 null",
                "2026-11-22 WEEKEND 星期日 null",
                "2026-11-26 HOLIDAY Thanksgiving Day FINNHUB",
                "2026-11-28 WEEKEND 星期六 null",
                "2026-11-29 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2027-03-01", "2027-03-31")).containsExactly(
                "2027-03-06 WEEKEND 星期六 null",
                "2027-03-07 WEEKEND 星期日 null",
                "2027-03-13 WEEKEND 星期六 null",
                "2027-03-14 WEEKEND 星期日 null",
                "2027-03-20 WEEKEND 星期六 null",
                "2027-03-21 WEEKEND 星期日 null",
                "2027-03-26 HOLIDAY Good Friday FINNHUB",
                "2027-03-27 WEEKEND 星期六 null",
                "2027-03-28 WEEKEND 星期日 null");
    }

    @Test
    @DisplayName("AC4 2025-01-06 至 01-12：2025-01-09 National Mourning Day（FINNHUB）、01-11、01-12")
    void ac4NationalMourningDay() {
        scenario();
        Resp r = nonTradingDaysResp("2025-01-06", "2025-01-12");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(json.valueToTree(Map.of(
                "from", "2025-01-06",
                "to", "2025-01-12",
                "days", List.of(
                        day("2025-01-09", "HOLIDAY", "National Mourning Day", "FINNHUB"),
                        day("2025-01-11", "WEEKEND", "星期六", null),
                        day("2025-01-12", "WEEKEND", "星期日", null)))));
    }

    static Map<String, Object> day(String date, String type, String name, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("type", type);
        m.put("name", name);
        m.put("source", source);
        return m;
    }

    @Test
    @DisplayName("AC5 2028（Finnhub 未覆盖）按规则：04-14 Good Friday（RULES）、04-15、04-16；之后拉取返回 2028 → 同一查询来源变为 FINNHUB")
    void ac5RulesThenFinnhub() {
        scenario();
        assertThat(nonTradingDays("2028-04-10", "2028-04-16")).containsExactly(
                "2028-04-14 HOLIDAY Good Friday RULES",
                "2028-04-15 WEEKEND 星期六 null",
                "2028-04-16 WEEKEND 星期日 null");

        setNow("2026-11-24T13:00:00Z");
        JsonNode s = fetchOk(years(2023, 2028));
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027, 2028);
        assertThat(nonTradingDays("2028-04-10", "2028-04-16")).containsExactly(
                "2028-04-14 HOLIDAY Good Friday FINNHUB",
                "2028-04-15 WEEKEND 星期六 null",
                "2028-04-16 WEEKEND 星期日 null");
    }

    @Test
    @DisplayName("规则 3：某年一旦被 Finnhub 覆盖就以 Finnhub 为准 —— 规则推算的 2028-07-04 在 Finnhub 2028 未列出后变成交易日；名称取 Finnhub eventName")
    void coveredYearFollowsFinnhubOnly() {
        scenario();
        List<String> before = nonTradingDays("2028-07-03", "2028-07-05");
        assertThat(before).hasSize(1);
        assertThat(before.get(0)).startsWith("2028-07-04 HOLIDAY ").endsWith(" RULES");

        setNow("2026-11-24T13:00:00Z");
        fetchOk(plus(scenarioData(), closed("Karfreitag (Finnhub)", "2028-04-14")));
        assertThat(nonTradingDays("2028-07-03", "2028-07-05")).isEmpty();
        assertThat(nonTradingDays("2028-04-14", "2028-04-14"))
                .containsExactly("2028-04-14 HOLIDAY Karfreitag (Finnhub) FINNHUB");
    }

    @Test
    @DisplayName("非交易日接口：任意年份（2030 / 2020）按规则；单日范围；只有工作日的范围返回 []；2026 全年 114 天且每日最多一条、升序")
    void nonTradingDaysVariousRanges() {
        scenario();
        assertThat(nonTradingDays("2030-04-15", "2030-04-21")).containsExactly(
                "2030-04-19 HOLIDAY Good Friday RULES",
                "2030-04-20 WEEKEND 星期六 null",
                "2030-04-21 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2020-04-06", "2020-04-12")).containsExactly(
                "2020-04-10 HOLIDAY Good Friday RULES",
                "2020-04-11 WEEKEND 星期六 null",
                "2020-04-12 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2026-11-26", "2026-11-26"))
                .containsExactly("2026-11-26 HOLIDAY Thanksgiving Day FINNHUB");
        assertThat(nonTradingDays("2026-11-27", "2026-11-27")).as("early close is a trading day (Q122)").isEmpty();
        assertThat(nonTradingDays("2026-11-23", "2026-11-25")).isEmpty();

        List<String> year = nonTradingDates("2026-01-01", "2026-12-31");
        assertThat(year).hasSize(114).doesNotHaveDuplicates().isSorted();
        assertThat(year).contains("2026-07-03", "2026-12-25").doesNotContain("2026-12-24", "2026-11-27");
    }

    @Test
    @DisplayName("非交易日接口：周末优先 —— Finnhub 列出落在周六的日期（2026-07-04）仍按 WEEKEND 返回一条")
    void weekendWinsOverFinnhubRow() {
        setNow("2026-11-23T13:00:00Z");
        fetchOk(plus(scenarioData(), closed("Independence Day", "2026-07-04")));
        assertThat(nonTradingDays("2026-07-03", "2026-07-06")).containsExactly(
                "2026-07-03 HOLIDAY Independence Day FINNHUB",
                "2026-07-04 WEEKEND 星期六 null",
                "2026-07-05 WEEKEND 星期日 null");
    }

    @Test
    @DisplayName("非交易日接口：范围 366 天可以（2027-01-01..2028-01-02），367 天 → 400 VALIDATION_ERROR to「日期范围最多 366 天」")
    void rangeLimit() {
        scenario();
        List<String> ok = nonTradingDates("2027-01-01", "2028-01-02");
        assertThat(ok).hasSize(116).startsWith("2027-01-01").endsWith("2028-01-01", "2028-01-02");

        Resp r = nonTradingDaysResp("2027-01-01", "2028-01-03");
        assertErrorShape(r, 400, "VALIDATION_ERROR", NTD);
        assertThat(details(r)).containsExactly("to=日期范围最多 366 天");
    }

    @Test
    @DisplayName("非交易日接口：from 晚于 to → 400 VALIDATION_ERROR to「开始日期不能晚于结束日期」；from = to 可以")
    void fromAfterTo() {
        Resp r = nonTradingDaysResp("2026-11-30", "2026-11-01");
        assertErrorShape(r, 400, "VALIDATION_ERROR", NTD);
        assertThat(details(r)).containsExactly("to=开始日期不能晚于结束日期");
        assertThat(nonTradingDaysResp("2026-11-28", "2026-11-28").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("非交易日接口：缺参数 / 格式不对 → 400 BAD_REQUEST（全部字段一起，先于 VALIDATION_ERROR）")
    void badRequests() {
        Resp missingBoth = get(NTD);
        assertErrorShape(missingBoth, 400, "BAD_REQUEST", NTD);
        assertThat(details(missingBoth)).containsExactly("from=请填写日期", "to=请填写日期");

        Resp missingTo = get(NTD + "?from=2026-11-01");
        assertErrorShape(missingTo, 400, "BAD_REQUEST", NTD);
        assertThat(details(missingTo)).containsExactly("to=请填写日期");

        Resp feb30 = nonTradingDaysResp("2026-02-01", "2026-02-30");
        assertErrorShape(feb30, 400, "BAD_REQUEST", NTD);
        assertThat(details(feb30)).containsExactly("to=日期格式不正确（YYYY-MM-DD）");

        Resp slashes = nonTradingDaysResp("2026/11/01", "2026-11-30");
        assertErrorShape(slashes, 400, "BAD_REQUEST", NTD);
        assertThat(details(slashes)).containsExactly("from=日期格式不正确（YYYY-MM-DD）");

        Resp both = get(NTD + "?from=2026-13-01");
        assertErrorShape(both, 400, "BAD_REQUEST", NTD);
        assertThat(details(both)).containsExactly("from=日期格式不正确（YYYY-MM-DD）", "to=请填写日期");

        // from invalid and (as text) "later" than to: only BAD_REQUEST is reported
        Resp precedence = nonTradingDaysResp("2026-13-01", "2020-01-01");
        assertErrorShape(precedence, 400, "BAD_REQUEST", NTD);
        assertThat(details(precedence)).containsExactly("from=日期格式不正确（YYYY-MM-DD）");
    }

    // =================================================================================================== AC 6 / Q126

    @Test
    @DisplayName("AC6 绕过页面 BTO 1 张 IXQZ 2027-03-26 30C 净额 80.00 → 422「到期日 2027-03-26 不是美股交易日（Good Friday）」，不保存，可用现金仍 10,770.00")
    void ac6RejectGoodFriday() {
        scenario();
        String msg = notTradingDay(trade("BTO", 1, "IXQZ", "2027-03-26", "30", "CALL", "80.00", "2026-11-23T09:50"));
        assertThat(msg).isEqualTo("到期日 2027-03-26 不是美股交易日（Good Friday）");
        assertThat(available()).isEqualTo("10770.00");
        // the day before is fine
        okTrade(trade("BTO", 1, "IXQZ", "2027-03-25", "30", "CALL", "80.00", "2026-11-23T09:50"));
        assertThat(available()).isEqualTo("10690.00");
    }

    @Test
    @DisplayName("Q126 周末、规则年份、Finnhub 新增的临时休市都拒绝（STO / BTO、CALL / PUT）；原因同非交易日接口的 name")
    void q126RejectionVariants() {
        scenario();
        assertThat(notTradingDay(trade("STO", 1, "QRTV", "2026-11-28", "40", "PUT", "50.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-28 不是美股交易日（星期六）");
        assertThat(notTradingDay(trade("BTO", 1, "QRTV", "2026-11-29", "40", "CALL", "50.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-29 不是美股交易日（星期日）");
        assertThat(notTradingDay(trade("STO", 1, "QRTV", "2026-11-26", "40", "PUT", "50.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-26 不是美股交易日（Thanksgiving Day）");
        assertThat(notTradingDay(trade("BTO", 1, "QRTV", "2028-04-14", "40", "PUT", "50.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2028-04-14 不是美股交易日（Good Friday）");

        // early close (Q122) and Christmas Eve are trading days
        okTrade(trade("BTO", 1, "QRTV", "2026-12-24", "40", "CALL", "5.00", "2026-11-23T09:50"));

        // an ad-hoc closure appearing in a later fetch is rejected from then on
        okTrade(trade("BTO", 1, "QRTV", "2026-12-04", "40", "CALL", "5.00", "2026-11-23T09:50"));
        setNow("2026-11-24T13:00:00Z");
        fetchOk(plus(scenarioData(), closed("Ad hoc closure", "2026-12-04")));
        setNow("2026-11-24T15:00:00Z");
        assertThat(notTradingDay(trade("BTO", 1, "QRTV", "2026-12-04", "41", "CALL", "5.00", "2026-11-24T09:50")))
                .isEqualTo("到期日 2026-12-04 不是美股交易日（Ad hoc closure）");
    }

    @Test
    @DisplayName("Q126 检查顺序：字段校验（含 16:00 截止、到期日早于成交日）先报 400；交易日检查先于方向冲突、multiplier、现金、覆盖")
    void q126CheckOrder() {
        scenario();
        // 1. field validation first
        ObjectNode zero = trade("STO", 0, "QRTV", "2026-11-26", "40", "PUT", "50.00", "2026-11-23T09:50");
        State before = state();
        Resp r = post(TRADES, zero.toString());
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES);
        assertThat(details(r)).containsExactly("contracts=张数必须是正整数");
        // 16:00 cutoff on a (Saturday) expiration date
        r = post(TRADES, trade("BTO", 1, "QRTV", "2026-11-21", "40", "PUT", "5.00", "2026-11-21T16:30").toString());
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES);
        assertThat(details(r)).containsExactly("tradedAtLocal=成交时间不能晚于到期日（2026-11-21）美东 16:00");
        // expiration before the trade date
        r = post(TRADES, trade("BTO", 1, "QRTV", "2026-11-22", "40", "PUT", "5.00", "2026-11-23T09:00").toString());
        assertErrorShape(r, 400, "VALIDATION_ERROR", TRADES);
        assertThat(details(r)).containsExactly("expirationDate=到期日不能早于成交日（2026-11-23）");
        // unparsable expiration
        r = post(TRADES, trade("BTO", 1, "QRTV", "2026-02-30", "40", "PUT", "5.00", "2026-11-23T09:00").toString());
        assertErrorShape(r, 400, "BAD_REQUEST", TRADES);
        assertThat(state()).isEqualTo(before);

        // 2. before CASH_INSUFFICIENT: STO put needing 100,000.00
        assertThat(notTradingDay(trade("STO", 1, "QRTV", "2026-11-26", "1000", "PUT", "50.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-26 不是美股交易日（Thanksgiving Day）");
        // 3. before COVERAGE_INSUFFICIENT: STO call without shares (FEAT-039)
        assertThat(notTradingDay(trade("STO", 1, "IXQZ", "2026-11-26", "10", "CALL", "20.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-26 不是美股交易日（Thanksgiving Day）");
        // 4. before POSITION_DIRECTION_CONFLICT / MULTIPLIER_MISMATCH: an existing short call on a Saturday
        //    expiration, entered through the e2e "legacy" endpoint, which the contract says is not checked
        createdId(post(LEGACY, trade("STO", 1, "IXQZ", "2026-11-28", "9", "CALL", "22.00", "2026-11-20T12:00")
                .toString()), "legacy STO call on a Saturday expiration (not checked)");
        assertThat(notTradingDay(trade("BTO", 1, "IXQZ", "2026-11-28", "9", "CALL", "5.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2026-11-28 不是美股交易日（星期六）");
        ObjectNode otherMultiplier = trade("STO", 1, "IXQZ", "2026-11-28", "9", "CALL", "5.00", "2026-11-23T09:50");
        otherMultiplier.put("multiplier", 10);
        assertThat(notTradingDay(otherMultiplier)).isEqualTo("到期日 2026-11-28 不是美股交易日（星期六）");
    }

    @Test
    @DisplayName("Q126 不检查：修改、平仓、作废、批量作废、被指派、行权、删除（到期日 2026-12-04 之后被 Finnhub 列为临时休市）；剩余交易日照公式 7")
    void q126NotChecked() {
        setNow("2026-11-23T13:00:00Z");
        fetchOk(scenarioData());
        setNow("2026-11-23T15:00:00Z");
        deposit("20000.00", "2026-11-19T10:00");
        long a = okTrade(trade("STO", 1, "XYZ", "2026-12-04", "40", "PUT", "50.00", "2026-11-20T10:00"));
        okTrade(trade("STO", 1, "XYZ", "2026-12-04", "35", "PUT", "40.00", "2026-11-20T10:01"));
        okTrade(trade("BTO", 1, "QRTV", "2026-12-04", "30", "CALL", "60.00", "2026-11-20T10:02"));
        okTrade(trade("STO", 1, "XYZ", "2026-12-04", "30", "PUT", "30.00", "2026-11-20T10:03"));
        long e = okTrade(trade("BTO", 1, "QRTV", "2026-12-04", "25", "CALL", "20.00", "2026-11-20T10:04"));
        okTrade(trade("STO", 1, "XYZ", "2026-12-04", "20", "PUT", "10.00", "2026-11-20T10:05"));

        // Finnhub now lists 2026-12-04 as a full-day closure: these become "stored data" (not re-checked)
        setNow("2026-11-23T14:00:00Z");
        fetchOk(plus(scenarioData(), closed("Ad hoc closure", "2026-12-04")));
        setNow("2026-11-23T15:00:00Z");
        assertThat(nonTradingDays("2026-11-30", "2026-12-06")).containsExactly(
                "2026-12-04 HOLIDAY Ad hoc closure FINNHUB",
                "2026-12-05 WEEKEND 星期六 null",
                "2026-12-06 WEEKEND 星期日 null");
        // (11-23, 12-04]: 24, 25, 27, 30, 12-01, 02, 03 (11-26 and 12-04 itself are not trading days)
        assertThat(days("XYZ 2026-12-04 40 P")).isEqualTo("11 / 7 / ACTIVE / 0");

        // edit (PUT) — contract cannot change, not checked
        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 1);
        edit.put("netAmount", "55.00");
        edit.put("tradedAtLocal", "2026-11-20T10:00");
        edit.put("multiplier", 100);
        edit.put("note", "改净额");
        Resp put = exchange(HttpMethod.PUT, TRADES + "/" + a, edit.toString());
        assertThat(put.status()).as("PUT -> %s", put.body()).isEqualTo(200);
        // close
        createdId(post(TRADES + "/closes",
                closeBody("BTC", 1, "XYZ", "2026-12-04", "40", "PUT", "20.00", "2026-11-23T09:30").toString()), "BTC");
        // delete
        assertThat(exchange(HttpMethod.DELETE, TRADES + "/" + e, null).status()).isEqualTo(204);

        // after 2026-12-04 17:30 New York
        setNow("2026-12-04T23:00:00Z");
        createdId(post(TRADES + "/expirations", contractBody("XYZ", "2026-12-04", "30", "PUT", 1).toString()), "EXP");
        ObjectNode batch = json.createObjectNode();
        batch.putArray("items").add(contractBody("XYZ", "2026-12-04", "20", "PUT", 1));
        Resp b = post(TRADES + "/expirations/batch", batch.toString());
        assertThat(b.status()).as("batch -> %s", b.body()).isEqualTo(201);

        ObjectNode asg = contractBody("XYZ", "2026-12-04", "35", "PUT", 1);
        asg.put("assignmentDate", "2026-12-04");
        asg.put("assignmentFee", "0.00");
        asg.putNull("note");
        createdId(post(TRADES + "/assignments", asg.toString()), "ASG");

        ObjectNode exr = contractBody("QRTV", "2026-12-04", "30", "CALL", 1);
        exr.put("exerciseDate", "2026-12-04");
        exr.put("exerciseFee", "0.00");
        exr.putNull("note");
        createdId(post(TRADES + "/exercises", exr.toString()), "EXR");

        assertThat(positions().get("positions")).isEmpty();
    }

    @Test
    @DisplayName("Q126 已存数据不追溯：开仓后 Finnhub 把到期日列为休市，持仓照常显示，GET 交易不报错")
    void q126StoredDataNotRetroactive() {
        scenario();
        long id = okTrade(trade("STO", 1, "QRTV", "2026-12-11", "40", "PUT", "60.00", "2026-11-23T09:40"));
        setNow("2026-11-24T13:00:00Z");
        fetchOk(plus(scenarioData(), closed("Ad hoc closure", "2026-12-11")));
        setNow("2026-11-24T15:00:00Z");
        assertThat(get(TRADES + "/" + id).status()).isEqualTo(200);
        // (11-24, 12-11]: 25, 27, 30, 12-01..04, 07..10 = 11
        assertThat(days("QRTV 2026-12-11 40 P")).isEqualTo("17 / 11 / ACTIVE / 0");
    }

    // =================================================================================================== status / AC 7–8

    @Test
    @DisplayName("状态：新库从未拉取 → lastAttemptResult null、stale false、warning null、coveredYears []；此时按规则（11-26 RULES）")
    void statusFreshDatabase() {
        setNow("2026-11-23T15:00:00Z");
        JsonNode s = status();
        assertThat(s.get("stale").asBoolean()).isFalse();
        for (String f : List.of("warning", "lastAttemptAt", "lastAttemptResult", "lastFailureDetail", "lastSuccessAt",
                "lastSuccessAtLocal")) {
            assertThat(s.get(f).isNull()).as(f).isTrue();
        }
        assertThat(coveredYears(s)).isEmpty();
        List<String> nov = nonTradingDays("2026-11-26", "2026-11-26");
        assertThat(nov).hasSize(1);
        assertThat(nov.get(0)).startsWith("2026-11-26 HOLIDAY ").endsWith(" RULES");
    }

    @Test
    @DisplayName("AC7（接口部分）无 key、无休市数据：状态 NO_KEY + 从未成功提示；规则仍禁用 11-26、2027-03-26；录入、平仓照常")
    void ac7NoKeyNeverSucceeded() {
        setNow("2026-11-23T15:00:00Z");
        JsonNode s = fetchNoKey();
        assertThat(s.get("stale").asBoolean()).isTrue();
        assertThat(text(s.get("warning"))).isEqualTo(NEVER_SUCCEEDED);
        assertThat(text(s.get("lastAttemptAt"))).isEqualTo("2026-11-23T15:00:00Z");
        assertThat(text(s.get("lastAttemptResult"))).isEqualTo("NO_KEY");
        assertThat(s.get("lastFailureDetail").isNull()).isTrue();
        assertThat(s.get("lastSuccessAt").isNull()).isTrue();
        assertThat(s.get("lastSuccessAtLocal").isNull()).isTrue();
        assertThat(coveredYears(s)).isEmpty();
        assertThat(status()).isEqualTo(s);

        List<String> nov = nonTradingDays("2026-11-23", "2026-11-29");
        assertThat(nov).hasSize(3);
        assertThat(nov.get(0)).startsWith("2026-11-26 HOLIDAY ").endsWith(" RULES");
        assertThat(nov.subList(1, 3)).containsExactly("2026-11-28 WEEKEND 星期六 null", "2026-11-29 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2027-03-26", "2027-03-26")).containsExactly("2027-03-26 HOLIDAY Good Friday RULES");

        openScenarioTrades();
        assertThat(days(P1)).isEqualTo("4 / 3 / ACTIVE / 0");
        assertThat(notTradingDay(trade("BTO", 1, "IXQZ", "2027-03-26", "30", "CALL", "80.00", "2026-11-23T09:50")))
                .isEqualTo("到期日 2027-03-26 不是美股交易日（Good Friday）");
        String thanksgiving = notTradingDay(trade("STO", 1, "QRTV", "2026-11-26", "40", "PUT", "50.00",
                "2026-11-23T09:50"));
        assertThat(thanksgiving).startsWith("到期日 2026-11-26 不是美股交易日（").endsWith("）")
                .isNotEqualTo("到期日 2026-11-26 不是美股交易日（）");
        createdId(post(TRADES + "/closes",
                closeBody("BTC", 1, "QRTV", "2026-11-27", "50", "PUT", "30.00", "2026-11-23T09:55").toString()), "BTC");
        assertThat(available()).isEqualTo("15740.00"); // 10,770.00 − 30.00 + 5,000.00 released
    }

    @Test
    @DisplayName("AC8 11-20 08:00 成功、11-21 拉取返回 500 → 状态同契约示例；数据不变（2025-01-09 仍 FINNHUB）；下一次成功后提示消失")
    void ac8FailureKeepsDataThenRecovers() {
        setNow("2026-11-20T13:00:00Z");
        fetchOk(scenarioData());
        setNow("2026-11-21T13:00:00Z");
        JsonNode s = fetchHttpError(500);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("stale", true);
        expected.put("warning", "休市日数据未更新（上次成功：2026-11-20 08:00 美东）");
        expected.put("lastAttemptAt", "2026-11-21T13:00:00Z");
        expected.put("lastAttemptResult", "HTTP_ERROR");
        expected.put("lastFailureDetail", "HTTP 500");
        expected.put("lastSuccessAt", "2026-11-20T13:00:00Z");
        expected.put("lastSuccessAtLocal", "2026-11-20T08:00:00");
        expected.put("coveredYears", List.of(2023, 2024, 2025, 2026, 2027));
        assertThat(s).isEqualTo(json.valueToTree(expected));
        assertThat(status()).isEqualTo(s);

        assertThat(nonTradingDays("2025-01-06", "2025-01-12")).containsExactly(
                "2025-01-09 HOLIDAY National Mourning Day FINNHUB",
                "2025-01-11 WEEKEND 星期六 null",
                "2025-01-12 WEEKEND 星期日 null");

        setNow("2026-11-22T13:00:00Z");
        JsonNode ok = fetchOk(scenarioData());
        assertThat(ok.get("stale").asBoolean()).isFalse();
        assertThat(ok.get("warning").isNull()).isTrue();
        assertThat(ok.get("lastFailureDetail").isNull()).isTrue();
        assertThat(text(ok.get("lastSuccessAtLocal"))).isEqualTo("2026-11-22T08:00:00");
        assertThat(status()).isEqualTo(ok);
    }

    @Test
    @DisplayName("规则 5：格式不对（无 data 数组、body null、data 不是数组、不是 JSON、atDate 非法）→ INVALID_RESPONSE；503 → HTTP_ERROR；都不改已存数据")
    void invalidResponsesKeepData() {
        setNow("2026-11-20T13:00:00Z");
        fetchOk(scenarioData());
        List<String> nov = nonTradingDays("2026-11-01", "2026-11-30");

        List<String> invalid = List.of(
                "{\"httpStatus\":200,\"body\":{\"exchange\":\"US\",\"timezone\":\"America/New_York\"}}",
                "{\"httpStatus\":200,\"body\":null}",
                "{\"httpStatus\":200,\"body\":{\"data\":\"not an array\"}}",
                "{\"httpStatus\":200,\"body\":\"<html>Bad Gateway</html>\"}",
                // one bad atDate rejects the whole response: the (otherwise valid) shrunken 2026 must not be applied
                "{\"httpStatus\":200,\"body\":{\"data\":["
                        + "{\"eventName\":\"Christmas\",\"atDate\":\"2026-12-25\",\"tradingHour\":\"\"},"
                        + "{\"eventName\":\"Bad\",\"atDate\":\"2026-02-30\",\"tradingHour\":\"\"}],"
                        + "\"exchange\":\"US\",\"timezone\":\"America/New_York\"}}");
        int hour = 14;
        for (String body : invalid) {
            String at = "2026-11-21T" + hour++ + ":00:00Z";
            setNow(at);
            JsonNode s = fetchRaw(body);
            assertThat(text(s.get("lastAttemptResult"))).as(body).isEqualTo("INVALID_RESPONSE");
            assertThat(text(s.get("lastAttemptAt"))).isEqualTo(at);
            assertThat(s.get("stale").asBoolean()).isTrue();
            assertThat(text(s.get("warning"))).isEqualTo("休市日数据未更新（上次成功：2026-11-20 08:00 美东）");
            assertThat(text(s.get("lastFailureDetail"))).isNotBlank().doesNotContain("exchange=").doesNotContain("token");
            assertThat(text(s.get("lastSuccessAt"))).isEqualTo("2026-11-20T13:00:00Z");
            assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027);
            assertThat(nonTradingDays("2026-11-01", "2026-11-30")).as("data unchanged after %s", body).isEqualTo(nov);
        }

        setNow("2026-11-21T20:00:00Z");
        JsonNode s = fetchHttpError(503);
        assertThat(text(s.get("lastAttemptResult"))).isEqualTo("HTTP_ERROR");
        assertThat(text(s.get("lastFailureDetail"))).isEqualTo("HTTP 503");
        assertThat(nonTradingDays("2026-11-01", "2026-11-30")).isEqualTo(nov);
    }

    @Test
    @DisplayName("状态：从未成功时失败 → 从未成功提示；成功后再无 key → 上次成功提示（夏令时：12:00Z = 08:00 美东）")
    void statusWarningVariants() {
        setNow("2026-06-30T12:00:00Z");
        JsonNode s = fetchHttpError(500);
        assertThat(text(s.get("warning"))).isEqualTo(NEVER_SUCCEEDED);
        assertThat(s.get("lastSuccessAt").isNull()).isTrue();

        setNow("2026-07-01T12:00:00Z");
        s = fetchOk(scenarioData());
        assertThat(text(s.get("lastSuccessAtLocal"))).isEqualTo("2026-07-01T08:00:00");

        setNow("2026-07-02T12:00:00Z");
        s = fetchNoKey();
        assertThat(text(s.get("lastAttemptResult"))).isEqualTo("NO_KEY");
        assertThat(s.get("lastFailureDetail").isNull()).isTrue();
        assertThat(text(s.get("warning"))).isEqualTo("休市日数据未更新（上次成功：2026-07-01 08:00 美东）");
        assertThat(text(s.get("lastSuccessAt"))).isEqualTo("2026-07-01T12:00:00Z");
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027);
    }

    // =================================================================================================== Q127

    @Test
    @DisplayName("Q127 新响应中出现的年份整年替换（删掉的 11-26 恢复为交易日、11-27 改为全天休市、新增 2028），未返回的 2023 保留")
    void q127ReplaceByYear() {
        setNow("2026-11-20T13:00:00Z");
        fetchOk(scenarioData());

        // 2024–2028; in 2026 Thanksgiving is gone and the day after became a full-day closure
        List<H> second = new ArrayList<>(years(2024, 2025));
        second.addAll(without(FINNHUB.get(2026), "2026-11-26", "2026-11-27"));
        second.add(closed("Day after Thanksgiving", "2026-11-27"));
        second.addAll(years(2027, 2028));
        setNow("2026-11-21T13:00:00Z");
        JsonNode s = fetchOk(second);
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027, 2028);

        assertThat(nonTradingDays("2026-11-23", "2026-11-29")).containsExactly(
                "2026-11-27 HOLIDAY Day after Thanksgiving FINNHUB",
                "2026-11-28 WEEKEND 星期六 null",
                "2026-11-29 WEEKEND 星期日 null");
        assertThat(nonTradingDays("2023-11-20", "2023-11-24"))
                .as("2023 not in the new response: kept").containsExactly("2023-11-23 HOLIDAY Thanksgiving Day FINNHUB");
        assertThat(nonTradingDays("2028-04-14", "2028-04-14")).containsExactly("2028-04-14 HOLIDAY Good Friday FINNHUB");

        setNow("2026-11-23T15:00:00Z");
        deposit("10000.00", "2026-11-19T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-11-26", "40", "PUT", "50.00", "2026-11-23T09:30"));
        assertThat(notTradingDay(trade("STO", 1, "QRTV", "2026-11-27", "40", "PUT", "50.00", "2026-11-23T09:30")))
                .isEqualTo("到期日 2026-11-27 不是美股交易日（Day after Thanksgiving）");
        // (11-23, 11-26]: 24, 25, 26 (now a trading day)
        assertThat(days("QRTV 2026-11-26 40 P")).isEqualTo("3 / 3 / ACTIVE / 0");

        // a third response with only 2027–2028 keeps 2023–2026 as they are now (not reverted)
        setNow("2026-11-24T13:00:00Z");
        s = fetchOk(years(2027, 2028));
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027, 2028);
        assertThat(nonTradingDates("2026-11-23", "2026-11-29")).containsExactly("2026-11-27", "2026-11-28", "2026-11-29");
        assertThat(nonTradingDates("2025-01-09", "2025-01-09")).containsExactly("2025-01-09");
    }

    @Test
    @DisplayName("Q127 成功但 data 为空数组：不替换任何年份，覆盖年份不变")
    void q127EmptyDataReplacesNothing() {
        setNow("2026-11-20T13:00:00Z");
        fetchOk(scenarioData());
        setNow("2026-11-21T13:00:00Z");
        JsonNode s = fetchOk(List.of());
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(coveredYears(s)).containsExactly(2023, 2024, 2025, 2026, 2027);
        assertThat(nonTradingDays("2026-11-26", "2026-11-26"))
                .containsExactly("2026-11-26 HOLIDAY Thanksgiving Day FINNHUB");
    }
}
