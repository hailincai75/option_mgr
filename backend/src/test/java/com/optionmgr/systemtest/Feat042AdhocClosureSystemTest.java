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
 * QA system tests for FEAT-042 临时休市检测与提醒, end to end over REST on a random port (never 8080).
 *
 * <p>Same fakes as {@code Feat009TradingCalendarSystemTest} (contract docs/api/FEAT-009.md / FEAT-042.md「测试支持」):
 * profiles {@code test} + {@code e2e}; every "fetch" is {@code POST /api/test-support/trading-calendar/fetch} with a
 * fake Finnhub response (processed exactly like a real fetch, including ad-hoc closure detection); "now" is fixed with
 * {@code PUT /api/test-support/clock}; {@code POST /api/test-support/reset} before and after every test (also empties
 * {@code adhoc_closure}).
 *
 * <p>Scenario (requirement): the fake Finnhub returns 2023–2027 at 2026-11-30 08:00 New York (incl. 2025-01-09
 * National Mourning Day, without 2026-12-04); on 2026-12-01 08:00 it additionally lists 2026-12-04 National Day of
 * Mourning (full day). Account A (default account 1): P1 = STO 1 QRTV 2026-12-04 50P, P2 = BTO 2 IXQZ 2026-12-04 9C,
 * P3 = STO 1 QRTV 2026-12-18 45P. Account B: only a 2026-12-18 position. Account C: STO 1 QRTV 2025-01-09 40P entered
 * before any Finnhub data, and P4 = STO 1 IXQZ 2026-12-04 8P. All tickers are fictional.
 *
 * <p>AC 6 seed: Jollyday 2.22.1 already knows 2025-01-09 as a (one-time, Q134) non-trading day, so Q126 rejects a
 * normal opening on it, and the e2e legacy endpoint only accepts STO CALL. The put is therefore opened on the trading
 * day 2025-01-10 through the normal {@code POST /api/option-trades} and its expiration moved to 2025-01-09 with JDBC
 * (same technique as {@code OptionTradeSystemTestBase.moveExpiration}) — "entered before FEAT-009, never processed".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@DisplayName("FEAT-042 临时休市检测与提醒（系统测试：假 Finnhub + 假时钟）")
class Feat042AdhocClosureSystemTest {

    static final String ALERTS = "/api/adhoc-closure-alerts";
    static final String CLOSURES = "/api/trading-calendar/adhoc-closures";
    static final String NTD = "/api/trading-calendar/non-trading-days";
    static final String STATUS = "/api/trading-calendar/status";
    static final String FETCH = "/api/test-support/trading-calendar/fetch";
    static final String CLOCK = "/api/test-support/clock";
    static final String RESET = "/api/test-support/reset";
    static final String TRADES = "/api/option-trades";
    static final String POSITIONS = "/api/option-positions";

    static final String P1 = "QRTV 2026-12-04 50 P";
    static final String P2 = "IXQZ 2026-12-04 9 C";
    static final String P3 = "QRTV 2026-12-18 45 P";
    static final String P4 = "IXQZ 2026-12-04 8 P";
    static final String OLD = "QRTV 2025-01-09 40 P";

    static final String MOURNING_2025 = "National Mourning Day";
    static final String MOURNING_2026 = "National Day of Mourning";

    /** Before any fetch (account C's 2025 trade is entered here). */
    static final String T_PRE = "2026-11-30T12:00:00Z";
    /** 2026-11-30 08:00 New York: first fetch. */
    static final String T_FETCH1 = "2026-11-30T13:00:00Z";
    /** 2026-11-30 10:00 New York: scenario trades. */
    static final String T_TRADE = "2026-11-30T15:00:00Z";
    /** 2026-12-01 08:00 New York: fetch with 2026-12-04. */
    static final String T_FETCH2 = "2026-12-01T13:00:00Z";
    /** 2026-12-01 10:00 New York: "today" of AC 3–7. */
    static final String T_DAY2 = "2026-12-01T15:00:00Z";
    /** 2026-12-02 08:00 New York. */
    static final String T_FETCH3 = "2026-12-02T13:00:00Z";
    static final String T_DAY3 = "2026-12-02T15:00:00Z";
    /** 2026-12-03 08:00 New York. */
    static final String T_FETCH4 = "2026-12-03T13:00:00Z";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    /** X-Account-Id of the business calls; null = omit. */
    String accountId = "1";

    long accountB;
    long accountC;

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

    /** NYSE calendar as published (same data as the FEAT-009 system test), plus 2025-01-09. */
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
                closed("New Year's Day", "2025-01-01"), closed(MOURNING_2025, "2025-01-09"),
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

    /** 2026-11-30 response: 2023–2027 (with 2025-01-09, without 2026-12-04). */
    static List<H> scenarioData() {
        return years(2023, 2027);
    }

    /** 2026-12-01 response: the same plus 2026-12-04 National Day of Mourning (full day). */
    static List<H> withDec4() {
        return plus(scenarioData(), closed(MOURNING_2026, "2026-12-04"));
    }

    @SafeVarargs
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

    /** Fake fetch with HTTP 200 + this data; must be processed as SUCCESS. */
    JsonNode fetchOk(List<H> rows) {
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", finnhubBody(rows));
        JsonNode s = fetchRaw(req.toString());
        assertThat(s.get("lastAttemptResult").asText()).as("fetch result %s", s).isEqualTo("SUCCESS");
        return s;
    }

    /** setNow + fetchOk. */
    JsonNode fetchAt(String instant, List<H> rows) {
        setNow(instant);
        return fetchOk(rows);
    }

    JsonNode fetchRaw(String requestJson) {
        String saved = accountId;
        accountId = null;
        Resp r = exchange(HttpMethod.POST, FETCH, requestJson);
        accountId = saved;
        assertThat(r.status()).as("POST %s -> %s", FETCH, r.body()).isEqualTo(200);
        return r.body();
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
        return exchangeAs(accountId, method, path, rawBody);
    }

    Resp exchangeAs(String account, HttpMethod method, String path, String rawBody) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (account != null) {
            h.set("X-Account-Id", account);
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

    static List<String> detailFields(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText()));
        return out;
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    // =================================================================================================== FEAT-042 reads

    /** Closures (whole system, no X-Account-Id) as "date|eventName|detectedOn|status|revokedOn"; checks the shape. */
    List<String> closures() {
        return closures("");
    }

    List<String> closures(String query) {
        Resp r = exchangeAs(null, HttpMethod.GET, CLOSURES + query, null);
        assertThat(r.status()).as("GET %s%s -> %s", CLOSURES, query, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactly("closures");
        assertThat(r.body().get("closures").isArray()).isTrue();
        List<String> out = new ArrayList<>();
        String prev = "";
        for (JsonNode c : r.body().get("closures")) {
            assertThat(fieldNames(c)).containsExactlyInAnyOrder("date", "eventName", "detectedOn", "status", "revokedOn");
            String date = c.get("date").asText();
            assertThat(date).as("closures sorted by date ascending").isGreaterThan(prev);
            prev = date;
            String status = c.get("status").asText();
            assertThat(status).isIn("ACTIVE", "REVOKED");
            if (status.equals("ACTIVE")) {
                assertThat(c.get("revokedOn").isNull()).as("ACTIVE has revokedOn null: %s", c).isTrue();
            }
            out.add(date + "|" + c.get("eventName").asText() + "|" + c.get("detectedOn").asText() + "|" + status
                    + "|" + text(c.get("revokedOn")));
        }
        return out;
    }

    /** Alerts of the current account as "date|eventName|count"; checks shape, order and the message text. */
    List<String> alerts() {
        Resp r = get(ALERTS);
        assertThat(r.status()).as("GET %s (account %s) -> %s", ALERTS, accountId, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactly("alerts");
        assertThat(r.body().get("alerts").isArray()).isTrue();
        List<String> out = new ArrayList<>();
        String prev = "";
        for (JsonNode a : r.body().get("alerts")) {
            assertThat(fieldNames(a)).containsExactlyInAnyOrder("date", "eventName", "affectedPositionCount", "message");
            String date = a.get("date").asText();
            assertThat(date).as("alerts sorted by date ascending").isGreaterThan(prev);
            prev = date;
            assertThat(a.get("affectedPositionCount").isInt()).isTrue();
            int n = a.get("affectedPositionCount").asInt();
            assertThat(n).as("only dates with affected positions").isGreaterThanOrEqualTo(1);
            String name = a.get("eventName").asText();
            assertThat(a.get("message").asText()).isEqualTo(message(date, name, n));
            out.add(date + "|" + name + "|" + n);
        }
        return out;
    }

    List<String> alertsOf(long account) {
        String saved = accountId;
        accountId = String.valueOf(account);
        try {
            return alerts();
        } finally {
            accountId = saved;
        }
    }

    static String message(String date, String eventName, int n) {
        return date + " 临时休市（" + eventName + "），你有 " + n
                + " 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日";
    }

    JsonNode positions() {
        Resp r = get(POSITIONS);
        assertThat(r.status()).as("GET positions -> %s", r.body()).isEqualTo(200);
        for (JsonNode p : r.body().get("positions")) {
            assertThat(p.has("expiresOnAdhocClosure")).as("expiresOnAdhocClosure present: %s", p).isTrue();
            assertThat(p.get("expiresOnAdhocClosure").isBoolean()).isTrue();
            assertThat(p.has("adhocClosureEventName")).as("adhocClosureEventName present (null, not omitted)").isTrue();
        }
        return r.body();
    }

    JsonNode position(String label) {
        JsonNode all = positions();
        for (JsonNode p : all.get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError("no position " + label + " in " + all);
    }

    /** "true|eventName" or "false|null". */
    String flag(String label) {
        JsonNode p = position(label);
        return p.get("expiresOnAdhocClosure").asBoolean() + "|" + text(p.get("adhocClosureEventName"));
    }

    /** "dte / remainingTradingDays / expirationStatus / expirable". */
    String days(String label) {
        JsonNode p = position(label);
        JsonNode rtd = p.get("remainingTradingDays");
        return p.get("dte").asInt() + " / " + (rtd.isNull() ? "null" : rtd.asInt()) + " / "
                + p.get("expirationStatus").asText() + " / " + p.get("expirable").asBoolean();
    }

    List<String> nonTradingDates(String from, String to) {
        Resp r = get(NTD + "?from=" + from + "&to=" + to);
        assertThat(r.status()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        r.body().get("days").forEach(d -> out.add(d.get("date").asText()));
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

    long close(String action, int contracts, String symbol, String expiration, String strike, String type,
               String net, String at) {
        ObjectNode n = trade(action, contracts, symbol, expiration, strike, type, net, at);
        n.remove("multiplier");
        Resp r = post(TRADES + "/closes", n.toString());
        assertThat(r.status()).as("close %s -> %s", n, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
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

    void deleteTrade(long id) {
        Resp r = exchange(HttpMethod.DELETE, TRADES + "/" + id, null);
        assertThat(r.status()).as("DELETE trade %s -> %s", id, r.body()).isEqualTo(204);
    }

    long newAccount(String name) {
        String saved = accountId;
        accountId = null;
        Resp r = post("/api/accounts", "{\"name\":\"" + name + "\"}");
        accountId = saved;
        assertThat(r.status()).as("create account -> %s", r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void as(long account, Runnable r) {
        String saved = accountId;
        accountId = String.valueOf(account);
        try {
            r.run();
        } finally {
            accountId = saved;
        }
    }

    // =================================================================================================== scenario

    /**
     * Stage 1: before any data, account C's 2025-01-09 put; then the first fetch 2026-11-30 08:00 NY (AC 1).
     */
    void stage1FirstFetch() {
        setNow(T_PRE);
        accountC = newAccount("账户C");
        as(accountC, () -> {
            deposit("10000.00", "2025-01-02T10:00");
            long id = okTrade(trade("STO", 1, "QRTV", "2025-01-10", "40", "PUT", "80.00", "2025-01-06T10:00"));
            assertThat(jdbc.update("UPDATE option_trade SET expiration_date = CAST(? AS DATE) WHERE id = ?",
                    "2025-01-09", id)).isEqualTo(1);
        });
        fetchAt(T_FETCH1, scenarioData());
    }

    /** Stage 2: 2026-11-30 10:00 NY trades of accounts A, B, C. */
    void stage2Trades() {
        setNow(T_TRADE);
        deposit("20000.00", "2026-11-25T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "120.00", "2026-11-30T09:40"));
        okTrade(trade("BTO", 2, "IXQZ", "2026-12-04", "9", "CALL", "60.00", "2026-11-30T09:41"));
        okTrade(trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "150.00", "2026-11-30T09:42"));

        accountB = newAccount("账户B");
        as(accountB, () -> {
            deposit("10000.00", "2026-11-25T10:00");
            okTrade(trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "150.00", "2026-11-30T09:45"));
        });
        as(accountC, () ->
                okTrade(trade("STO", 1, "IXQZ", "2026-12-04", "8", "PUT", "30.00", "2026-11-30T09:50")));
    }

    /** Stage 3: 2026-12-01 08:00 NY fetch with 2026-12-04; then "today" = 2026-12-01 10:00 NY (AC 2 first half). */
    void stage3SecondFetch() {
        fetchAt(T_FETCH2, withDec4());
        setNow(T_DAY2);
    }

    /** State after AC 2 (= the "Given" of AC 3–9). */
    void givenAc2() {
        stage1FirstFetch();
        stage2Trades();
        stage3SecondFetch();
    }

    static final String C2025 = "2025-01-09|" + MOURNING_2025 + "|2026-11-30|ACTIVE|null";
    static final String C1204 = "2026-12-04|" + MOURNING_2026 + "|2026-12-01|ACTIVE|null";

    // =================================================================================================== AC 1–2

    @Test
    @DisplayName("AC1 新库第一次拉取：只记录 2025-01-09（检测日期 2026-11-30、有效）；感恩节 11-26、提前收盘 11-27 不记录")
    void ac1FirstFetch() {
        assertThat(closures()).as("new database").isEmpty();
        stage1FirstFetch();
        assertThat(closures()).containsExactly(C2025);
        assertThat(closures("?status=ACTIVE")).containsExactly(C2025);
        assertThat(closures("?status=REVOKED")).isEmpty();
        // FEAT-009 unaffected: 2025-01-09 is a (Finnhub) non-trading day, 11-27 early close is a trading day
        assertThat(nonTradingDates("2025-01-06", "2025-01-10")).containsExactly("2025-01-09");
        assertThat(nonTradingDates("2026-11-23", "2026-11-27")).containsExactly("2026-11-26");
    }

    @Test
    @DisplayName("AC2 12-01 拉取新增 2026-12-04（检测日期 12-01）；12-02 再拉到同样数据不新增，检测日期仍为 12-01")
    void ac2SecondFetchAndRepeat() {
        stage1FirstFetch();
        stage2Trades();
        stage3SecondFetch();
        assertThat(closures()).containsExactly(C2025, C1204);

        fetchAt(T_FETCH3, withDec4());
        assertThat(closures()).containsExactly(C2025, C1204);
        assertThat(closures("?status=ACTIVE")).containsExactly(C2025, C1204);
    }

    // =================================================================================================== AC 3–4

    @Test
    @DisplayName("AC3 账户 A：一条提醒（2026-12-04，2 个持仓，整行文字）；P1、P2 expiresOnAdhocClosure=true + eventName，P3 false/null")
    void ac3AlertsAndFlags() {
        givenAc2();
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");
        JsonNode a = get(ALERTS).body().get("alerts").get(0);
        assertThat(a.get("message").asText()).isEqualTo(
                "2026-12-04 临时休市（National Day of Mourning），你有 2 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日");

        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);
        assertThat(flag(P2)).isEqualTo("true|" + MOURNING_2026);
        assertThat(flag(P3)).isEqualTo("false|null");
    }

    @Test
    @DisplayName("AC4 账户 B（没有 12-04 持仓）：alerts 为 []，持仓都不带标志；临时休市记录全系统一份，带不带账户头都一样")
    void ac4AccountBHasNoAlert() {
        givenAc2();
        as(accountB, () -> {
            assertThat(alerts()).isEmpty();
            assertThat(flag(P3)).isEqualTo("false|null");
        });
        // closures ignore X-Account-Id
        Resp withHeader = exchangeAs(String.valueOf(accountB), HttpMethod.GET, CLOSURES, null);
        assertThat(withHeader.status()).isEqualTo(200);
        assertThat(withHeader.body().get("closures")).hasSize(2);
        Resp withBogusHeader = exchangeAs("999", HttpMethod.GET, CLOSURES, null);
        assertThat(withBogusHeader.status()).as("header is ignored, not validated").isEqualTo(200);
    }

    // =================================================================================================== AC 5

    @Test
    @DisplayName("AC5 P2 STC 2 张平完 → 1 个持仓；P1 BTC 1 张 → 提醒消失（Q129）；删除 BTC 后 P1 重新出现 → 再次计入")
    void ac5ClosingClearsAlert() {
        givenAc2();
        close("STC", 2, "IXQZ", "2026-12-04", "9", "CALL", "80.00", "2026-12-01T09:45");
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|1");
        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);

        long btc = close("BTC", 1, "QRTV", "2026-12-04", "50", "PUT", "40.00", "2026-12-01T09:50");
        assertThat(alerts()).isEmpty();
        // the closure record itself is unchanged
        assertThat(closures()).containsExactly(C2025, C1204);

        // edge: deleting the BTC brings P1 back → counted again (real-time, nothing stored)
        deleteTrade(btc);
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|1");
        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);
    }

    // =================================================================================================== AC 6

    @Test
    @DisplayName("AC6 账户 C：两行，依次 2025-01-09（1 个，持仓早已过期未处理）、2026-12-04（1 个）")
    void ac6AccountCTwoAlerts() {
        givenAc2();
        as(accountC, () -> {
            assertThat(alerts()).containsExactly(
                    "2025-01-09|" + MOURNING_2025 + "|1",
                    "2026-12-04|" + MOURNING_2026 + "|1");
            JsonNode old = position(OLD);
            assertThat(old.get("expirationStatus").asText()).isEqualTo("EXPIRED");
            assertThat(flag(OLD)).isEqualTo("true|" + MOURNING_2025);
            assertThat(flag(P4)).isEqualTo("true|" + MOURNING_2026);
        });
        // account isolation: A sees only its own count for 2026-12-04, not A + C
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");
    }

    // =================================================================================================== AC 7

    @Test
    @DisplayName("AC7 / Q124 DTE、剩余交易日、作废按钮按录入的到期日 2026-12-04：12-01 DTE 3 剩余 2 不可作废；12-04 17:45 今天到期、可作废")
    void ac7NothingElseChanges() {
        givenAc2();
        assertThat(days(P1)).isEqualTo("3 / 2 / ACTIVE / false");
        assertThat(days(P2)).isEqualTo("3 / 2 / ACTIVE / false");
        assertThat(days(P3)).isEqualTo("17 / 12 / ACTIVE / false");

        setNow("2026-12-04T22:45:00Z"); // 17:45 New York
        assertThat(days(P1)).isEqualTo("0 / 0 / EXPIRES_TODAY / true");
        assertThat(days(P2)).isEqualTo("0 / 0 / EXPIRES_TODAY / true");
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");
    }

    // =================================================================================================== AC 8–9

    @Test
    @DisplayName("AC8 / Q127 12-02 拉取中 2026-12-04 被删除 → REVOKED（revokedOn 12-02）；提醒、标志消失；12-04 可开仓")
    void ac8Revoked() {
        givenAc2();
        fetchAt(T_FETCH3, scenarioData());
        setNow(T_DAY3);
        assertThat(closures()).containsExactly(C2025, "2026-12-04|" + MOURNING_2026 + "|2026-12-01|REVOKED|2026-12-02");
        assertThat(closures("?status=REVOKED"))
                .containsExactly("2026-12-04|" + MOURNING_2026 + "|2026-12-01|REVOKED|2026-12-02");
        assertThat(closures("?status=ACTIVE")).containsExactly(C2025);

        assertThat(alerts()).isEmpty();
        assertThat(flag(P1)).isEqualTo("false|null");
        assertThat(flag(P2)).isEqualTo("false|null");
        as(accountC, () -> assertThat(alerts()).containsExactly("2025-01-09|" + MOURNING_2025 + "|1"));

        assertThat(nonTradingDates("2026-11-30", "2026-12-04")).isEmpty();
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "48", "PUT", "60.00", "2026-12-02T09:50"));
    }

    @Test
    @DisplayName("AC9 12-02 拉取失败（HTTP 500）：记录不变、提醒照常；status 显示 \"上次成功：2026-12-01 08:00 美东\"")
    void ac9FailedFetchKeepsRecords() {
        givenAc2();
        setNow(T_FETCH3);
        JsonNode s = fetchRaw("{\"httpStatus\":500,\"body\":null}");
        assertThat(s.get("lastAttemptResult").asText()).isEqualTo("HTTP_ERROR");
        setNow(T_DAY3);

        assertThat(closures()).containsExactly(C2025, C1204);
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");
        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);

        Resp st = get(STATUS);
        assertThat(st.status()).isEqualTo(200);
        assertThat(st.body().get("stale").asBoolean()).isTrue();
        assertThat(text(st.body().get("warning"))).isEqualTo("休市日数据未更新（上次成功：2026-12-01 08:00 美东）");
    }

    // =================================================================================================== detection rules

    @Test
    @DisplayName("检测规则 2（覆盖年份但之前没有）：第一次拉取 2026 没有感恩节 → 不记录；之后出现 → 记录（即使 Jollyday 产生该日）")
    void detectCoveredYearButAbsentBefore() {
        fetchAt(T_FETCH1, without(scenarioData(), "2026-11-26"));
        assertThat(closures()).containsExactly(C2025);

        fetchAt(T_FETCH2, scenarioData());
        assertThat(closures()).containsExactly(C2025, "2026-11-26|Thanksgiving Day|2026-12-01|ACTIVE|null");
    }

    @Test
    @DisplayName("检测规则 1：新年份第一次出现时，规则产生的假日不记录，规则不产生的工作日休市记录")
    void detectNewYearOnlyByRules() {
        fetchAt(T_FETCH1, scenarioData());
        // 2028 appears for the first time: all its holidays are produced by the rules → nothing new;
        // 2028-03-03 (Friday) is not produced by the rules → ad-hoc closure
        fetchAt(T_FETCH2, plus(years(2023, 2028), closed("Ad hoc closure", "2028-03-03")));
        assertThat(closures()).containsExactly(C2025, "2028-03-03|Ad hoc closure|2026-12-01|ACTIVE|null");
    }

    @Test
    @DisplayName("检测：Jollyday observed 日期（2026-07-03、2023-01-02、2027-12-24）不当作临时休市")
    void detectObservedHolidaysAreNotAdhoc() {
        fetchAt(T_FETCH1, scenarioData());
        List<String> dates = closures().stream().map(s -> s.substring(0, 10)).toList();
        assertThat(dates)
                .doesNotContain("2026-07-03", "2023-01-02", "2027-12-24")
                .containsExactly("2025-01-09");
    }

    @Test
    @DisplayName("检测：周末的全天休市不记录（第一次拉取、以及已覆盖年份中新出现）")
    void detectWeekendSkipped() {
        fetchAt(T_FETCH1, plus(scenarioData(), closed("Weekend closure", "2026-12-05")));
        assertThat(closures()).containsExactly(C2025);
        // covered year, absent before, but Sunday → still not recorded
        fetchAt(T_FETCH2, plus(scenarioData(), closed("Weekend closure", "2026-12-05"),
                closed("Weekend closure", "2026-12-06")));
        assertThat(closures()).containsExactly(C2025);
    }

    @Test
    @DisplayName("检测：提前收盘不算（第一次拉取和之后新出现都不记录）；之前提前收盘的日期改为全天休市 → 记录")
    void detectEarlyCloseSkipped() {
        fetchAt(T_FETCH1, plus(scenarioData(), early("Early close", "2026-12-07")));
        assertThat(closures()).containsExactly(C2025);
        fetchAt(T_FETCH2, plus(scenarioData(), early("Early close", "2026-12-07"), early("Early close", "2026-12-08")));
        assertThat(closures()).containsExactly(C2025);

        // 2026-12-24 was stored as an early close; now a full-day closure
        List<H> rows = new ArrayList<>(without(scenarioData(), "2026-12-24"));
        rows.add(closed("Christmas Eve", "2026-12-24"));
        fetchAt(T_FETCH3, rows);
        assertThat(closures()).containsExactly(C2025, "2026-12-24|Christmas Eve|2026-12-02|ACTIVE|null");
    }

    @Test
    @DisplayName("检测：失败的拉取（无 key、HTTP 500、格式不对）不检测、记录不变；之后成功的拉取再检测，检测日期为那次")
    void detectFailedFetchNoDetection() {
        fetchAt(T_FETCH1, scenarioData());

        setNow(T_FETCH2);
        assertThat(fetchRaw("{\"noKey\":true}").get("lastAttemptResult").asText()).isEqualTo("NO_KEY");
        assertThat(fetchRaw("{\"httpStatus\":500,\"body\":null}").get("lastAttemptResult").asText())
                .isEqualTo("HTTP_ERROR");
        // INVALID_RESPONSE that would otherwise contain 2026-12-04
        ObjectNode body = finnhubBody(withDec4());
        ObjectNode bad = ((ArrayNode) body.get("data")).addObject();
        bad.put("eventName", "Broken");
        bad.put("atDate", "2026-02-30");
        bad.put("tradingHour", "");
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", body);
        assertThat(fetchRaw(req.toString()).get("lastAttemptResult").asText()).isEqualTo("INVALID_RESPONSE");
        assertThat(closures()).containsExactly(C2025);

        fetchAt(T_FETCH3, withDec4());
        assertThat(closures()).containsExactly(C2025, "2026-12-04|" + MOURNING_2026 + "|2026-12-02|ACTIVE|null");
    }

    @Test
    @DisplayName("检测：一次失败的拉取发生在新库上（从未成功）→ 没有任何记录")
    void detectFailedFirstFetch() {
        setNow(T_FETCH1);
        fetchRaw("{\"noKey\":true}");
        fetchRaw("{\"httpStatus\":503,\"body\":null}");
        assertThat(closures()).isEmpty();
    }

    @Test
    @DisplayName("detectedOn 取拉取时的美东日期：12-02 03:30Z（= 12-01 22:30 美东）拉到 → 2026-12-01")
    void detectedOnIsNewYorkDate() {
        setNow("2026-11-30T04:30:00Z"); // 2026-11-29 23:30 New York
        fetchOk(scenarioData());
        assertThat(closures()).containsExactly("2025-01-09|" + MOURNING_2025 + "|2026-11-29|ACTIVE|null");
        setNow("2026-12-02T03:30:00Z"); // 2026-12-01 22:30 New York
        fetchOk(withDec4());
        assertThat(closures()).containsExactly("2025-01-09|" + MOURNING_2025 + "|2026-11-29|ACTIVE|null",
                "2026-12-04|" + MOURNING_2026 + "|2026-12-01|ACTIVE|null");
    }

    @Test
    @DisplayName("再次拉到时 eventName 更新为本次响应（与 non-trading-days 一致），detectedOn 不变")
    void eventNameUpdatedDetectedOnKept() {
        givenAc2();
        fetchAt(T_FETCH3, plus(scenarioData(), closed("Day of Mourning (renamed)", "2026-12-04")));
        setNow(T_DAY3);
        assertThat(closures()).containsExactly(C2025, "2026-12-04|Day of Mourning (renamed)|2026-12-01|ACTIVE|null");
        assertThat(alerts()).containsExactly("2026-12-04|Day of Mourning (renamed)|2");
        assertThat(flag(P1)).isEqualTo("true|Day of Mourning (renamed)");
    }

    // =================================================================================================== Q127

    @Test
    @DisplayName("Q127 提前收盘替换全天休市 → REVOKED（revokedOn = 那次拉取日期），提醒消失")
    void q127EarlyCloseRevokes() {
        givenAc2();
        fetchAt(T_FETCH3, plus(scenarioData(), early(MOURNING_2026, "2026-12-04")));
        setNow(T_DAY3);
        assertThat(closures()).containsExactly(C2025, "2026-12-04|" + MOURNING_2026 + "|2026-12-01|REVOKED|2026-12-02");
        assertThat(alerts()).isEmpty();
        assertThat(flag(P1)).isEqualTo("false|null");
    }

    @Test
    @DisplayName("Q127 撤销后又出现 → 恢复 ACTIVE、revokedOn null、detectedOn 保持第一次 12-01；提醒回来")
    void q127Reinstated() {
        givenAc2();
        fetchAt(T_FETCH3, scenarioData());
        assertThat(closures()).containsExactly(C2025, "2026-12-04|" + MOURNING_2026 + "|2026-12-01|REVOKED|2026-12-02");

        fetchAt(T_FETCH4, withDec4());
        setNow("2026-12-03T15:00:00Z");
        assertThat(closures()).containsExactly(C2025, C1204);
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");
        assertThat(flag(P2)).isEqualTo("true|" + MOURNING_2026);
    }

    @Test
    @DisplayName("Q127 年份滚出窗口（响应不含 2025）：2025-01-09 仍 ACTIVE、revokedOn null，账户 C 仍提醒")
    void q127YearDropsOutStaysActive() {
        givenAc2();
        fetchAt(T_FETCH3, plus(years(2026, 2027), closed(MOURNING_2026, "2026-12-04")));
        setNow(T_DAY3);
        assertThat(closures()).containsExactly(C2025, C1204);
        assertThat(nonTradingDates("2025-01-06", "2025-01-10")).as("stored 2025 data kept (FEAT-009)")
                .containsExactly("2025-01-09");
        as(accountC, () -> assertThat(alerts()).containsExactly(
                "2025-01-09|" + MOURNING_2025 + "|1",
                "2026-12-04|" + MOURNING_2026 + "|1"));
    }

    // =================================================================================================== affected count

    @Test
    @DisplayName("受影响持仓：同一合约多批次算 1 个；过期未处理仍算；行权、被指派、作废后不算；删除指派 / 作废后再次计入；股票持仓不算")
    void affectedCountOnlyOpenPositions() {
        stage1FirstFetch();
        stage2Trades();
        // second lot of P1, opened 11-30 while 12-04 was still a trading day (afterwards Q126 rejects it)
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "110.00", "2026-11-30T09:55"));
        stage3SecondFetch();
        assertThat(alerts()).as("2 lots of one contract = 1 position").containsExactly(
                "2026-12-04|" + MOURNING_2026 + "|2");

        // 2026-12-05 (Sat) 10:00 NY: both expired and unprocessed → still counted
        setNow("2026-12-05T15:00:00Z");
        assertThat(position(P1).get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|2");

        // exercise P2 (long call)
        ObjectNode exr = contractBody("IXQZ", "2026-12-04", "9", "CALL", 2);
        exr.put("exerciseDate", "2026-12-04");
        exr.put("exerciseFee", "0.00");
        exr.putNull("note");
        createdId(post(TRADES + "/exercises", exr.toString()), "EXR");
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|1");

        // assignment of P1 (both lots) → 200 QRTV shares; stock positions do not count
        ObjectNode asg = contractBody("QRTV", "2026-12-04", "50", "PUT", 2);
        asg.put("assignmentDate", "2026-12-04");
        asg.put("assignmentFee", "0.00");
        asg.putNull("note");
        long asgId = createdId(post(TRADES + "/assignments", asg.toString()), "ASG");
        assertThat(alerts()).isEmpty();

        // deleting the assignment brings P1 back
        deleteTrade(asgId);
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|1");

        // expire worthless → gone; delete the EXP → back again
        long expId = createdId(post(TRADES + "/expirations", contractBody("QRTV", "2026-12-04", "50", "PUT", 2)
                .toString()), "EXP");
        assertThat(alerts()).isEmpty();
        assertThat(flag(P3)).isEqualTo("false|null");
        deleteTrade(expId);
        assertThat(alerts()).containsExactly("2026-12-04|" + MOURNING_2026 + "|1");
        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);
    }

    @Test
    @DisplayName("受影响持仓：批量作废也会让提醒消失")
    void affectedCountBatchExpire() {
        givenAc2();
        setNow("2026-12-04T22:45:00Z"); // 17:45 New York
        ObjectNode batch = json.createObjectNode();
        batch.putArray("items")
                .add(contractBody("QRTV", "2026-12-04", "50", "PUT", 1))
                .add(contractBody("IXQZ", "2026-12-04", "9", "CALL", 2));
        Resp b = post(TRADES + "/expirations/batch", batch.toString());
        assertThat(b.status()).as("batch -> %s", b.body()).isEqualTo(201);
        assertThat(alerts()).isEmpty();
        // other accounts unaffected
        as(accountC, () -> assertThat(alerts()).hasSize(2));
    }

    // =================================================================================================== endpoints

    @Test
    @DisplayName("GET adhoc-closures：status 只接受 ACTIVE / REVOKED（区分大小写），其它值 400 BAD_REQUEST field status")
    void closuresInvalidStatus() {
        givenAc2();
        for (String bad : List.of("active", "FOO", "Revoked", "ALL")) {
            Resp r = exchangeAs(null, HttpMethod.GET, CLOSURES + "?status=" + bad, null);
            assertErrorShape(r, 400, "BAD_REQUEST", CLOSURES);
            assertThat(detailFields(r)).as("status=%s", bad).containsExactly("status");
        }
    }

    @Test
    @DisplayName("GET adhoc-closure-alerts 账户错误：无头 400 ACCOUNT_REQUIRED；非数字 400 BAD_REQUEST；不存在 404 ACCOUNT_NOT_FOUND")
    void alertsAccountErrors() {
        givenAc2();
        assertErrorShape(exchangeAs(null, HttpMethod.GET, ALERTS, null), 400, "ACCOUNT_REQUIRED", ALERTS);
        assertErrorShape(exchangeAs("abc", HttpMethod.GET, ALERTS, null), 400, "BAD_REQUEST", ALERTS);
        Resp nf = exchangeAs("999", HttpMethod.GET, ALERTS, null);
        assertErrorShape(nf, 404, "ACCOUNT_NOT_FOUND", ALERTS);
    }

    @Test
    @DisplayName("没有临时休市或没有受影响持仓：alerts 为 []；新库 closures 为 []；reset 清空记录")
    void emptyCasesAndReset() {
        assertThat(alerts()).isEmpty();
        assertThat(closures()).isEmpty();
        givenAc2();
        assertThat(closures()).hasSize(2);
        reset();
        accountId = "1";
        assertThat(closures()).as("reset empties adhoc_closure").isEmpty();
        assertThat(alerts()).isEmpty();
    }

    @Test
    @DisplayName("没有临时休市时持仓字段为 false / null（字段存在、不省略）")
    void positionsFieldsWithoutClosure() {
        stage1FirstFetch();
        stage2Trades();
        assertThat(flag(P1)).isEqualTo("false|null");
        assertThat(flag(P2)).isEqualTo("false|null");
        assertThat(alerts()).isEmpty();
        // account C already has the 2025-01-09 alert before 12-04 is detected
        as(accountC, () -> assertThat(alerts()).containsExactly("2025-01-09|" + MOURNING_2025 + "|1"));
    }
}
