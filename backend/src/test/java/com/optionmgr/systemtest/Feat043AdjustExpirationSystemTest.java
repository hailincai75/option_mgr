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
import org.springframework.test.context.ActiveProfiles;

/**
 * QA system tests for FEAT-043 临时休市时调整合约到期日, end to end over REST on a random port (never 8080).
 *
 * <p>Same fakes as {@code Feat042AdhocClosureSystemTest}: profiles {@code test} + {@code e2e}; Finnhub fetches go
 * through {@code POST /api/test-support/trading-calendar/fetch} (FEAT-042 detection included); "now" is fixed with
 * {@code PUT /api/test-support/clock}; {@code POST /api/test-support/reset} before and after every test.
 *
 * <p>Scenario (requirement AC): first fetch 2026-11-30 08:00 New York (2023–2027, with 2025-01-09, without
 * 2026-12-04); trades entered 2026-11-30 10:00 while 2026-12-04 is still a trading day; second fetch 2026-12-01 08:00
 * adds 2026-12-04 National Day of Mourning; "today" = 2026-12-01 10:00 New York. Account A (default account 1):
 * P1 = QRTV 2026-12-04 50P: STO 1 2026-11-20 10:00 120.00, STO 1 2026-11-24 10:00 100.00, BTC 1 2026-11-25 10:00
 * 40.00 (closes lot 1, +80.00); P2 = BTO 2 IXQZ 2026-12-04 9C 60.00; P3 = STO 1 QRTV 2026-12-18 45P.
 * Accounts C / D: 2025-01-09 puts seeded with the extended e2e {@code legacy-option-trades} (Q126 rejects a normal
 * opening on 2025-01-09). All tickers are fictional.
 *
 * <p>The replay error codes of check 10 (FEAT-005 / FEAT-039, prefix {@code 调整后}) are defensive per contract
 * ("正常数据下不会出现") and cannot be provoked over REST; they are left to the backend unit tests of
 * {@code ExpirationAdjustmentRules}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
@DisplayName("FEAT-043 临时休市时调整到期日（系统测试：假 Finnhub + 假时钟）")
class Feat043AdjustExpirationSystemTest {

    static final String ADJUST = "/api/option-trades/expiration-adjustments";
    static final String ALERTS = "/api/adhoc-closure-alerts";
    static final String FETCH = "/api/test-support/trading-calendar/fetch";
    static final String CLOCK = "/api/test-support/clock";
    static final String RESET = "/api/test-support/reset";
    static final String LEGACY = "/api/test-support/legacy-option-trades";
    static final String TRADES = "/api/option-trades";
    static final String POSITIONS = "/api/option-positions";
    static final String CLOSED = "/api/closed-trades";
    static final String AUDIT = "/api/audit-log";
    static final String CASH_SUMMARY = "/api/cash/summary";
    static final String LEDGER = "/api/cash/ledger?page=1&size=100";

    static final String P1 = "QRTV 2026-12-04 50 P";
    static final String P1_NEW = "QRTV 2026-12-03 50 P";
    static final String P2 = "IXQZ 2026-12-04 9 C";
    static final String P2_NEW = "IXQZ 2026-12-03 9 C";
    static final String P3 = "QRTV 2026-12-18 45 P";

    static final String MOURNING_2025 = "National Mourning Day";
    static final String MOURNING_2026 = "National Day of Mourning";

    /** 2026-11-30 08:00 New York: first fetch. */
    static final String T_FETCH1 = "2026-11-30T13:00:00Z";
    /** 2026-11-30 10:00 New York: scenario trades (2026-12-04 still a trading day). */
    static final String T_TRADE = "2026-11-30T15:00:00Z";
    /** 2026-12-01 08:00 New York: fetch with 2026-12-04. */
    static final String T_FETCH2 = "2026-12-01T13:00:00Z";
    /** 2026-12-01 10:00 New York: "today" of the AC. */
    static final String T_DAY2 = "2026-12-01T15:00:00Z";

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    /** X-Account-Id of the business calls; null = omit. */
    String accountId = "1";

    long p1Lot1;
    long p1Lot2;
    long p1Btc;
    long p2;
    long p3;

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

    record H(String name, String date, String hours) {
    }

    static H closed(String name, String date) {
        return new H(name, date, "");
    }

    static H early(String name, String date) {
        return new H(name, date, "09:30-13:00");
    }

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
    }

    static List<H> scenarioData() {
        List<H> out = new ArrayList<>();
        FINNHUB.values().forEach(out::addAll);
        return out;
    }

    static List<H> withDec4() {
        return plus(scenarioData(), closed(MOURNING_2026, "2026-12-04"));
    }

    @SafeVarargs
    static List<H> plus(List<H> base, H... extra) {
        List<H> out = new ArrayList<>(base);
        out.addAll(List.of(extra));
        return out;
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

    void fetchAt(String instant, List<H> rows) {
        setNow(instant);
        ObjectNode req = json.createObjectNode();
        req.put("httpStatus", 200);
        req.set("body", finnhubBody(rows));
        Resp r = exchangeAs(null, HttpMethod.POST, FETCH, req.toString());
        assertThat(r.status()).as("POST %s -> %s", FETCH, r.body()).isEqualTo(200);
        assertThat(r.body().get("lastAttemptResult").asText()).as("fetch %s", r.body()).isEqualTo("SUCCESS");
    }

    // =================================================================================================== clock/reset

    void reset() {
        Resp r = exchangeAs(null, HttpMethod.POST, RESET, null);
        assertThat(r.status()).as("POST %s", RESET).isEqualTo(204);
    }

    void setNow(String instant) {
        Instant.parse(instant);
        Resp r = exchangeAs(null, HttpMethod.PUT, CLOCK, "{\"now\":\"" + instant + "\"}");
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
        assertThat(b.get("code").asText()).as("code, body=%s", b).isEqualTo(code);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("path").asText()).isEqualTo(path);
        assertThat(b.get("message").asText()).isNotBlank();
        Instant.parse(b.get("timestamp").asText());
        assertThat(b.get("details").isArray()).isTrue();
    }

    /** 422 business rejection: code, exact message, details []. */
    void assert422(Resp r, String code, String message) {
        assertErrorShape(r, 422, code, ADJUST);
        assertThat(r.body().get("message").asText()).isEqualTo(message);
        assertThat(r.body().get("details")).as("422 details").isEmpty();
    }

    static List<String> detailFields(Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("details").forEach(d -> out.add(d.get("field").asText()));
        return out;
    }

    static String detailMessage(Resp r, String field) {
        for (JsonNode d : r.body().get("details")) {
            if (d.get("field").asText().equals(field)) {
                return d.get("message").asText();
            }
        }
        throw new AssertionError("no detail for " + field + " in " + r.body());
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    static List<Long> longs(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asLong()));
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

    /** Extended e2e legacy opening (FEAT-043: BTO / STO, CALL / PUT), skips only the Q126 trading-day check. */
    long legacyTrade(ObjectNode body) {
        Resp r = post(LEGACY, body.toString());
        assertThat(r.status()).as("POST %s %s -> %s", LEGACY, body, r.body()).isEqualTo(201);
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
        Resp r = exchangeAs(null, HttpMethod.POST, "/api/accounts", "{\"name\":\"" + name + "\"}");
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

    // =================================================================================================== adjust

    ObjectNode adjustBody(String symbol, String expiration, String strike, String type, String newExpiration) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        if (newExpiration != null) {
            n.put("newExpirationDate", newExpiration);
        }
        n.putNull("note");
        return n;
    }

    Resp adjust(String symbol, String expiration, String strike, String type, String newExpiration) {
        return post(ADJUST, adjustBody(symbol, expiration, strike, type, newExpiration).toString());
    }

    JsonNode adjustOk(ObjectNode body) {
        Resp r = post(ADJUST, body.toString());
        assertThat(r.status()).as("POST %s %s -> %s", ADJUST, body, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrder("auditId", "symbol", "strike", "optionType",
                "oldExpirationDate", "newExpirationDate", "contractLabel", "tradeIds", "position");
        return r.body();
    }

    JsonNode adjustOk(String symbol, String expiration, String strike, String type, String newExpiration) {
        return adjustOk(adjustBody(symbol, expiration, strike, type, newExpiration));
    }

    // =================================================================================================== reads

    static final List<String> NEW_POSITION_FIELDS = List.of("expirationAdjustable", "expirationAdjustmentRecordCount",
            "defaultNewExpirationDate", "newExpirationDateMin", "newExpirationDateMax");

    JsonNode positions() {
        Resp r = get(POSITIONS);
        assertThat(r.status()).as("GET positions -> %s", r.body()).isEqualTo(200);
        for (JsonNode p : r.body().get("positions")) {
            for (String f : NEW_POSITION_FIELDS) {
                assertThat(p.has(f)).as("%s present (null, not omitted): %s", f, p).isTrue();
            }
            assertThat(p.get("expirationAdjustable").isBoolean()).isTrue();
            assertThat(p.get("expirationAdjustmentRecordCount").isInt()).isTrue();
        }
        return r.body();
    }

    JsonNode findPosition(String label) {
        for (JsonNode p : positions().get("positions")) {
            if (p.get("contractLabel").asText().equals(label)) {
                return p;
            }
        }
        return null;
    }

    JsonNode position(String label) {
        JsonNode p = findPosition(label);
        assertThat(p).as("position %s in %s", label, positions()).isNotNull();
        return p;
    }

    /** "adjustable|count|default|min|max". */
    String adjFields(String label) {
        JsonNode p = position(label);
        return p.get("expirationAdjustable").asBoolean() + "|" + p.get("expirationAdjustmentRecordCount").asInt()
                + "|" + text(p.get("defaultNewExpirationDate")) + "|" + text(p.get("newExpirationDateMin"))
                + "|" + text(p.get("newExpirationDateMax"));
    }

    static final String NOT_ADJUSTABLE = "false|0|null|null|null";

    /** "true|eventName" or "false|null". */
    String flag(String label) {
        JsonNode p = position(label);
        return p.get("expiresOnAdhocClosure").asBoolean() + "|" + text(p.get("adhocClosureEventName"));
    }

    /** Alerts as "date|count". */
    List<String> alerts() {
        Resp r = get(ALERTS);
        assertThat(r.status()).as("GET %s -> %s", ALERTS, r.body()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        for (JsonNode a : r.body().get("alerts")) {
            int n = a.get("affectedPositionCount").asInt();
            assertThat(a.get("message").asText()).contains("你有 " + n + " 个持仓在该日到期");
            out.add(a.get("date").asText() + "|" + n);
        }
        return out;
    }

    JsonNode tradesList() {
        Resp r = get(TRADES + "?page=1&size=100");
        assertThat(r.status()).as("GET trades -> %s", r.body()).isEqualTo(200);
        return r.body().get("items");
    }

    JsonNode tradeRow(long id) {
        for (JsonNode t : tradesList()) {
            if (t.get("id").asLong() == id) {
                return t;
            }
        }
        throw new AssertionError("no trade " + id);
    }

    JsonNode closedRows() {
        Resp r = get(CLOSED + "?page=1&size=100");
        assertThat(r.status()).as("GET closed -> %s", r.body()).isEqualTo(200);
        return r.body().get("items");
    }

    JsonNode closedRow(long id) {
        for (JsonNode t : closedRows()) {
            if (t.get("id").asLong() == id) {
                return t;
            }
        }
        throw new AssertionError("no closed row " + id);
    }

    JsonNode auditItems(String query) {
        Resp r = get(AUDIT + "?page=1&size=100" + query);
        assertThat(r.status()).as("GET audit %s -> %s", query, r.body()).isEqualTo(200);
        for (JsonNode a : r.body().get("items")) {
            assertThat(a.has("expirationAdjustment")).as("expirationAdjustment present on every item: %s", a).isTrue();
        }
        return r.body().get("items");
    }

    JsonNode cashSummary() {
        Resp r = get(CASH_SUMMARY);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode ledger() {
        Resp r = get(LEDGER);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    /** Everything visible in the current account: a rejection must leave all of it unchanged. */
    String snapshot() {
        return String.join("\n", tradesList().toString(), positions().toString(), cashSummary().toString(),
                ledger().toString(), auditItems("").toString(), closedRows().toString(), alerts().toString());
    }

    /** A trade row without the fields that the adjustment is allowed to change. */
    static JsonNode withoutContractFields(JsonNode trade) {
        ObjectNode c = trade.deepCopy();
        c.remove(List.of("expirationDate", "contractLabel", "hasAuditLog"));
        return c;
    }

    // =================================================================================================== scenario

    /**
     * AC scenario of account A. {@code extra} runs at 2026-11-30 10:00 New York before 2026-12-04 is detected (so
     * more 2026-12-04 openings are still accepted). Leaves the clock at 2026-12-01 10:00 New York.
     */
    void scenario(Runnable extra) {
        fetchAt(T_FETCH1, scenarioData());
        setNow(T_TRADE);
        deposit("30000.00", "2026-11-19T10:00");
        p1Lot1 = okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "120.00", "2026-11-20T10:00"));
        p1Lot2 = okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "100.00", "2026-11-24T10:00"));
        p1Btc = close("BTC", 1, "QRTV", "2026-12-04", "50", "PUT", "40.00", "2026-11-25T10:00");
        p2 = okTrade(trade("BTO", 2, "IXQZ", "2026-12-04", "9", "CALL", "60.00", "2026-11-30T09:41"));
        p3 = okTrade(trade("STO", 1, "QRTV", "2026-12-18", "45", "PUT", "150.00", "2026-11-30T09:42"));
        if (extra != null) {
            extra.run();
        }
        fetchAt(T_FETCH2, withDec4());
        setNow(T_DAY2);
    }

    void scenario() {
        scenario(null);
    }

    /** Account with a 2025-01-09 STO put (2024-12-20 10:00) via the extended legacy endpoint. */
    long accountWith2025Put(String name, String symbol, String strike, String net) {
        long id = newAccount(name);
        as(id, () -> {
            deposit("10000.00", "2024-12-19T10:00");
            legacyTrade(trade("STO", 1, symbol, "2025-01-09", strike, "PUT", net, "2024-12-20T10:00"));
        });
        return id;
    }

    List<Long> p1Ids() {
        return List.of(p1Lot1, p1Lot2, p1Btc);
    }

    // =================================================================================================== AC 1–2

    @Test
    @DisplayName("AC1/AC2 持仓新字段：P1 可调整、3 条记录、默认 12-03、范围 11-25..12-03；P2 1 条、范围 11-30..12-03；P3 不可调整（false/0/null）")
    void ac1ac2PositionFields() {
        scenario();
        assertThat(flag(P1)).isEqualTo("true|" + MOURNING_2026);
        assertThat(adjFields(P1)).isEqualTo("true|3|2026-12-03|2026-11-25|2026-12-03");
        assertThat(adjFields(P2)).isEqualTo("true|1|2026-12-03|2026-11-30|2026-12-03");
        assertThat(adjFields(P3)).isEqualTo(NOT_ADJUSTABLE);
    }

    @Test
    @DisplayName("没有临时休市时所有持仓 expirationAdjustable=false、记录数 0、三个日期 null（字段存在）")
    void fieldsWithoutClosure() {
        fetchAt(T_FETCH1, scenarioData());
        setNow(T_TRADE);
        deposit("30000.00", "2026-11-19T10:00");
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "120.00", "2026-11-20T10:00"));
        assertThat(adjFields(P1)).isEqualTo(NOT_ADJUSTABLE);
        Resp r = adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        assert422(r, "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");
    }

    // =================================================================================================== AC 3

    @Test
    @DisplayName("AC3 P1 调整为 12-03：响应（审计 id、合约、tradeIds 升序、新持仓）；持仓到期日 12-03、DTE 2、剩余交易日 2、冻结 5,000.00；标签与按钮消失；提醒 2 → 1")
    void ac3AdjustP1() {
        scenario();
        assertThat(alerts()).containsExactly("2026-12-04|2");
        JsonNode lotsBefore = position(P1).get("lots");
        JsonNode cashBefore = cashSummary();

        JsonNode res = adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        assertThat(res.get("auditId").isNumber()).isTrue();
        assertThat(res.get("symbol").asText()).isEqualTo("QRTV");
        assertThat(res.get("strike").asText()).isEqualTo("50");
        assertThat(res.get("optionType").asText()).isEqualTo("PUT");
        assertThat(res.get("oldExpirationDate").asText()).isEqualTo("2026-12-04");
        assertThat(res.get("newExpirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(res.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(longs(res.get("tradeIds"))).containsExactlyElementsOf(p1Ids()).isSorted();

        JsonNode pos = res.get("position");
        assertThat(pos.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(pos.get("expirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(pos.get("direction").asText()).isEqualTo("SHORT");
        assertThat(pos.get("contracts").asInt()).isEqualTo(1);
        assertThat(pos.get("frozenCash").asText()).isEqualTo("5000.00");
        assertThat(pos.get("dte").asInt()).isEqualTo(2);
        assertThat(pos.get("remainingTradingDays").asInt()).isEqualTo(2);
        assertThat(pos.get("expirationStatus").asText()).isEqualTo("ACTIVE");
        assertThat(pos.get("expiresOnAdhocClosure").asBoolean()).isFalse();
        assertThat(pos.get("adhocClosureEventName").isNull()).isTrue();
        for (String f : NEW_POSITION_FIELDS) {
            assertThat(pos.has(f)).as("response position has %s", f).isTrue();
        }
        // response position = GET /api/option-positions row
        assertThat(pos).isEqualTo(position(P1_NEW));

        assertThat(findPosition(P1)).as("old contract no longer a position").isNull();
        assertThat(flag(P1_NEW)).isEqualTo("false|null");
        assertThat(adjFields(P1_NEW)).isEqualTo(NOT_ADJUSTABLE);
        // Q130: lots, frozen, cash unchanged
        assertThat(position(P1_NEW).get("lots")).isEqualTo(lotsBefore);
        assertThat(cashSummary()).isEqualTo(cashBefore);
        // FEAT-042 alert 2 → 1 (Q129)
        assertThat(alerts()).containsExactly("2026-12-04|1");
        // other positions untouched
        assertThat(adjFields(P2)).isEqualTo("true|1|2026-12-03|2026-11-30|2026-12-03");
        assertThat(adjFields(P3)).isEqualTo(NOT_ADJUSTABLE);
    }

    @Test
    @DisplayName("Q130 金额不变：交易除到期日 / 合约文字 / hasAuditLog 外逐字段不变；现金汇总、流水金额与余额不变，流水说明改为新合约文字")
    void q130NothingButExpirationChanges() {
        scenario();
        JsonNode tradesBefore = tradesList();
        String ledgerBefore = ledger().toString();
        JsonNode cashBefore = cashSummary();
        assertThat(ledgerBefore).contains(P1);

        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");

        JsonNode tradesAfter = tradesList();
        assertThat(tradesAfter).hasSameSizeAs(tradesBefore);
        for (JsonNode before : tradesBefore) {
            long id = before.get("id").asLong();
            JsonNode after = tradeRow(id);
            assertThat(withoutContractFields(after)).as("trade %s", id).isEqualTo(withoutContractFields(before));
            if (p1Ids().contains(id)) {
                assertThat(after.get("expirationDate").asText()).isEqualTo("2026-12-03");
                assertThat(after.get("contractLabel").asText()).isEqualTo(P1_NEW);
            } else {
                assertThat(after).as("unrelated trade %s untouched", id).isEqualTo(before);
            }
        }
        assertThat(cashSummary()).isEqualTo(cashBefore);
        String ledgerAfter = ledger().toString();
        assertThat(ledgerAfter).doesNotContain(P1);
        assertThat(ledgerAfter).isEqualTo(ledgerBefore.replace(P1, P1_NEW));
    }

    // =================================================================================================== AC 4

    @Test
    @DisplayName("AC4 交易列表 3 条为 QRTV 2026-12-03 50 P 且 hasAuditLog；已平仓 BTC 仍 +80.00；审计 1 条 ADJUST_EXPIRATION（完整结构）")
    void ac4ListsAndAudit() {
        scenario();
        ObjectNode body = adjustBody("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        body.put("note", "  临时休市，交易所改为 12-03 到期  ");
        JsonNode res = adjustOk(body);

        for (long id : p1Ids()) {
            JsonNode t = tradeRow(id);
            assertThat(t.get("contractLabel").asText()).isEqualTo(P1_NEW);
            assertThat(t.get("hasAuditLog").asBoolean()).as("hasAuditLog of %s", id).isTrue();
        }
        assertThat(tradeRow(p2).get("hasAuditLog").asBoolean()).isFalse();
        assertThat(tradeRow(p3).get("hasAuditLog").asBoolean()).isFalse();

        JsonNode btc = closedRow(p1Btc);
        assertThat(btc.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(btc.get("expirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(btc.get("realizedPnl").asText()).isEqualTo("80.00");
        assertThat(btc.get("hasAuditLog").asBoolean()).isTrue();
        List<Long> covered = new ArrayList<>();
        btc.get("coveredLots").forEach(l -> covered.add(l.get("tradeId").asLong()));
        assertThat(covered).as("FIFO match unchanged").containsExactly(p1Lot1);

        JsonNode items = auditItems("");
        assertThat(items).hasSize(1);
        JsonNode a = items.get(0);
        assertThat(a.get("id").asLong()).isEqualTo(res.get("auditId").asLong());
        assertThat(a.get("operation").asText()).isEqualTo("ADJUST_EXPIRATION");
        assertThat(Instant.parse(a.get("operatedAt").asText())).isEqualTo(Instant.parse(T_DAY2));
        assertThat(a.get("objectType").asText()).isEqualTo("OPTION_TRADE");
        assertThat(a.get("objectId").asLong()).isEqualTo(p1Lot1);
        assertThat(a.get("summary").asText()).isEqualTo("调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）");

        JsonNode before = a.get("before");
        assertThat(fieldNames(before)).containsExactlyInAnyOrder("symbol", "expirationDate", "strike", "optionType",
                "contractLabel", "tradeIds");
        assertThat(before.get("expirationDate").asText()).isEqualTo("2026-12-04");
        assertThat(before.get("contractLabel").asText()).isEqualTo(P1);
        assertThat(before.get("strike").asText()).isEqualTo("50");
        assertThat(longs(before.get("tradeIds"))).containsExactlyElementsOf(p1Ids());
        JsonNode after = a.get("after");
        assertThat(fieldNames(after)).containsExactlyInAnyOrder("symbol", "expirationDate", "strike", "optionType",
                "contractLabel", "tradeIds");
        assertThat(after.get("expirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(after.get("contractLabel").asText()).isEqualTo(P1_NEW);
        assertThat(longs(after.get("tradeIds"))).containsExactlyElementsOf(p1Ids());

        assertThat(a.get("changes")).hasSize(1);
        JsonNode c = a.get("changes").get(0);
        assertThat(c.get("field").asText()).isEqualTo("expirationDate");
        assertThat(c.get("label").asText()).isEqualTo("到期日");
        assertThat(c.get("before").asText()).isEqualTo("2026-12-04");
        assertThat(c.get("after").asText()).isEqualTo("2026-12-03");
        assertThat(c.get("text").asText()).isEqualTo("到期日：2026-12-04 → 2026-12-03");
        assertThat(a.get("sideEffects").isArray()).isTrue();
        assertThat(a.get("sideEffects")).isEmpty();

        JsonNode ea = a.get("expirationAdjustment");
        assertThat(fieldNames(ea)).containsExactlyInAnyOrder("symbol", "strike", "optionType", "oldExpirationDate",
                "newExpirationDate", "adhocClosureEventName", "tradeIds", "note");
        assertThat(ea.get("symbol").asText()).isEqualTo("QRTV");
        assertThat(ea.get("strike").asText()).isEqualTo("50");
        assertThat(ea.get("optionType").asText()).isEqualTo("PUT");
        assertThat(ea.get("oldExpirationDate").asText()).isEqualTo("2026-12-04");
        assertThat(ea.get("newExpirationDate").asText()).isEqualTo("2026-12-03");
        assertThat(ea.get("adhocClosureEventName").asText()).isEqualTo(MOURNING_2026);
        assertThat(longs(ea.get("tradeIds"))).containsExactlyElementsOf(p1Ids());
        assertThat(ea.get("note").asText()).as("note trimmed").isEqualTo("临时休市，交易所改为 12-03 到期");
    }

    @Test
    @DisplayName("审计筛选 objectType=OPTION_TRADE&objectId=X：每条被调整记录都返回该调整；无关交易不返回；其它审计项 expirationAdjustment=null")
    void auditObjectIdFilter() {
        scenario();
        // an unrelated UPDATE audit on P3
        ObjectNode put = json.createObjectNode();
        put.put("contracts", 1);
        put.put("netAmount", "150.00");
        put.put("tradedAtLocal", "2026-11-30T09:42");
        put.put("multiplier", 100);
        put.put("note", "改备注");
        Resp u = exchange(HttpMethod.PUT, TRADES + "/" + p3, put.toString());
        assertThat(u.status()).as("PUT p3 -> %s", u.body()).isEqualTo(200);

        JsonNode res = adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        long auditId = res.get("auditId").asLong();

        for (long id : p1Ids()) {
            JsonNode items = auditItems("&objectType=OPTION_TRADE&objectId=" + id);
            assertThat(items).as("history of trade %s", id).hasSize(1);
            assertThat(items.get(0).get("id").asLong()).isEqualTo(auditId);
            assertThat(items.get(0).get("operation").asText()).isEqualTo("ADJUST_EXPIRATION");
            assertThat(items.get(0).get("objectId").asLong()).as("objectId = smallest trade id").isEqualTo(p1Lot1);
        }
        JsonNode p3Items = auditItems("&objectType=OPTION_TRADE&objectId=" + p3);
        assertThat(p3Items).hasSize(1);
        assertThat(p3Items.get(0).get("operation").asText()).isEqualTo("UPDATE");
        assertThat(p3Items.get(0).get("expirationAdjustment").isNull()).isTrue();
        assertThat(auditItems("&objectType=OPTION_TRADE&objectId=" + p2)).isEmpty();

        // all: newest first (same operatedAt → bigger id first)
        JsonNode all = auditItems("");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("operation").asText()).isEqualTo("ADJUST_EXPIRATION");
        assertThat(all.get(1).get("expirationAdjustment").isNull()).isTrue();

        // edge: deleting one adjusted record keeps the adjustment audit (tradeIds still lists it)
        deleteTrade(p1Btc);
        JsonNode btcHistory = auditItems("&objectType=OPTION_TRADE&objectId=" + p1Btc);
        List<String> ops = new ArrayList<>();
        btcHistory.forEach(n -> ops.add(n.get("operation").asText()));
        assertThat(ops).containsExactly("DELETE", "ADJUST_EXPIRATION");
        JsonNode adj = btcHistory.get(1);
        assertThat(longs(adj.get("expirationAdjustment").get("tradeIds"))).containsExactlyElementsOf(p1Ids());
        assertThat(btcHistory.get(0).get("before").get("contractLabel").asText())
                .as("deleted record already carried the new date").isEqualTo(P1_NEW);
    }

    // =================================================================================================== AC 5

    @Test
    @DisplayName("AC5 再对 P2 调整为 12-03 → 成功（position LONG 2 张），FEAT-042 提醒消失（Q129）")
    void ac5AdjustP2AlertDisappears() {
        scenario();
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        assertThat(alerts()).containsExactly("2026-12-04|1");

        JsonNode res = adjustOk("IXQZ", "2026-12-04", "9", "CALL", "2026-12-03");
        assertThat(res.get("contractLabel").asText()).isEqualTo(P2_NEW);
        assertThat(longs(res.get("tradeIds"))).containsExactly(p2);
        assertThat(res.get("position").get("direction").asText()).isEqualTo("LONG");
        assertThat(res.get("position").get("contracts").asInt()).isEqualTo(2);
        assertThat(alerts()).isEmpty();
        assertThat(flag(P2_NEW)).isEqualTo("false|null");
        assertThat(auditItems("")).hasSize(2);
    }

    // =================================================================================================== AC 6–7

    @Test
    @DisplayName("AC6 绕过页面对 P3（12-18，非临时休市）调整为 12-17 → 422 EXPIRATION_NOT_ADHOC_CLOSURE，什么都不改")
    void ac6NotAdhocClosure() {
        scenario();
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-18", "45", "PUT", "2026-12-17"),
                "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC7 P1 新到期日 2026-12-05（周六）→ 422 NEW_EXPIRATION_NOT_TRADING_DAY（先于范围检查）；感恩节 11-26、临时休市 12-04 同样")
    void ac7NotTradingDay() {
        scenario();
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-05"),
                "NEW_EXPIRATION_NOT_TRADING_DAY", "新到期日 2026-12-05 不是美股交易日");
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-11-26"),
                "NEW_EXPIRATION_NOT_TRADING_DAY", "新到期日 2026-11-26 不是美股交易日");
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-04"),
                "NEW_EXPIRATION_NOT_TRADING_DAY", "新到期日 2026-12-04 不是美股交易日");
        assertThat(snapshot()).isEqualTo(before);
    }

    // =================================================================================================== AC 8–9

    @Test
    @DisplayName("AC8 账户 C 的 QRTV 2025-01-09 40P 已作废 → 422 CONTRACT_ALREADY_SETTLED；持仓页没有该行")
    void ac8SettledRejected() {
        fetchAt(T_FETCH1, scenarioData());
        setNow(T_DAY2);
        long c = accountWith2025Put("账户C", "QRTV", "40", "80.00");
        as(c, () -> {
            createdId(post(TRADES + "/expirations", contractBody("QRTV", "2025-01-09", "40", "PUT", 1).toString()),
                    "EXP");
            assertThat(findPosition("QRTV 2025-01-09 40 P")).isNull();
            assertThat(alerts()).isEmpty();
            String before = snapshot();
            assert422(adjust("QRTV", "2025-01-09", "40", "PUT", "2025-01-08"),
                    "CONTRACT_ALREADY_SETTLED", "合约已作废 / 被指派 / 行权，不能调整到期日");
            // order: settled (5) before not-trading-day (6): 2025-01-11 is a Saturday
            assert422(adjust("QRTV", "2025-01-09", "40", "PUT", "2025-01-11"),
                    "CONTRACT_ALREADY_SETTLED", "合约已作废 / 被指派 / 行权，不能调整到期日");
            assertThat(snapshot()).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("AC9 账户 D 的 IXQZ 2025-01-09 8P 未处理 → 调整为 2025-01-08 成功；已过期 692 天、可作废；作废记在 2025-01-08 16:00（Q63）")
    void ac9PastDateThenExpire() {
        fetchAt(T_FETCH1, scenarioData());
        setNow(T_DAY2);
        long d = accountWith2025Put("账户D", "IXQZ", "8", "30.00");
        as(d, () -> {
            String old = "IXQZ 2025-01-09 8 P";
            String neu = "IXQZ 2025-01-08 8 P";
            assertThat(flag(old)).isEqualTo("true|" + MOURNING_2025);
            assertThat(adjFields(old)).isEqualTo("true|1|2025-01-08|2024-12-20|2025-01-08");
            assertThat(alerts()).containsExactly("2025-01-09|1");

            JsonNode res = adjustOk("IXQZ", "2025-01-09", "8", "PUT", "2025-01-08");
            assertThat(res.get("contractLabel").asText()).isEqualTo(neu);
            JsonNode pos = position(neu);
            assertThat(pos.get("dte").asInt()).isEqualTo(-692);
            assertThat(pos.get("daysPastExpiration").asInt()).isEqualTo(692);
            assertThat(pos.get("expirationStatus").asText()).isEqualTo("EXPIRED");
            assertThat(pos.get("expirable").asBoolean()).isTrue();
            assertThat(pos.get("frozenCash").asText()).isEqualTo("800.00");
            assertThat(alerts()).isEmpty();

            Resp exp = post(TRADES + "/expirations", contractBody("IXQZ", "2025-01-08", "8", "PUT", 1).toString());
            assertThat(exp.status()).as("expire -> %s", exp.body()).isEqualTo(201);
            assertThat(exp.body().get("expirationDate").asText()).isEqualTo("2025-01-08");
            assertThat(exp.body().get("tradedAtLocal").asText()).isEqualTo("2025-01-08T16:00:00");
            assertThat(findPosition(neu)).isNull();
        });
    }

    // =================================================================================================== Q131 range

    @Test
    @DisplayName("Q131 最早允许日 = 最后成交日 11-25（过去日期）：成功，持仓显示已过期 6 天、未处理（冻结仍在）；11-24 → OUT_OF_RANGE")
    void q131EarliestAllowedPastDate() {
        scenario();
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-11-24"),
                "NEW_EXPIRATION_OUT_OF_RANGE", "新到期日不能早于该合约最后一笔记录的成交日期 2026-11-25");
        assertThat(snapshot()).isEqualTo(before);

        JsonNode res = adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-11-25");
        assertThat(res.get("contractLabel").asText()).isEqualTo("QRTV 2026-11-25 50 P");
        JsonNode pos = position("QRTV 2026-11-25 50 P");
        assertThat(pos.get("expirationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(pos.get("dte").asInt()).isEqualTo(-6);
        assertThat(pos.get("expirable").asBoolean()).isTrue();
        assertThat(pos.get("frozenCash").asText()).as("expired, not processed").isEqualTo("5000.00");
        assertThat(closedRow(p1Btc).get("realizedPnl").asText()).isEqualTo("80.00");
    }

    @Test
    @DisplayName("Q131 最晚允许日 = 12-03（当前到期日前一交易日）成功；≥ 当前到期日（12-07 交易日）→ OUT_OF_RANGE \"必须早于当前到期日\"")
    void q131LatestAllowedAndNotEarlier() {
        scenario();
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-07"),
                "NEW_EXPIRATION_OUT_OF_RANGE", "新到期日必须早于当前到期日 2026-12-04");
        assertThat(snapshot()).isEqualTo(before);
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
    }

    @Test
    @DisplayName("Q131 调整后不能再调：12-03 是交易日 → 第二次调整 EXPIRATION_NOT_ADHOC_CLOSURE；12-03 之后也被记为临时休市 → EXPIRATION_ALREADY_ADJUSTED（先于 5–9），按钮不出现；未调整过的 12-03 合约仍可调")
    void q131SecondAdjustmentRejected() {
        scenario(() -> okTrade(trade("STO", 1, "QRTV", "2026-12-03", "44", "PUT", "40.00", "2026-11-30T09:50")));
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        assert422(adjust("QRTV", "2026-12-03", "50", "PUT", "2026-12-02"),
                "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");

        // 2026-12-03 later detected as an ad-hoc closure as well (11:00 New York fetch)
        fetchAt("2026-12-01T16:00:00Z", plus(withDec4(), closed("Second closure", "2026-12-03")));
        setNow("2026-12-01T16:30:00Z");
        assertThat(flag(P1_NEW)).isEqualTo("true|Second closure");
        assertThat(adjFields(P1_NEW)).as("adjusted contract: no button").isEqualTo(NOT_ADJUSTABLE);

        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-03", "50", "PUT", "2026-12-02"),
                "EXPIRATION_ALREADY_ADJUSTED", "该合约已调整过到期日，不能再次调整");
        // order: already-adjusted (4) before not-trading-day (6) / range (7)
        assert422(adjust("QRTV", "2026-12-03", "50", "PUT", "2026-12-05"),
                "EXPIRATION_ALREADY_ADJUSTED", "该合约已调整过到期日，不能再次调整");
        assertThat(snapshot()).isEqualTo(before);

        // a never-adjusted contract on the same date is adjustable
        assertThat(adjFields("QRTV 2026-12-03 44 P")).isEqualTo("true|1|2026-12-02|2026-11-30|2026-12-02");
        adjustOk("QRTV", "2026-12-03", "44", "PUT", "2026-12-02");
    }

    // =================================================================================================== Q83

    @Test
    @DisplayName("Q83 记录成交时间 ≤ 新到期日 16:00：正好 16:00 可调整；16:30 → TRADE_AFTER_NEW_EXPIRATION 指出最早一笔（先于目标合约已存在）；之后按新到期日：到期待确认 / 17:30 可作废")
    void q83SixteenHundredRule() {
        fetchAt(T_FETCH1, scenarioData());
        setNow("2026-12-02T15:00:00Z");
        deposit("30000.00", "2026-11-19T10:00");
        // target QRTV 2026-12-03 53P already has a record (check 9 would also fail)
        okTrade(trade("BTO", 1, "QRTV", "2026-12-03", "53", "PUT", "10.00", "2026-12-02T09:50"));
        setNow("2026-12-03T22:00:00Z"); // 17:00 New York, 12-04 still a trading day
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "52", "PUT", "50.00", "2026-12-03T16:00"));
        long late1 = okTrade(trade("STO", 1, "QRTV", "2026-12-04", "53", "PUT", "50.00", "2026-12-03T16:30"));
        okTrade(trade("STO", 1, "QRTV", "2026-12-04", "53", "PUT", "50.00", "2026-12-03T16:45"));
        fetchAt("2026-12-03T22:05:00Z", withDec4());
        setNow("2026-12-03T22:10:00Z"); // 17:10 New York

        assertThat(adjFields("QRTV 2026-12-04 52 P")).isEqualTo("true|1|2026-12-03|2026-12-03|2026-12-03");
        assertThat(adjFields("QRTV 2026-12-04 53 P")).isEqualTo("true|2|2026-12-03|2026-12-03|2026-12-03");

        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "53", "PUT", "2026-12-03"), "TRADE_AFTER_NEW_EXPIRATION",
                "交易 #" + late1 + " STO 的成交时间 2026-12-03 16:30 晚于新到期日 2026-12-03 16:00");
        assert422(adjust("QRTV", "2026-12-04", "52", "PUT", "2026-12-02"),
                "NEW_EXPIRATION_OUT_OF_RANGE", "新到期日不能早于该合约最后一笔记录的成交日期 2026-12-03");
        assertThat(snapshot()).isEqualTo(before);

        // exactly 16:00 on the new date is allowed; earliest = latest allowed date
        adjustOk("QRTV", "2026-12-04", "52", "PUT", "2026-12-03");
        JsonNode pos = position("QRTV 2026-12-03 52 P");
        assertThat(pos.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(pos.get("expiryPending").asBoolean()).as("17:10 on the new expiration date").isTrue();
        assertThat(pos.get("expirable").asBoolean()).isFalse();

        setNow("2026-12-03T22:45:00Z"); // 17:45 New York
        assertThat(position("QRTV 2026-12-03 52 P").get("expirable").asBoolean()).isTrue();
        Resp exp = post(TRADES + "/expirations", contractBody("QRTV", "2026-12-03", "52", "PUT", 1).toString());
        assertThat(exp.status()).as("expire -> %s", exp.body()).isEqualTo(201);
        assertThat(exp.body().get("tradedAtLocal").asText()).isEqualTo("2026-12-03T16:00:00");
    }

    @Test
    @DisplayName("之后按新到期日：12-03 16:01 平仓被拒（Q83），17:45 作废记在 2026-12-03 16:00")
    void laterProcessingUsesNewDate() {
        scenario();
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        setNow("2026-12-03T22:45:00Z");
        ObjectNode n = trade("BTC", 1, "QRTV", "2026-12-03", "50", "PUT", "10.00", "2026-12-03T16:01");
        n.remove("multiplier");
        Resp late = post(TRADES + "/closes", n.toString());
        assertThat(late.status()).as("close after 16:00 -> %s", late.body()).isBetween(400, 422);
        assertThat(late.body().toString()).contains("2026-12-03");

        JsonNode pos = position(P1_NEW);
        assertThat(pos.get("expirationStatus").asText()).isEqualTo("EXPIRES_TODAY");
        assertThat(pos.get("expirable").asBoolean()).isTrue();
        Resp exp = post(TRADES + "/expirations", contractBody("QRTV", "2026-12-03", "50", "PUT", 1).toString());
        assertThat(exp.status()).as("expire -> %s", exp.body()).isEqualTo(201);
        assertThat(exp.body().get("tradedAtLocal").asText()).isEqualTo("2026-12-03T16:00:00");
        assertThat(exp.body().get("frozenChange").asText()).isEqualTo("-5000.00");
    }

    // =================================================================================================== Q132

    @Test
    @DisplayName("Q132 目标合约 QRTV 2026-12-03 50 P 已有记录（已平完也算）→ 422 TARGET_CONTRACT_EXISTS；其它账户的同名合约不挡")
    void q132TargetExists() {
        scenario(() -> {
            okTrade(trade("STO", 1, "QRTV", "2026-12-03", "50", "PUT", "30.00", "2026-11-30T09:50"));
            close("BTC", 1, "QRTV", "2026-12-03", "50", "PUT", "10.00", "2026-11-30T09:51");
        });
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-03"),
                "TARGET_CONTRACT_EXISTS", "该账户已有合约 QRTV 2026-12-03 50 P 的记录，不能合并");
        assertThat(snapshot()).isEqualTo(before);
        // P2 is unaffected
        adjustOk("IXQZ", "2026-12-04", "9", "CALL", "2026-12-03");
    }

    @Test
    @DisplayName("检查顺序 7 先于 9：新到期日早于最后成交日且目标合约已存在 → OUT_OF_RANGE")
    void rangeBeforeTargetExists() {
        scenario(() -> okTrade(trade("BTO", 1, "QRTV", "2026-11-24", "50", "PUT", "10.00", "2026-11-20T10:05")));
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-11-24"),
                "NEW_EXPIRATION_OUT_OF_RANGE", "新到期日不能早于该合约最后一笔记录的成交日期 2026-11-25");
    }

    @Test
    @DisplayName("跨账户（Q53）：只改当前账户；账户 B 的同一合约不变、仍可调整、提醒仍在；B 中没有 A 的目标合约记录时不挡")
    void crossAccount() {
        long[] b = new long[2];
        scenario(() -> {
            b[0] = newAccount("账户B");
            as(b[0], () -> {
                deposit("10000.00", "2026-11-19T10:00");
                b[1] = okTrade(trade("STO", 1, "QRTV", "2026-12-04", "50", "PUT", "90.00", "2026-11-30T09:45"));
            });
        });
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        as(b[0], () -> {
            JsonNode t = tradeRow(b[1]);
            assertThat(t.get("contractLabel").asText()).isEqualTo(P1);
            assertThat(t.get("hasAuditLog").asBoolean()).isFalse();
            assertThat(adjFields(P1)).isEqualTo("true|1|2026-12-03|2026-11-30|2026-12-03");
            assertThat(alerts()).containsExactly("2026-12-04|1");
            // creating account B wrote an ACCOUNT audit (FEAT-033); B has no option-trade audit
            assertThat(auditItems("&objectType=OPTION_TRADE")).isEmpty();
            // B adjusts its own contract; A's QRTV 2026-12-03 50 P does not count as B's target
            JsonNode res = adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
            assertThat(longs(res.get("tradeIds"))).containsExactly(b[1]);
        });
        // A cannot see / adjust B's record: A's own (adjusted) contract no longer exists on 12-04
        Resp r = adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        assertErrorShape(r, 404, "NOT_FOUND", ADJUST);
        assertThat(r.body().get("message").asText()).isEqualTo("该账户没有合约 QRTV 2026-12-04 50 P 的记录");
    }

    // =================================================================================================== Q133

    @Test
    @DisplayName("Q133 部分被指派（空头 put 2 批次指派 1 批）/ 部分行权（多头 call 2 批次行权 1 批）：仍是持仓、带标签，但不可调整；提交 → CONTRACT_ALREADY_SETTLED")
    void q133PartialAssignmentAndExercise() {
        scenario(() -> {
            okTrade(trade("STO", 1, "QRTV", "2026-12-04", "47", "PUT", "60.00", "2026-11-30T09:50"));
            okTrade(trade("STO", 1, "QRTV", "2026-12-04", "47", "PUT", "60.00", "2026-11-30T09:51"));
            okTrade(trade("BTO", 1, "IXQZ", "2026-12-04", "7", "CALL", "40.00", "2026-11-30T09:52"));
            okTrade(trade("BTO", 1, "IXQZ", "2026-12-04", "7", "CALL", "40.00", "2026-11-30T09:53"));
        });
        ObjectNode asg = contractBody("QRTV", "2026-12-04", "47", "PUT", 1);
        asg.put("assignmentDate", "2026-11-30");
        asg.put("assignmentFee", "0.00");
        asg.putNull("note");
        createdId(post(TRADES + "/assignments", asg.toString()), "ASG");
        ObjectNode exr = contractBody("IXQZ", "2026-12-04", "7", "CALL", 1);
        exr.put("exerciseDate", "2026-11-30");
        exr.put("exerciseFee", "0.00");
        exr.putNull("note");
        createdId(post(TRADES + "/exercises", exr.toString()), "EXR");

        for (String label : List.of("QRTV 2026-12-04 47 P", "IXQZ 2026-12-04 7 C")) {
            assertThat(position(label).get("contracts").asInt()).isEqualTo(1);
            assertThat(flag(label)).isEqualTo("true|" + MOURNING_2026);
            assertThat(adjFields(label)).as(label).isEqualTo(NOT_ADJUSTABLE);
        }
        String before = snapshot();
        assert422(adjust("QRTV", "2026-12-04", "47", "PUT", "2026-12-03"),
                "CONTRACT_ALREADY_SETTLED", "合约已作废 / 被指派 / 行权，不能调整到期日");
        assert422(adjust("IXQZ", "2026-12-04", "7", "CALL", "2026-12-03"),
                "CONTRACT_ALREADY_SETTLED", "合约已作废 / 被指派 / 行权，不能调整到期日");
        assertThat(snapshot()).isEqualTo(before);
    }

    // =================================================================================================== other edges

    @Test
    @DisplayName("已全部平仓（只有 STO + BTC）的临时休市合约：可提交，position 为 null，tradeIds 两条")
    void fullyClosedContractPositionNull() {
        long[] ids = new long[2];
        scenario(() -> {
            ids[0] = okTrade(trade("STO", 1, "QRTV", "2026-12-04", "48", "PUT", "50.00", "2026-11-30T09:43"));
            ids[1] = close("BTC", 1, "QRTV", "2026-12-04", "48", "PUT", "20.00", "2026-11-30T09:44");
        });
        assertThat(findPosition("QRTV 2026-12-04 48 P")).isNull();
        JsonNode res = adjustOk("QRTV", "2026-12-04", "48", "PUT", "2026-12-03");
        assertThat(res.get("position").isNull()).as("position null, not omitted").isTrue();
        assertThat(longs(res.get("tradeIds"))).containsExactly(ids[0], ids[1]);
        assertThat(closedRow(ids[1]).get("contractLabel").asText()).isEqualTo("QRTV 2026-12-03 48 P");
        assertThat(closedRow(ids[1]).get("realizedPnl").asText()).isEqualTo("30.00");
        assertThat(alerts()).containsExactly("2026-12-04|2");
    }

    @Test
    @DisplayName("Q130 空头 call 有股票覆盖：调整后覆盖股数、批次覆盖状态、股票可用股数都不变")
    void coveredCallCoverageUnchanged() {
        scenario(() -> {
            ObjectNode st = json.createObjectNode();
            st.put("type", "TRANSFER_IN");
            st.put("symbol", "KPLM");
            st.put("shares", 100);
            st.put("amount", "2000.00");
            st.put("tradeDate", "2026-11-20");
            st.putNull("note");
            Resp r = post("/api/stock-trades", st.toString());
            assertThat(r.status()).as("transfer in -> %s", r.body()).isEqualTo(201);
            okTrade(trade("STO", 1, "KPLM", "2026-12-04", "20", "CALL", "45.00", "2026-11-30T09:55"));
        });
        JsonNode before = position("KPLM 2026-12-04 20 C");
        assertThat(before.get("coveredShares").asInt()).isEqualTo(100);
        JsonNode stockBefore = get("/api/stock-positions").body();

        adjustOk("KPLM", "2026-12-04", "20", "CALL", "2026-12-03");
        JsonNode after = position("KPLM 2026-12-03 20 C");
        assertThat(after.get("coveredShares").asInt()).isEqualTo(100);
        assertThat(after.get("uncoveredContracts").asInt()).isEqualTo(0);
        assertThat(after.get("lots")).isEqualTo(before.get("lots"));
        assertThat(after.get("lots").get(0).get("coverage").asText()).isEqualTo("CHECKED");
        assertThat(get("/api/stock-positions").body()).isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("请求：标的小写、行权价 50.00 按规范化匹配；多余字段忽略；备注全空白 → null；500 字符备注可以")
    void requestNormalizationAndNote() {
        scenario();
        ObjectNode body = adjustBody("qrtv", "2026-12-04", "50.00", "PUT", "2026-12-03");
        body.put("note", "   ");
        body.put("action", "BTC");
        body.put("contracts", 99);
        JsonNode res = adjustOk(body);
        assertThat(res.get("symbol").asText()).isEqualTo("QRTV");
        assertThat(res.get("strike").asText()).isEqualTo("50");
        assertThat(auditItems("").get(0).get("expirationAdjustment").get("note").isNull()).isTrue();

        ObjectNode body2 = adjustBody("IXQZ", "2026-12-04", "9", "CALL", "2026-12-03");
        String note500 = "注".repeat(500);
        body2.put("note", note500);
        adjustOk(body2);
        assertThat(auditItems("").get(0).get("expirationAdjustment").get("note").asText()).isEqualTo(note500);
    }

    // =================================================================================================== check 1–2

    @Test
    @DisplayName("检查 1 字段：缺新到期日 → VALIDATION_ERROR（先于 NOT_FOUND）；格式不对 → BAD_REQUEST；备注 501 字 → VALIDATION_ERROR；缺标的 → VALIDATION_ERROR；JSON 语法错误 → BAD_REQUEST")
    void check1Validation() {
        scenario();
        String before = snapshot();

        Resp missing = post(ADJUST, adjustBody("QRTV", "2026-12-04", "55", "PUT", null).toString());
        assertErrorShape(missing, 400, "VALIDATION_ERROR", ADJUST);
        assertThat(detailFields(missing)).containsExactly("newExpirationDate");
        assertThat(detailMessage(missing, "newExpirationDate")).isEqualTo("新到期日不能为空");

        Resp badFormat = adjust("QRTV", "2026-12-04", "50", "PUT", "2026/12/03");
        assertErrorShape(badFormat, 400, "BAD_REQUEST", ADJUST);
        assertThat(detailFields(badFormat)).containsExactly("newExpirationDate");
        Resp badDate = adjust("QRTV", "2026-12-04", "50", "PUT", "2026-02-30");
        assertErrorShape(badDate, 400, "BAD_REQUEST", ADJUST);

        ObjectNode longNote = adjustBody("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        longNote.put("note", "x".repeat(501));
        Resp ln = post(ADJUST, longNote.toString());
        assertErrorShape(ln, 400, "VALIDATION_ERROR", ADJUST);
        assertThat(detailFields(ln)).containsExactly("note");
        assertThat(detailMessage(ln, "note")).isEqualTo("备注最多 500 个字符");

        ObjectNode noSymbol = adjustBody("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        noSymbol.remove("symbol");
        Resp ns = post(ADJUST, noSymbol.toString());
        assertErrorShape(ns, 400, "VALIDATION_ERROR", ADJUST);
        assertThat(detailFields(ns)).contains("symbol");

        assertErrorShape(post(ADJUST, "{\"symbol\":"), 400, "BAD_REQUEST", ADJUST);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("检查 1 账户：无头 400 ACCOUNT_REQUIRED；非数字 400 BAD_REQUEST；不存在 404 ACCOUNT_NOT_FOUND")
    void check1AccountErrors() {
        scenario();
        String body = adjustBody("QRTV", "2026-12-04", "50", "PUT", "2026-12-03").toString();
        assertErrorShape(exchangeAs(null, HttpMethod.POST, ADJUST, body), 400, "ACCOUNT_REQUIRED", ADJUST);
        assertErrorShape(exchangeAs("abc", HttpMethod.POST, ADJUST, body), 400, "BAD_REQUEST", ADJUST);
        assertErrorShape(exchangeAs("999", HttpMethod.POST, ADJUST, body), 404, "ACCOUNT_NOT_FOUND", ADJUST);
        assertThat(adjFields(P1)).isEqualTo("true|3|2026-12-03|2026-11-25|2026-12-03");
        assertThat(auditItems("")).isEmpty();
    }

    @Test
    @DisplayName("检查 2 NOT_FOUND：当前账户没有该合约的记录（临时休市日上的不存在合约、以及非临时休市日 → 2 先于 3）")
    void check2NotFound() {
        scenario();
        String before = snapshot();
        Resp r = adjust("QRTV", "2026-12-04", "55", "PUT", "2026-12-03");
        assertErrorShape(r, 404, "NOT_FOUND", ADJUST);
        assertThat(r.body().get("message").asText()).isEqualTo("该账户没有合约 QRTV 2026-12-04 55 P 的记录");
        assertThat(r.body().get("details")).isEmpty();

        Resp r2 = adjust("QRTV", "2026-12-18", "99", "PUT", "2026-12-17");
        assertErrorShape(r2, 404, "NOT_FOUND", ADJUST);
        assertThat(r2.body().get("message").asText()).isEqualTo("该账户没有合约 QRTV 2026-12-18 99 P 的记录");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("检查 3 先于 6：P3（非临时休市）+ 非交易日新日期 12-05 → EXPIRATION_NOT_ADHOC_CLOSURE；已撤销的临时休市也算不是")
    void check3BeforeOthersAndRevoked() {
        scenario();
        assert422(adjust("QRTV", "2026-12-18", "45", "PUT", "2026-12-05"),
                "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");
        // Q127: 2026-12-04 revoked by a later fetch → not adjustable any more
        fetchAt("2026-12-01T16:00:00Z", scenarioData());
        setNow("2026-12-01T16:30:00Z");
        assertThat(adjFields(P1)).isEqualTo(NOT_ADJUSTABLE);
        assert422(adjust("QRTV", "2026-12-04", "50", "PUT", "2026-12-03"),
                "EXPIRATION_NOT_ADHOC_CLOSURE", "只能调整到期日为临时休市日的合约");
    }

    @Test
    @DisplayName("调整后 FEAT-042 记录被撤销（Q127）：已调整的记录不回退")
    void revokedAfterAdjustmentKeepsNewDate() {
        scenario();
        adjustOk("QRTV", "2026-12-04", "50", "PUT", "2026-12-03");
        fetchAt("2026-12-01T16:00:00Z", scenarioData());
        setNow("2026-12-01T16:30:00Z");
        for (long id : p1Ids()) {
            assertThat(tradeRow(id).get("expirationDate").asText()).isEqualTo("2026-12-03");
        }
        assertThat(position(P1_NEW).get("contracts").asInt()).isEqualTo(1);
    }

    // =================================================================================================== test support

    @Test
    @DisplayName("测试支持 legacy-option-trades 扩展：BTO call / BTO put 可预置（2025-01-09 不查交易日）；平仓方向 → 400 VALIDATION_ERROR field action")
    void legacyEndpointExtended() {
        fetchAt(T_FETCH1, scenarioData());
        setNow(T_DAY2);
        deposit("10000.00", "2024-12-19T10:00");
        legacyTrade(trade("BTO", 1, "IXQZ", "2025-01-09", "6", "CALL", "20.00", "2024-12-20T10:00"));
        legacyTrade(trade("BTO", 1, "IXQZ", "2025-01-09", "5", "PUT", "20.00", "2024-12-20T10:00"));
        assertThat(flag("IXQZ 2025-01-09 6 C")).isEqualTo("true|" + MOURNING_2025);
        assertThat(flag("IXQZ 2025-01-09 5 P")).isEqualTo("true|" + MOURNING_2025);

        Resp bad = post(LEGACY, trade("BTC", 1, "IXQZ", "2025-01-09", "5", "PUT", "5.00", "2024-12-23T10:00")
                .toString());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(detailFields(bad)).containsExactly("action");
        assertThat(detailMessage(bad, "action")).isEqualTo("本功能只支持开仓（BTO / STO），平仓请使用平仓功能");
    }
}
