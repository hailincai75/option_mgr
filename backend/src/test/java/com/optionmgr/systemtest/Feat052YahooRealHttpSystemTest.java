package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * QA system tests for FEAT-052 with the real HTTP transport (no {@code e2e} profile, no test-support endpoints):
 * <ul>
 *   <li>against a local fake Yahoo ({@link FakeYahoo}, loopback, random port): the session handshake (cookie URL →
 *   cookie, {@code /v1/test/getcrumb} with the cookie → crumb), {@code /v7/finance/options/{SYMBOL}?crumb=…[&date=…]}
 *   with {@code date} = expiration 00:00 UTC in Unix seconds, the cookie and a browser User-Agent on every request, and
 *   401 → one re-handshake → resend;</li>
 *   <li>AC 10 like {@code bootRun} with an unreachable Yahoo: the app starts (startup pull on a trading day fails
 *   quietly), refresh reports {@code HANDSHAKE_FAILED}, bookkeeping works.</li>
 * </ul>
 * "Now" is a {@link MutableClock} bean. Never the real Yahoo / CBOE / Finnhub; the system environment and the
 * project's .env are never read. Fictional tickers.
 */
@DisplayName("FEAT-052 真实 HTTP 传输（Yahoo 握手 / cookie / crumb / date 参数；AC10 Yahoo 不可达启动）")
class Feat052YahooRealHttpSystemTest {

    static final FakeYahoo FAKE = new FakeYahoo();
    static final String CRUMB = "QaCrumb0001";
    static final String COOKIE = "A3=qa-session-cookie-7d2f";

    static void noFinnhub(DynamicPropertyRegistry r) {
        r.add("optionmgr.quotes.cboe-base-url", () -> "http://127.0.0.1:9");
        r.add("optionmgr.finnhub.base-url", () -> "http://127.0.0.1:9");
        r.add("optionmgr.finnhub.read-system-env", () -> "false");
        r.add("optionmgr.finnhub.env-files", () -> "build/qa-feat052-no-env/.env");
        r.add("optionmgr.finnhub.fetch-enabled", () -> "false");
        r.add("optionmgr.quotes.option-source", () -> "yahoo");
        r.add("optionmgr.quotes.risk-free-rate-percent", () -> "0.00");
        r.add("optionmgr.quotes.request-interval-millis", () -> "0");
    }

    static String db(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    }

    /** Unix seconds of 00:00 UTC of an ISO date (the contract's {@code date} parameter). */
    static long dateSec(String iso) {
        return java.time.LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
    }

    // ============================================================================================== fake Yahoo

    /**
     * Local fake of the three Yahoo endpoints. Records every request (path + query, Cookie, User-Agent). The cookie
     * path answers 404 with a {@code Set-Cookie} (like the real {@code fc.yahoo.com}); getcrumb answers the crumb as
     * plain text; options answer per "SYMBOL" / "SYMBOL date" key, a list of replies used in order (the last one
     * repeats). Unconfigured → 404.
     */
    static final class FakeYahoo {

        record Received(String path, String query, String cookie, String userAgent) {
        }

        record Reply(int status, String body) {
        }

        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private final Map<String, List<Reply>> replies = new ConcurrentHashMap<>();
        private final Map<String, Integer> served = new ConcurrentHashMap<>();

        FakeYahoo() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String query = ex.getRequestURI().getRawQuery();
                received.add(new Received(path, query, ex.getRequestHeaders().getFirst("Cookie"),
                        ex.getRequestHeaders().getFirst("User-Agent")));
                Reply reply;
                if (path.equals("/fc")) {
                    ex.getResponseHeaders().add("Set-Cookie", COOKIE + "; Path=/; HttpOnly");
                    reply = new Reply(404, "Not Found");
                } else if (path.equals("/v1/test/getcrumb")) {
                    reply = next("crumb");
                    if (reply == null) {
                        reply = new Reply(200, CRUMB);
                    }
                } else if (path.startsWith("/v7/finance/options/")) {
                    String symbol = path.substring("/v7/finance/options/".length());
                    String date = param(query, "date");
                    reply = next(date == null ? symbol : symbol + " " + date);
                } else {
                    reply = null;
                }
                if (reply == null) {
                    reply = new Reply(404, "{\"finance\":{\"error\":{\"code\":\"Not Found\"}}}");
                }
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                ex.close();
            });
            server.start();
        }

        private Reply next(String key) {
            List<Reply> list = replies.get(key);
            if (list == null || list.isEmpty()) {
                return null;
            }
            int i = served.merge(key, 1, Integer::sum) - 1;
            return list.get(Math.min(i, list.size() - 1));
        }

        static String param(String query, String name) {
            if (query == null) {
                return null;
            }
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p[0].equals(name)) {
                    return p.length > 1 ? p[1] : "";
                }
            }
            return null;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        void options(String key, Reply... rs) {
            replies.put(key, List.of(rs));
            served.remove(key);
        }

        void crumb(Reply... rs) {
            replies.put("crumb", List.of(rs));
            served.remove("crumb");
        }

        List<Received> received() {
            return List.copyOf(received);
        }

        void clear() {
            received.clear();
            replies.clear();
            served.clear();
        }
    }

    // ============================================================================================== base

    abstract static class Base {

        @Autowired
        TestRestTemplate http;

        @Autowired
        ObjectMapper json;

        @Autowired
        MutableClock clock;

        record Resp(int status, JsonNode body, String raw) {
        }

        Resp call(HttpMethod method, String path, String body, boolean withAccount) {
            HttpHeaders h = new HttpHeaders();
            h.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (withAccount) {
                h.set("X-Account-Id", "1");
            }
            if (body != null) {
                h.setContentType(MediaType.APPLICATION_JSON);
            }
            ResponseEntity<byte[]> r = http.exchange(path, method,
                    new HttpEntity<>(body == null ? null : body.getBytes(StandardCharsets.UTF_8), h), byte[].class);
            String raw = r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8);
            JsonNode node = null;
            if (!raw.isEmpty()) {
                try {
                    node = json.readTree(raw);
                } catch (Exception e) {
                    throw new AssertionError("not JSON: " + raw);
                }
            }
            return new Resp(r.getStatusCode().value(), node, raw);
        }

        JsonNode ok(HttpMethod method, String path, String body, boolean withAccount, int status) {
            Resp r = call(method, path, body, withAccount);
            assertThat(r.status()).as("%s %s -> %s", method, path, r.raw()).isEqualTo(status);
            return r.body();
        }

        JsonNode status() {
            return ok(HttpMethod.GET, "/api/market-data/status", null, false, 200);
        }

        JsonNode refresh() {
            return ok(HttpMethod.POST, "/api/market-data/refresh", null, false, 200);
        }

        void deposit(String amount) {
            ok(HttpMethod.POST, "/api/cash/transfers", "{\"type\":\"DEPOSIT\",\"amount\":\"" + amount
                    + "\",\"occurredAtLocal\":\"2026-11-16T10:00\",\"remark\":null}", true, 201);
        }

        void open(String action, String symbol, String exp, String strike, String type, String net, String at) {
            ObjectNode n = json.createObjectNode();
            n.put("symbol", symbol).put("expirationDate", exp).put("strike", strike).put("optionType", type)
                    .put("action", action).put("contracts", 1).put("netAmount", net).put("tradedAtLocal", at)
                    .put("multiplier", 100).putNull("note");
            ok(HttpMethod.POST, "/api/option-trades", n.toString(), true, 201);
        }

        void transferIn(String symbol, int shares) {
            ok(HttpMethod.POST, "/api/stock-trades", "{\"type\":\"TRANSFER_IN\",\"symbol\":\"" + symbol
                    + "\",\"shares\":" + shares + ",\"amount\":\"6000.00\",\"tradeDate\":\"2026-11-18\",\"note\":null}",
                    true, 201);
        }

        JsonNode optionRow(String label) {
            for (JsonNode p : ok(HttpMethod.GET, "/api/option-positions", null, true, 200).get("positions")) {
                if (p.get("contractLabel").asText().equals(label)) {
                    return p;
                }
            }
            throw new AssertionError("no position " + label);
        }

        JsonNode stockQuote(String symbol) {
            for (JsonNode p : ok(HttpMethod.GET, "/api/stock-positions", null, true, 200).get("positions")) {
                if (p.get("symbol").asText().equals(symbol)) {
                    return p.get("quote");
                }
            }
            throw new AssertionError("no stock position " + symbol);
        }

        /** "SYMBOL result optionSource yahooResult cboeResult finnhubResult". */
        static List<String> yres(JsonNode refresh) {
            List<String> out = new ArrayList<>();
            for (JsonNode r : refresh.get("results")) {
                out.add(r.get("symbol").asText() + " " + r.get("result").asText() + " "
                        + r.get("optionSource").asText() + " " + txt(r.get("yahooResult")) + " "
                        + txt(r.get("cboeResult")) + " " + txt(r.get("finnhubResult")));
            }
            return out;
        }

        static String txt(JsonNode n) {
            return n == null || n.isNull() ? "null" : n.asText();
        }
    }

    // ============================================================================================== fake Yahoo over HTTP

    /** Real-shaped Yahoo body for one expiration (Unix seconds, IV fraction, no Greeks). */
    static String yahooBody(String symbol, String price, long marketTime, String chainDate, String contractsJson) {
        String options = chainDate == null ? "[]"
                : "[{\"expirationDate\":" + dateSec(chainDate) + ",\"hasMiniOptions\":false," + contractsJson + "}]";
        return "{\"optionChain\":{\"result\":[{\"underlyingSymbol\":\"" + symbol + "\","
                + "\"expirationDates\":[" + dateSec("2026-11-27") + "," + dateSec("2026-12-18") + ","
                + dateSec("2027-02-19") + "],\"strikes\":[45,50],\"hasMiniOptions\":false,"
                + "\"quote\":{\"language\":\"en-US\",\"region\":\"US\",\"quoteType\":\"EQUITY\",\"currency\":\"USD\","
                + "\"marketState\":\"REGULAR\",\"symbol\":\"" + symbol + "\",\"regularMarketPrice\":" + price + ","
                + "\"regularMarketTime\":" + marketTime + ",\"regularMarketPreviousClose\":49.60,\"bid\":49.98,"
                + "\"ask\":50.02},\"options\":" + options + "}],\"error\":null}}";
    }

    static String contract(String occ, String strike, String bid, String ask, String last, String iv, String exp) {
        return "{\"contractSymbol\":\"" + occ + "\",\"strike\":" + strike + ",\"currency\":\"USD\",\"lastPrice\":" + last
                + ",\"change\":0.0,\"percentChange\":0.0,\"volume\":12,\"openInterest\":340,\"bid\":" + bid
                + ",\"ask\":" + ask + ",\"contractSize\":\"REGULAR\",\"expiration\":" + dateSec(exp)
                + ",\"lastTradeDate\":1795100000,\"impliedVolatility\":" + iv + ",\"inTheMoney\":false}";
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=false", "optionmgr.quotes.transport=http"})
    @Import(FakeYahooOverHttp.Clock.class)
    @DisplayName("本地假 Yahoo（真实 HTTP）")
    class FakeYahooOverHttp extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat052HttpClock() {
                return new MutableClock(Instant.parse("2026-11-20T14:50:00Z")); // Fri 09:50 New York
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            noFinnhub(r);
            r.add("optionmgr.quotes.yahoo.base-url", FAKE::baseUrl);
            r.add("optionmgr.quotes.yahoo.cookie-url", () -> FAKE.baseUrl() + "/fc");
            r.add("spring.datasource.url", () -> db("qa-feat052-http"));
        }

        void configure() {
            long t = Instant.parse("2026-11-20T15:00:00Z").getEpochSecond();
            FAKE.options("QRTV " + dateSec("2027-02-19"), new FakeYahoo.Reply(200, yahooBody("QRTV", "50.00", t,
                    "2027-02-19", "\"calls\":[],\"puts\":[" + contract("QRTV270219P00050000", "50", "3.90", "4.10",
                            "3.95", "0.3", "2027-02-19") + "]")));
            FAKE.options("QRTV " + dateSec("2026-12-18"), new FakeYahoo.Reply(200, yahooBody("QRTV", "50.00", t,
                    "2026-12-18", "\"calls\":[],\"puts\":[" + contract("QRTV261218P00045000", "45", "0.60", "0.70",
                            "0.62", "0.35", "2026-12-18") + "]")));
            FAKE.options("IXQZ", new FakeYahoo.Reply(200, yahooBody("IXQZ", "61.10", t, "2026-11-27",
                    "\"calls\":[],\"puts\":[]")));
        }

        @Test
        @DisplayName("握手：GET cookie URL（404 也行）→ 带 cookie GET /v1/test/getcrumb → 带 cookie + crumb GET /v7/finance/options/{SYMBOL}，date = 到期日 00:00 UTC 秒（2027-02-19 → 1802995200）；每个请求带浏览器 User-Agent；401 → 重新握手一次后重发")
        void handshakeCookieCrumbAndDateParameter() {
            FAKE.clear();
            configure();
            deposit("20000.00");
            open("STO", "QRTV", "2027-02-19", "50", "PUT", "380.00", "2026-11-19T10:00");
            open("STO", "QRTV", "2026-12-18", "45", "PUT", "70.00", "2026-11-19T10:05");
            transferIn("IXQZ", 100);
            assertThat(dateSec("2027-02-19")).as("contract example").isEqualTo(1802995200L);

            clock.set(Instant.parse("2026-11-20T15:00:00Z"));
            JsonNode r = refresh();
            assertThat(yres(r)).containsExactly("IXQZ SUCCESS YAHOO SUCCESS null null",
                    "QRTV SUCCESS YAHOO SUCCESS null null");

            List<FakeYahoo.Received> rec = FAKE.received();
            List<String> paths = rec.stream().map(FakeYahoo.Received::path).toList();
            assertThat(paths).containsExactly("/fc", "/v1/test/getcrumb", "/v7/finance/options/IXQZ",
                    "/v7/finance/options/QRTV", "/v7/finance/options/QRTV");
            assertThat(FakeYahoo.param(rec.get(2).query(), "crumb")).isEqualTo(CRUMB);
            assertThat(FakeYahoo.param(rec.get(2).query(), "date")).as("stock-only symbol: no date").isNull();
            assertThat(FakeYahoo.param(rec.get(3).query(), "date")).isEqualTo(String.valueOf(dateSec("2026-12-18")));
            assertThat(FakeYahoo.param(rec.get(4).query(), "date")).isEqualTo("1802995200");
            assertThat(FakeYahoo.param(rec.get(4).query(), "crumb")).isEqualTo(CRUMB);
            for (FakeYahoo.Received x : rec) {
                assertThat(x.userAgent()).as("browser UA on %s", x.path()).isNotBlank().contains("Mozilla/5.0");
                if (!x.path().equals("/fc")) {
                    assertThat(x.cookie()).as("session cookie on %s", x.path()).contains(COOKIE);
                }
            }

            JsonNode p1 = optionRow("QRTV 2027-02-19 50 P");
            assertThat(p1.get("optionQuote").get("mark").asText()).isEqualTo("4.00");
            assertThat(p1.get("optionQuote").get("source").asText()).isEqualTo("YAHOO");
            assertThat(p1.get("optionQuote").get("quoteTimeLocal").asText()).isEqualTo("2026-11-20T10:00:00");
            assertThat(p1.get("greeks").get("deltaPerContract").asText()).isEqualTo("-0.4701");
            assertThat(stockQuote("IXQZ").get("price").asText()).isEqualTo("61.10");
            assertThat(status().get("optionSource").asText()).isEqualTo("YAHOO");

            // second run (after the 60 s cooldown): session reused; IXQZ 401 → re-handshake → resend OK
            FAKE.options("IXQZ", new FakeYahoo.Reply(401, "{\"finance\":{\"error\":{\"code\":\"Unauthorized\"}}}"),
                    new FakeYahoo.Reply(200, yahooBody("IXQZ", "61.50", Instant.parse("2026-11-20T15:02:00Z")
                            .getEpochSecond(), null, "")));
            int before = FAKE.received().size();
            clock.set(Instant.parse("2026-11-20T15:02:00Z"));
            r = refresh();
            List<String> after = FAKE.received().subList(before, FAKE.received().size()).stream()
                    .map(FakeYahoo.Received::path).toList();
            assertThat(after).containsExactly("/v7/finance/options/IXQZ", "/fc", "/v1/test/getcrumb",
                    "/v7/finance/options/IXQZ", "/v7/finance/options/QRTV", "/v7/finance/options/QRTV");
            assertThat(yres(r)).contains("IXQZ SUCCESS YAHOO SUCCESS null null");
            assertThat(stockQuote("IXQZ").get("price").asText()).isEqualTo("61.50");
            for (JsonNode res : r.get("results")) {
                if (!res.get("failureDetail").isNull()) {
                    assertThat(res.get("failureDetail").asText()).doesNotContain(CRUMB).doesNotContain(COOKIE);
                }
            }
        }
    }

    // ============================================================================================== AC 10

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=true", "optionmgr.quotes.transport=http"})
    @Import(YahooUnreachableStartup.Clock.class)
    @DisplayName("AC10 Yahoo 不可达（像 bootRun 一样开启定时 / 启动拉取，交易日 10:00 启动）")
    class YahooUnreachableStartup extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat052Ac10Clock() {
                return new MutableClock(Instant.parse("2026-11-20T15:00:00Z")); // Fri 10:00 New York
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            noFinnhub(r);
            r.add("optionmgr.quotes.yahoo.base-url", () -> "http://127.0.0.1:9");
            r.add("optionmgr.quotes.yahoo.cookie-url", () -> "http://127.0.0.1:9/");
            r.add("spring.datasource.url", () -> db("qa-feat052-ac10"));
        }

        @Test
        @DisplayName("AC10 后端正常启动（启动拉取失败不阻塞）；立即刷新 → 所有标的 HANDSHAKE_FAILED；持仓 无报价 + 行情未更新；录入交易照常保存")
        void startsWithUnreachableYahoo() throws Exception {
            assertThat(ok(HttpMethod.GET, "/api/health", null, false, 200).get("status").asText()).isEqualTo("UP");
            JsonNode s = null;
            for (int i = 0; i < 200; i++) {
                s = status();
                if (!s.get("lastAttemptAt").isNull()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(s.get("optionSource").asText()).isEqualTo("YAHOO");
            assertThat(s.get("riskFreeRatePercent").asText()).isEqualTo("0.00");

            deposit("20000.00");
            open("STO", "QRTV", "2027-02-19", "50", "PUT", "380.00", "2026-11-19T10:00");
            transferIn("IXQZ", 100);
            clock.set(Instant.parse("2026-11-20T15:05:00Z"));
            JsonNode r = refresh();
            assertThat(r.get("performed").asBoolean()).isTrue();
            assertThat(yres(r)).containsExactly("IXQZ FAILED YAHOO HANDSHAKE_FAILED null NO_KEY",
                    "QRTV FAILED YAHOO HANDSHAKE_FAILED null NO_KEY");
            JsonNode p1 = optionRow("QRTV 2027-02-19 50 P");
            assertThat(p1.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
            assertThat(p1.get("optionQuote").get("staleLabel").asText()).isEqualTo("行情未更新（从未成功）");
            // FEAT-016 "run 成功": the startup run at 10:00 had no tracked symbols → success (lastSuccessAt 10:00), so
            // the top warning follows the 90-minute rule, not "从未成功获取"
            s = status();
            assertThat(s.get("lastSuccessAt").asText()).as("empty startup run = success").isEqualTo("2026-11-20T15:00:00Z");
            assertThat(s.get("lastAttemptAt").asText()).isEqualTo("2026-11-20T15:05:00Z");
            assertThat(s.get("stale").asBoolean()).isFalse();
            assertThat(s.get("warning").isNull()).isTrue();
            clock.set(Instant.parse("2026-11-20T16:31:00Z")); // 11:31 New York, 91 minutes after the last success
            assertThat(status().get("warning").asText()).isEqualTo("行情未更新（上次：2026-11-20 10:00 美东）");

            clock.set(Instant.parse("2026-11-20T15:10:00Z"));
            open("STO", "QRTV", "2027-01-15", "45", "PUT", "90.00", "2026-11-20T10:09");
            assertThat(optionRow("QRTV 2027-01-15 45 P").get("optionQuote").get("mark").isNull()).isTrue();
        }
    }
}
