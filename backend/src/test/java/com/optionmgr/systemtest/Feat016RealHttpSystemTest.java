package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * QA system tests for FEAT-016 with the real HTTP transport (no {@code e2e} profile, no test-support endpoints):
 * AC 10 (start like {@code bootRun} with no Finnhub key and an unreachable CBOE), the real request paths, BigDecimal
 * parsing and "key only in the X-Finnhub-Token header" against a local fake ({@link QaFakeQuoteServer}), and Q147
 * (startup pull only on a trading day between 09:30 and 16:20). "Now" is a {@link MutableClock} bean. Never the real
 * CBOE / Finnhub; the system environment and the project's .env are never read.
 */
@DisplayName("FEAT-016 真实 HTTP 传输（AC10、key 只在请求头、启动拉取 Q147）")
class Feat016RealHttpSystemTest {

    static final String KEY = "qa-feat016-key-7f3a";
    static final QaFakeQuoteServer FAKE = new QaFakeQuoteServer();
    static final Path ENV_DIR;
    static final Path ENV_WITH_KEY;

    static {
        try {
            ENV_DIR = Files.createTempDirectory("feat016-env");
            ENV_WITH_KEY = ENV_DIR.resolve("with-key.env");
            Files.writeString(ENV_WITH_KEY, "# QA test only\nFINNHUB_API_KEY=" + KEY + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(ENV_WITH_KEY);
        Files.deleteIfExists(ENV_DIR);
    }

    static void noKeyUnreachable(DynamicPropertyRegistry r) {
        r.add("optionmgr.quotes.cboe-base-url", () -> "http://127.0.0.1:9");
        r.add("optionmgr.finnhub.base-url", () -> "http://127.0.0.1:9");
        r.add("optionmgr.finnhub.read-system-env", () -> "false");
        r.add("optionmgr.finnhub.env-files", () -> "build/qa-feat016-no-env/.env");
        r.add("optionmgr.finnhub.fetch-enabled", () -> "false");
    }

    static void fakeWithKey(DynamicPropertyRegistry r) {
        r.add("optionmgr.quotes.cboe-base-url", FAKE::baseUrl);
        r.add("optionmgr.finnhub.base-url", FAKE::baseUrl);
        r.add("optionmgr.finnhub.read-system-env", () -> "false");
        r.add("optionmgr.finnhub.env-files", ENV_WITH_KEY::toString);
        r.add("optionmgr.finnhub.fetch-enabled", () -> "false");
    }

    static String db(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    }

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
                    + "\",\"shares\":" + shares + ",\"amount\":\"6000.00\",\"tradeDate\":\"2026-11-19\",\"note\":null}",
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

        static List<String> results(JsonNode refresh) {
            List<String> out = new ArrayList<>();
            for (JsonNode r : refresh.get("results")) {
                out.add(r.get("symbol").asText() + " " + r.get("result").asText() + " " + r.get("cboeResult").asText()
                        + " " + (r.get("finnhubResult").isNull() ? "null" : r.get("finnhubResult").asText()));
            }
            return out;
        }
    }

    // ============================================================================================== AC 10

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=true"})
    @Import(Ac10NoKeyUnreachable.Clock.class)
    @DisplayName("AC10 无 FINNHUB_API_KEY、CBOE 不可达、从未成功（像 bootRun 一样开启定时 / 启动拉取）")
    class Ac10NoKeyUnreachable extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat016Ac10Clock() {
                return new MutableClock(Instant.parse("2026-11-21T15:00:00Z")); // Sat 10:00 New York
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            noKeyUnreachable(r);
            r.add("spring.datasource.url", () -> db("qa-feat016-ac10"));
        }

        @Test
        @DisplayName("AC10 正常启动；所有持仓无报价、行情未更新（从未成功）；顶部 行情未更新（从未成功获取）；STO QRTV 2027-01-15 45P 照常保存")
        void startsAndKeepsBookkeeping() throws Exception {
            assertThat(ok(HttpMethod.GET, "/api/health", null, false, 200).get("status").asText()).isEqualTo("UP");
            Thread.sleep(1000);
            JsonNode s = status();
            assertThat(s.get("lastAttemptAt").isNull()).as("no startup pull on a Saturday").isTrue();
            assertThat(s.get("stale").asBoolean()).as("no tracked symbols").isFalse();

            deposit("20000.00");
            open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:00");
            transferIn("IXQZ", 100);
            s = status();
            assertThat(s.get("stale").asBoolean()).isTrue();
            assertThat(s.get("warning").asText()).isEqualTo("行情未更新（从未成功获取）");
            assertThat(s.get("trackedSymbolCount").asInt()).isEqualTo(2);

            clock.set(Instant.parse("2026-11-23T15:00:00Z")); // Mon 10:00 New York
            JsonNode r = ok(HttpMethod.POST, "/api/market-data/refresh", null, false, 200);
            assertThat(r.get("performed").asBoolean()).isTrue();
            assertThat(results(r)).containsExactly("IXQZ FAILED NETWORK_ERROR NO_KEY",
                    "QRTV FAILED NETWORK_ERROR NO_KEY");

            JsonNode p1 = optionRow("QRTV 2026-12-18 50 P");
            assertThat(p1.get("optionQuote").get("mark").isNull()).isTrue();
            assertThat(p1.get("optionQuote").get("contractStatus").asText()).isEqualTo("NEVER_QUOTED");
            assertThat(p1.get("optionQuote").get("staleLabel").asText()).isEqualTo("行情未更新（从未成功）");
            assertThat(p1.get("underlyingQuote").get("price").isNull()).isTrue();
            JsonNode stock = stockQuote("IXQZ");
            assertThat(stock.get("price").isNull()).isTrue();
            assertThat(stock.get("staleLabel").asText()).isEqualTo("行情未更新（从未成功）");
            s = status();
            assertThat(s.get("stale").asBoolean()).isTrue();
            assertThat(s.get("warning").asText()).isEqualTo("行情未更新（从未成功获取）");

            clock.set(Instant.parse("2026-11-23T15:10:00Z"));
            open("STO", "QRTV", "2027-01-15", "45", "PUT", "90.00", "2026-11-23T10:05");
            assertThat(optionRow("QRTV 2027-01-15 45 P").get("optionQuote").get("mark").isNull()).isTrue();
        }
    }

    // ============================================================================================== real HTTP + key

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=false"})
    @Import(FakeSourcesWithKey.Clock.class)
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("本地假 CBOE / Finnhub（真实 HTTP），.env 中有 key")
    class FakeSourcesWithKey extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat016HttpClock() {
                return new MutableClock(Instant.parse("2026-11-23T14:50:00Z")); // Mon 09:50 New York
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            fakeWithKey(r);
            r.add("spring.datasource.url", () -> db("qa-feat016-http"));
        }

        @Test
        @DisplayName("CBOE 路径 /api/global/delayed_quotes/options/QRTV.json；IXQZ 500 → Finnhub /api/v1/quote?symbol=IXQZ，key 只在 X-Finnhub-Token；BigDecimal 中间价 0.075；响应和日志中都没有 key")
        void realTransport(CapturedOutput output) {
            FAKE.clear();
            FAKE.cboe("QRTV", 200, "{\"timestamp\":\"2026-11-23 09:45:00\",\"data\":{\"symbol\":\"QRTV\","
                    + "\"current_price\":52.40,\"bid\":52.38,\"ask\":52.42,\"prev_day_close\":51.90,\"iv30\":31.2,"
                    + "\"options\":[{\"option\":\"QRTV261218P00050000\",\"bid\":1.20,\"ask\":1.30,\"theo\":1.24,"
                    + "\"iv\":0.3512,\"delta\":-0.3021,\"theta\":-0.0456,\"volume\":120,\"open_interest\":3400},"
                    + "{\"option\":\"QRTV261218P00045000\",\"bid\":0.07,\"ask\":0.08}]}}");
            FAKE.cboe("IXQZ", 500, "{\"error\":\"boom\"}");
            FAKE.finnhub("IXQZ", 200, "{\"c\":61.05,\"pc\":60.80,\"t\":"
                    + Instant.parse("2026-11-23T14:59:58Z").getEpochSecond() + "}");

            deposit("20000.00");
            open("STO", "QRTV", "2026-12-18", "50", "PUT", "120.00", "2026-11-20T10:00");
            open("BTO", "QRTV", "2026-12-18", "45", "PUT", "10.00", "2026-11-20T10:01");
            transferIn("IXQZ", 100);

            clock.set(Instant.parse("2026-11-23T15:00:00Z"));
            Resp refresh = call(HttpMethod.POST, "/api/market-data/refresh", null, false);
            assertThat(refresh.status()).isEqualTo(200);
            assertThat(results(refresh.body())).containsExactly("IXQZ FALLBACK HTTP_ERROR SUCCESS",
                    "QRTV SUCCESS SUCCESS null");

            List<String> uris = FAKE.received().stream().map(QaFakeQuoteServer.Received::uri).toList();
            assertThat(uris).containsExactlyInAnyOrder("/api/global/delayed_quotes/options/QRTV.json",
                    "/api/global/delayed_quotes/options/IXQZ.json", "/api/v1/quote?symbol=IXQZ");
            for (QaFakeQuoteServer.Received rec : FAKE.received()) {
                assertThat(rec.uri()).doesNotContain(KEY);
                if (rec.uri().startsWith("/api/v1/quote")) {
                    assertThat(rec.token()).isEqualTo(KEY);
                } else {
                    assertThat(rec.token()).as("CBOE gets no Finnhub key").isNull();
                }
            }

            JsonNode p1 = optionRow("QRTV 2026-12-18 50 P");
            assertThat(p1.get("optionQuote").get("mark").asText()).isEqualTo("1.25");
            assertThat(p1.get("optionQuote").get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:45:00");
            assertThat(p1.get("underlyingQuote").get("price").asText()).isEqualTo("52.40");
            assertThat(optionRow("QRTV 2026-12-18 45 P").get("optionQuote").get("mark").asText())
                    .as("exact BigDecimal mid of 0.07 / 0.08").isEqualTo("0.075");
            JsonNode stock = stockQuote("IXQZ");
            assertThat(stock.get("price").asText()).isEqualTo("61.05");
            assertThat(stock.get("source").asText()).isEqualTo("FINNHUB");
            assertThat(stock.get("quoteTimeLocal").asText()).isEqualTo("2026-11-23T09:59:58");

            List<String> bodies = List.of(refresh.raw(),
                    call(HttpMethod.GET, "/api/market-data/status", null, false).raw(),
                    call(HttpMethod.GET, "/api/option-positions", null, true).raw(),
                    call(HttpMethod.GET, "/api/stock-positions", null, true).raw(),
                    call(HttpMethod.PUT, "/api/market-data/overrides/underlyings/NOPE", "{\"price\":\"1\"}", false)
                            .raw());
            for (String b : bodies) {
                assertThat(b).isNotEmpty().doesNotContain(KEY);
            }
            for (JsonNode res : refresh.body().get("results")) {
                if (!res.get("failureDetail").isNull()) {
                    assertThat(res.get("failureDetail").asText()).doesNotContain(KEY).doesNotContain("symbol=");
                }
            }
            assertThat(output.getAll()).doesNotContain(KEY);
        }
    }

    // ============================================================================================== Q147 startup

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=true"})
    @Import(StartupOnTradingDay.Clock.class)
    @DisplayName("Q147 交易日 10:00 启动 → 后台立即拉一次")
    class StartupOnTradingDay extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat016StartupClock() {
                return new MutableClock(Instant.parse("2026-11-23T15:00:00Z")); // Mon 10:00 New York
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            noKeyUnreachable(r);
            r.add("spring.datasource.url",
                    () -> db("qa-feat016-startup"));
        }

        @Test
        @DisplayName("Q147 启动后 lastAttemptAt = 启动时刻（不阻塞启动）")
        void pullsAtStartup() throws Exception {
            JsonNode s = null;
            for (int i = 0; i < 200; i++) {
                s = status();
                if (!s.get("lastAttemptAt").isNull()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(s.get("lastAttemptAt").asText()).isEqualTo("2026-11-23T15:00:00Z");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "optionmgr.quotes.fetch-enabled=true"})
    @Import(StartupOnThanksgiving.Clock.class)
    @DisplayName("Q147 感恩节 10:00 启动 → 不拉取")
    class StartupOnThanksgiving extends Base {

        @TestConfiguration
        static class Clock {
            @Bean
            @Primary
            MutableClock qaFeat016ThanksgivingClock() {
                return new MutableClock(Instant.parse("2026-11-26T15:00:00Z")); // Thu 10:00 New York, NYSE closed
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            noKeyUnreachable(r);
            r.add("spring.datasource.url",
                    () -> db("qa-feat016-thanksgiving"));
        }

        @Test
        @DisplayName("Q147 非交易日启动不拉取（lastAttemptAt 保持 null）")
        void noPullOnHoliday() throws Exception {
            Thread.sleep(2000);
            assertThat(status().get("lastAttemptAt").isNull()).isTrue();
        }
    }
}
