package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Shared helpers of the QA system tests for FEAT-016 自动行情 (contract docs/api/FEAT-016.md).
 *
 * <p>Runs with profiles {@code test} + {@code e2e} on a random port (never 8080): the backend never calls the real
 * CBOE / Finnhub (no scheduled / startup fetch, in-memory fake transport). Each "pull" of a test is either
 * {@code POST /api/test-support/market-data/tick} (exactly the scheduled run at the current clock) or
 * {@code POST /api/market-data/refresh}; the fake responses are set with {@code PUT /api/test-support/market-data/
 * sources}, the requests the fake sources received are read from {@code GET /api/test-support/market-data/requests},
 * the stored raw quotes / close snapshots from {@code GET /api/test-support/market-data/stored}, "now" with
 * {@code PUT /api/test-support/clock}. {@code POST /api/test-support/reset} before and after every test empties the
 * database, the market data, the fake source configuration, the request log and the refresh cooldown.
 *
 * <p>Requirement scenario: today 2026-11-23 (Mon, EST = UTC-5). Account A (default account 1): STO 1 QRTV 2026-12-18
 * 50P (P1). Account B: 100 IXQZ shares (转入), STO 1 IXQZ 2026-12-18 60C (P2), STO 1 QRTV 2026-12-18 50P (P3).
 * Fake CBOE: QRTV current_price 52.40 at 11-23 09:45 with QRTV261218P00050000 bid 1.20 / ask 1.30 / theo 1.24 / iv
 * 0.3512 / delta -0.3021 / theta -0.0456; IXQZ 61.10 at 09:45 with IXQZ261218C00060000 bid 2.10 / ask 2.30. First pull
 * at 10:00. All tickers are fictional.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "e2e"})
abstract class Feat016QuoteSystemTestBase {

    static final ZoneId NY = ZoneId.of("America/New_York");

    static final String OPTION_POSITIONS = "/api/option-positions";
    static final String STOCK_POSITIONS = "/api/stock-positions";
    static final String STATUS = "/api/market-data/status";
    static final String REFRESH = "/api/market-data/refresh";
    static final String OVERRIDE_UNDERLYINGS = "/api/market-data/overrides/underlyings/";
    static final String OVERRIDE_OPTIONS = "/api/market-data/overrides/options";
    static final String SOURCES = "/api/test-support/market-data/sources";
    static final String REQUESTS = "/api/test-support/market-data/requests";
    static final String TICK = "/api/test-support/market-data/tick";
    static final String STORED = "/api/test-support/market-data/stored";
    static final String CLOCK = "/api/test-support/clock";
    static final String RESET = "/api/test-support/reset";

    static final String P1 = "QRTV 2026-12-18 50 P";
    static final String P2 = "IXQZ 2026-12-18 60 C";
    static final String P3 = P1;
    static final String P4 = "QRTV 2026-12-18 47.5 P";

    static final String OCC_P1 = "QRTV261218P00050000";
    static final String OCC_P2 = "IXQZ261218C00060000";
    static final String OCC_P4 = "QRTV261218P00047500";

    static final String NEVER_SUCCEEDED_WARNING = "行情未更新（从未成功获取）";
    static final String NEVER_SUCCEEDED_ROW = "行情未更新（从未成功）";

    /** Exact field sets of the contract (nothing more, in particular no IV / Greeks on the page objects). */
    static final List<String> OPTION_QUOTE_FIELDS = List.of("bid", "ask", "mark", "automaticMark", "markBasis",
            "source", "quoteTime", "quoteTimeLocal", "contractStatus", "contractStatusLabel", "manual",
            "manualEnteredAt", "manualEnteredAtLocal", "fetchFailed", "lastSuccessAt", "lastSuccessAtLocal",
            "staleLabel");
    static final List<String> UNDERLYING_QUOTE_FIELDS = List.of("symbol", "price", "automaticPrice", "source",
            "quoteTime", "quoteTimeLocal", "manual", "manualEnteredAt", "manualEnteredAtLocal", "fetchFailed",
            "lastSuccessAt", "lastSuccessAtLocal", "staleLabel");
    static final List<String> STATUS_FIELDS = List.of("stale", "warning", "lastSuccessAt", "lastSuccessAtLocal",
            "lastAttemptAt", "lastAttemptAtLocal", "lastRefreshAt", "lastRefreshAtLocal", "refreshAvailableAt",
            "refreshAllowed", "trackedSymbolCount", "optionSource", "riskFreeRatePercent");
    /** FEAT-052 added {@code optionSource} / {@code yahooResult} ({@code cboeResult} is null under Yahoo). */
    static final List<String> RESULT_FIELDS = List.of("symbol", "result", "optionSource", "yahooResult",
            "cboeResult", "finnhubResult", "underlyingPriceSource", "failureDetail");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    /** X-Account-Id of business calls; null = omit. */
    String accountId = "1";

    /** Id of account B once {@link #scenario()} ran. */
    String accountB;

    @BeforeEach
    void resetBefore() {
        accountId = "1";
        accountB = null;
        reset();
    }

    @AfterEach
    void resetAfter() {
        accountId = "1";
        reset();
    }

    // =========================================================================================== time

    /** Instant of the New York local time "yyyy-MM-ddTHH:mm[:ss]". */
    static Instant ny(String local) {
        return LocalDateTime.parse(local).atZone(NY).toInstant();
    }

    void setNow(String nyLocal) {
        Instant at = ny(nyLocal);
        Resp r = noAccount(() -> exchange(HttpMethod.PUT, CLOCK, "{\"now\":\"" + at + "\"}"));
        assertThat(r.status()).as("PUT %s %s -> %s", CLOCK, at, r.body()).isEqualTo(204);
    }

    void reset() {
        Resp r = noAccount(() -> exchange(HttpMethod.POST, RESET, null));
        assertThat(r.status()).as("POST %s", RESET).isEqualTo(204);
    }

    // =========================================================================================== HTTP

    record Resp(int status, JsonNode body) {
    }

    interface Call {
        Resp run();
    }

    Resp noAccount(Call c) {
        String saved = accountId;
        accountId = null;
        try {
            return c.run();
        } finally {
            accountId = saved;
        }
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

    static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // =========================================================================================== business data

    String createAccount(String name) {
        Resp r = noAccount(() -> post("/api/accounts", "{\"name\":\"" + name + "\",\"brokerNote\":null}"));
        assertThat(r.status()).as("create account %s -> %s", name, r.body()).isEqualTo(201);
        return r.body().get("id").asText();
    }

    void deposit(String amount, String at) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", at);
        n.putNull("remark");
        Resp r = post("/api/cash/transfers", n.toString());
        assertThat(r.status()).as("deposit %s -> %s", amount, r.body()).isEqualTo(201);
    }

    void transferInShares(String symbol, int shares, String amount, String tradeDate) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "TRANSFER_IN");
        n.put("symbol", symbol);
        n.put("shares", shares);
        n.put("amount", amount);
        n.put("tradeDate", tradeDate);
        n.putNull("note");
        Resp r = post("/api/stock-trades", n.toString());
        assertThat(r.status()).as("TRANSFER_IN %s -> %s", symbol, r.body()).isEqualTo(201);
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

    long open(String action, String symbol, String expiration, String strike, String type, String net, String at) {
        ObjectNode body = trade(action, 1, symbol, expiration, strike, type, net, at);
        Resp r = post("/api/option-trades", body.toString());
        assertThat(r.status()).as("POST %s -> %s", body, r.body()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    void close(String action, String symbol, String expiration, String strike, String type, String net, String at) {
        ObjectNode body = trade(action, 1, symbol, expiration, strike, type, net, at);
        body.remove("multiplier");
        Resp r = post("/api/option-trades/closes", body.toString());
        assertThat(r.status()).as("POST closes %s -> %s", body, r.body()).isEqualTo(201);
    }

    /**
     * The requirement scenario (data only; no fake source configured, no pull). Leaves the clock at 2026-11-23 09:50
     * New York and the API account at A (1).
     */
    void scenario() {
        setNow("2026-11-23T09:50");
        accountId = "1";
        deposit("20000.00", "2026-11-16T10:00");
        open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:00");
        accountB = createAccount("账户B");
        accountId = accountB;
        deposit("10000.00", "2026-11-16T10:00");
        transferInShares("IXQZ", 100, "6000.00", "2026-11-19");
        open("STO", "IXQZ", "2026-12-18", "60", "CALL", "150.00", "2026-11-20T10:05");
        open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:10");
        accountId = "1";
    }

    // =========================================================================================== fake sources

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** One CBOE options[] entry; {@code fields} = name, value pairs (value String → JSON number, null → omitted). */
    ObjectNode option(String occ, String... fields) {
        ObjectNode o = json.createObjectNode();
        o.put("option", occ);
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] != null) {
                if (fields[i].equals("volume") || fields[i].equals("open_interest")) {
                    o.put(fields[i], Long.parseLong(fields[i + 1]));
                } else {
                    o.put(fields[i], d(fields[i + 1]));
                }
            }
        }
        return o;
    }

    /** The scenario's P1 entry (bid / ask / theo / iv / delta / theta as in the requirement, plus the other Greeks). */
    ObjectNode p1Option(String bid, String ask) {
        return option(OCC_P1, "bid", bid, "ask", ask, "theo", "1.24", "iv", "0.3512", "delta", "-0.3021",
                "gamma", "0.0512", "theta", "-0.0456", "vega", "0.0711", "rho", "-0.0123",
                "last_trade_price", "1.22", "volume", "120", "open_interest", "3400");
    }

    ObjectNode p1Option() {
        return p1Option("1.20", "1.30");
    }

    ObjectNode p2Option() {
        return option(OCC_P2, "bid", "2.10", "ask", "2.30");
    }

    /** CBOE body: {timestamp, data:{symbol, current_price, bid, ask, prev_day_close, options}}. */
    ObjectNode cboeBody(String symbol, String currentPrice, String timestampNyLocal, List<ObjectNode> options) {
        ObjectNode body = json.createObjectNode();
        body.put("timestamp", timestampNyLocal);
        ObjectNode data = body.putObject("data");
        data.put("symbol", symbol);
        if (currentPrice == null) {
            data.putNull("current_price");
        } else {
            data.put("current_price", d(currentPrice));
            data.put("bid", d(currentPrice).subtract(d("0.02")));
            data.put("ask", d(currentPrice).add(d("0.02")));
        }
        data.put("prev_day_close", d("50.00"));
        data.put("iv30", d("31.2"));
        ArrayNode arr = data.putArray("options");
        options.forEach(arr::add);
        return body;
    }

    ObjectNode ok(ObjectNode body) {
        ObjectNode n = json.createObjectNode();
        n.put("httpStatus", 200);
        n.set("body", body);
        return n;
    }

    ObjectNode httpError(int status) {
        ObjectNode n = json.createObjectNode();
        n.put("httpStatus", status);
        n.putNull("body");
        return n;
    }

    ObjectNode unreachable() {
        ObjectNode n = json.createObjectNode();
        n.put("unreachable", true);
        return n;
    }

    ObjectNode rawBody(String text) {
        ObjectNode n = json.createObjectNode();
        n.put("httpStatus", 200);
        n.put("rawBody", text);
        return n;
    }

    ObjectNode finnhubOk(String c, String tNyLocal) {
        ObjectNode b = json.createObjectNode();
        b.put("c", d(c));
        b.put("pc", d("60.80"));
        b.put("t", ny(tNyLocal).getEpochSecond());
        return ok(b);
    }

    ObjectNode qrtvOk() {
        return ok(cboeBody("QRTV", "52.40", "2026-11-23 09:45:00", List.of(p1Option())));
    }

    ObjectNode ixqzOk() {
        return ok(cboeBody("IXQZ", "61.10", "2026-11-23 09:45:00", List.of(p2Option())));
    }

    /** Fluent builder of the PUT sources body. */
    final class Sources {
        boolean keyPresent = true;
        final Map<String, ObjectNode> cboe = new LinkedHashMap<>();
        final Map<String, ObjectNode> finnhub = new LinkedHashMap<>();

        Sources cboe(String symbol, ObjectNode response) {
            cboe.put(symbol, response);
            return this;
        }

        Sources finnhub(String symbol, ObjectNode response) {
            finnhub.put(symbol, response);
            return this;
        }

        Sources noKey() {
            keyPresent = false;
            return this;
        }

        void apply() {
            ObjectNode n = json.createObjectNode();
            n.put("finnhubKeyPresent", keyPresent);
            ObjectNode c = n.putObject("cboe");
            cboe.forEach(c::set);
            ObjectNode f = n.putObject("finnhub");
            finnhub.forEach(f::set);
            Resp r = noAccount(() -> exchange(HttpMethod.PUT, SOURCES, n.toString()));
            assertThat(r.status()).as("PUT %s -> %s", SOURCES, r.body()).isEqualTo(204);
        }
    }

    Sources sources() {
        return new Sources();
    }

    /** The scenario's fake CBOE (both symbols OK, no Finnhub entries). */
    void scenarioSources() {
        sources().cboe("QRTV", qrtvOk()).cboe("IXQZ", ixqzOk()).apply();
    }

    // =========================================================================================== test support

    /** "SOURCE SYMBOL" per received request, in order. */
    List<String> requests() {
        Resp r = noAccount(() -> get(REQUESTS));
        assertThat(r.status()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        for (JsonNode q : r.body().get("requests")) {
            Instant.parse(q.get("at").asText());
            out.add(q.get("source").asText() + " " + q.get("symbol").asText());
        }
        return out;
    }

    void clearRequests() {
        Resp r = noAccount(() -> exchange(HttpMethod.DELETE, REQUESTS, null));
        assertThat(r.status()).isEqualTo(204);
    }

    /** Scheduled-timer simulation at the current clock; returns the tick body. */
    JsonNode tick() {
        Resp r = noAccount(() -> post(TICK, null));
        assertThat(r.status()).as("POST %s -> %s", TICK, r.body()).isEqualTo(200);
        assertStatusShape(r.body().get("status"));
        return r.body();
    }

    /** Clock to {@code nyLocal}, tick, and assert a run of the given trigger happened. */
    JsonNode tickAt(String nyLocal, String expectedTrigger) {
        setNow(nyLocal);
        JsonNode t = tick();
        assertThat(t.get("ran").asBoolean()).as("tick at %s ran: %s", nyLocal, t).isTrue();
        assertThat(t.get("trigger").asText()).isEqualTo(expectedTrigger);
        assertThat(t.get("skipReason").isNull()).isTrue();
        assertResultsShape(t.get("results"));
        return t;
    }

    /** Clock to {@code nyLocal}, tick, assert nothing ran for the given reason. */
    void tickSkipped(String nyLocal, String reason) {
        setNow(nyLocal);
        JsonNode t = tick();
        assertThat(t.get("ran").asBoolean()).as("tick at %s must not run: %s", nyLocal, t).isFalse();
        assertThat(t.get("trigger").isNull()).isTrue();
        assertThat(t.get("skipReason").asText()).as("skipReason at %s", nyLocal).isEqualTo(reason);
        assertThat(t.get("results")).isEmpty();
    }

    JsonNode stored() {
        Resp r = noAccount(() -> get(STORED));
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    JsonNode storedContract(String symbol, String expiration, String strike, String type) {
        for (JsonNode c : stored().get("contracts")) {
            if (c.get("symbol").asText().equals(symbol) && c.get("expirationDate").asText().equals(expiration)
                    && new BigDecimal(c.get("strike").asText()).compareTo(new BigDecimal(strike)) == 0
                    && c.get("optionType").asText().equals(type)) {
                return c;
            }
        }
        throw new AssertionError("no stored contract " + symbol + " " + expiration + " " + strike + " " + type);
    }

    JsonNode storedUnderlying(String symbol) {
        for (JsonNode u : stored().get("underlyings")) {
            if (u.get("symbol").asText().equals(symbol)) {
                return u;
            }
        }
        throw new AssertionError("no stored underlying " + symbol);
    }

    // =========================================================================================== market data API

    JsonNode status() {
        Resp r = noAccount(() -> get(STATUS));
        assertThat(r.status()).isEqualTo(200);
        assertStatusShape(r.body());
        return r.body();
    }

    Resp refreshResp() {
        return noAccount(() -> post(REFRESH, null));
    }

    JsonNode refresh() {
        Resp r = refreshResp();
        assertThat(r.status()).as("POST %s -> %s", REFRESH, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrder("performed", "reason", "results", "status");
        assertStatusShape(r.body().get("status"));
        assertResultsShape(r.body().get("results"));
        return r.body();
    }

    void assertStatusShape(JsonNode s) {
        assertThat(fieldNames(s)).as("status fields").containsExactlyInAnyOrderElementsOf(STATUS_FIELDS);
        assertThat(s.get("stale").isBoolean()).isTrue();
        assertThat(s.get("refreshAllowed").isBoolean()).isTrue();
        assertThat(s.get("trackedSymbolCount").isInt()).isTrue();
        assertThat(s.get("warning").isNull()).as("warning null iff not stale").isEqualTo(!s.get("stale").asBoolean());
        // FEAT-052: current option source + configured risk-free rate (string, at least 2 decimals)
        assertThat(s.get("optionSource").asText()).isIn("YAHOO", "CBOE");
        assertThat(s.get("riskFreeRatePercent").isTextual()).as("riskFreeRatePercent is a string").isTrue();
        assertThat(new BigDecimal(s.get("riskFreeRatePercent").asText()).scale())
                .as("riskFreeRatePercent at least 2 decimals").isGreaterThanOrEqualTo(2);
    }

    void assertResultsShape(JsonNode results) {
        assertThat(results.isArray()).isTrue();
        List<String> symbols = new ArrayList<>();
        for (JsonNode r : results) {
            assertThat(fieldNames(r)).containsExactlyInAnyOrderElementsOf(RESULT_FIELDS);
            symbols.add(r.get("symbol").asText());
            // FEAT-052: exactly one of cboeResult / yahooResult is set, per the run's option source
            String source = r.get("optionSource").asText();
            assertThat(source).isIn("YAHOO", "CBOE");
            assertThat(r.get("cboeResult").isNull()).as("cboeResult null iff YAHOO: %s", r)
                    .isEqualTo(source.equals("YAHOO"));
            assertThat(r.get("yahooResult").isNull()).as("yahooResult null iff CBOE: %s", r)
                    .isEqualTo(source.equals("CBOE"));
        }
        assertThat(symbols).as("results sorted by symbol").isSorted();
    }

    /** "SYMBOL result cboeResult finnhubResult underlyingPriceSource" per result. */
    static List<String> results(JsonNode runBody) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : runBody.get("results")) {
            out.add(r.get("symbol").asText() + " " + r.get("result").asText() + " " + r.get("cboeResult").asText()
                    + " " + text(r.get("finnhubResult")) + " " + text(r.get("underlyingPriceSource")));
        }
        return out;
    }

    // =========================================================================================== positions

    JsonNode optionPositions(String account) {
        String saved = accountId;
        accountId = account;
        try {
            Resp r = get(OPTION_POSITIONS);
            assertThat(r.status()).as("GET %s (account %s) -> %s", OPTION_POSITIONS, account, r.body())
                    .isEqualTo(200);
            return r.body();
        } finally {
            accountId = saved;
        }
    }

    /** The position row with this contractLabel in the given account (contract shapes asserted). */
    JsonNode position(String account, String contractLabel) {
        for (JsonNode p : optionPositions(account).get("positions")) {
            if (p.get("contractLabel").asText().equals(contractLabel)) {
                assertThat(fieldNames(p.get("optionQuote")))
                        .as("optionQuote fields of %s (no IV / Greeks)", contractLabel)
                        .containsExactlyInAnyOrderElementsOf(OPTION_QUOTE_FIELDS);
                assertThat(fieldNames(p.get("underlyingQuote")))
                        .containsExactlyInAnyOrderElementsOf(UNDERLYING_QUOTE_FIELDS);
                return p;
            }
        }
        throw new AssertionError("no position " + contractLabel + " in account " + account);
    }

    JsonNode optionQuote(String account, String contractLabel) {
        return position(account, contractLabel).get("optionQuote");
    }

    JsonNode underlyingQuote(String account, String contractLabel) {
        return position(account, contractLabel).get("underlyingQuote");
    }

    JsonNode stockQuote(String account, String symbol) {
        String saved = accountId;
        accountId = account;
        try {
            Resp r = get(STOCK_POSITIONS);
            assertThat(r.status()).isEqualTo(200);
            for (JsonNode p : r.body().get("positions")) {
                if (p.get("symbol").asText().equals(symbol)) {
                    assertThat(fieldNames(p.get("quote"))).containsExactlyInAnyOrderElementsOf(UNDERLYING_QUOTE_FIELDS);
                    return p.get("quote");
                }
            }
            throw new AssertionError("no stock position " + symbol + " in account " + account);
        } finally {
            accountId = saved;
        }
    }

    /** "bid/ask mark quoteTimeLocal contractStatus" — the columns the page shows. */
    static String shown(JsonNode optionQuote) {
        return text(optionQuote.get("bid")) + "/" + text(optionQuote.get("ask")) + " " + text(optionQuote.get("mark"))
                + " " + text(optionQuote.get("quoteTimeLocal")) + " " + optionQuote.get("contractStatus").asText();
    }

    // =========================================================================================== overrides

    Resp putUnderlyingOverride(String symbol, String rawBody) {
        return noAccount(() -> exchange(HttpMethod.PUT, OVERRIDE_UNDERLYINGS + symbol, rawBody));
    }

    JsonNode overrideUnderlying(String symbol, String price) {
        Resp r = putUnderlyingOverride(symbol, "{\"price\":\"" + price + "\"}");
        assertThat(r.status()).as("PUT override %s %s -> %s", symbol, price, r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrderElementsOf(UNDERLYING_QUOTE_FIELDS);
        return r.body();
    }

    Resp putOptionOverride(String rawBody) {
        return noAccount(() -> exchange(HttpMethod.PUT, OVERRIDE_OPTIONS, rawBody));
    }

    String optionOverrideBody(String symbol, String expiration, String strike, String type, String mark) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", expiration);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("mark", mark);
        return n.toString();
    }

    JsonNode overrideMark(String symbol, String expiration, String strike, String type, String mark) {
        Resp r = putOptionOverride(optionOverrideBody(symbol, expiration, strike, type, mark));
        assertThat(r.status()).as("PUT option override -> %s", r.body()).isEqualTo(200);
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrderElementsOf(OPTION_QUOTE_FIELDS);
        return r.body();
    }

    Resp deleteMarkOverride(String query) {
        return noAccount(() -> exchange(HttpMethod.DELETE, OVERRIDE_OPTIONS + query, null));
    }

    Resp deleteUnderlyingOverride(String symbol) {
        return noAccount(() -> exchange(HttpMethod.DELETE, OVERRIDE_UNDERLYINGS + symbol, null));
    }
}
