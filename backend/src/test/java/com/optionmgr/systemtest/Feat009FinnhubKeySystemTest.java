package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.FakeFinnhub;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * QA system tests for FEAT-009 AC 7 and AC 9: the real startup fetch (profile "test" with
 * {@code optionmgr.finnhub.fetch-enabled=true}) against a local fake Finnhub ({@link FakeFinnhub}, never the real
 * one). The key comes from a temporary {@code .env} file ({@code optionmgr.finnhub.env-files}); the system environment
 * is not read ({@code read-system-env=false}), so a real key on the machine can never be used. The project's own
 * {@code .env} is never read or touched. Everything is checked over REST on a random port.
 */
@DisplayName("FEAT-009 AC7 / AC9：启动拉取、.env 中的 key（本地假 Finnhub）")
class Feat009FinnhubKeySystemTest {

    static final String KEY = "test-key-123";
    static final FakeFinnhub FINNHUB = new FakeFinnhub();
    static final Path ENV_DIR;
    static final Path ENV_WITH_KEY;
    static final Path ENV_WITHOUT_KEY;

    static {
        try {
            ENV_DIR = Files.createTempDirectory("feat009-env");
            ENV_WITH_KEY = ENV_DIR.resolve("with-key.env");
            Files.writeString(ENV_WITH_KEY, "# QA test only\nOTHER=1\nFINNHUB_API_KEY=" + KEY + "\n",
                    StandardCharsets.UTF_8);
            ENV_WITHOUT_KEY = ENV_DIR.resolve("without-key.env");
            Files.writeString(ENV_WITHOUT_KEY, "OTHER=1\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FINNHUB.respond(200, FakeFinnhub.scenario());
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(ENV_WITH_KEY);
        Files.deleteIfExists(ENV_WITHOUT_KEY);
        Files.deleteIfExists(ENV_DIR);
    }

    static void common(DynamicPropertyRegistry r) {
        r.add("optionmgr.finnhub.fetch-enabled", () -> "true");
        r.add("optionmgr.finnhub.read-system-env", () -> "false");
        r.add("optionmgr.finnhub.base-url", FINNHUB::baseUrl);
    }

    abstract static class Base {

        @Autowired
        TestRestTemplate http;

        @Autowired
        ObjectMapper json;

        String body(String path, boolean withAccount) {
            HttpHeaders h = new HttpHeaders();
            if (withAccount) {
                h.set("X-Account-Id", "1");
            }
            ResponseEntity<byte[]> r = http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
            return r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8);
        }

        JsonNode awaitAttempt() throws Exception {
            for (int i = 0; i < 200; i++) {
                JsonNode s = json.readTree(body("/api/trading-calendar/status", false));
                if (!s.get("lastAttemptResult").isNull()) {
                    return s;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no fetch attempt after startup");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:h2:mem:qa-feat009-key;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("AC9 .env 中 FINNHUB_API_KEY=test-key-123")
    class WithKeyInEnvFile extends Base {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            common(r);
            r.add("optionmgr.finnhub.env-files", ENV_WITH_KEY::toString);
        }

        @Test
        @DisplayName("AC9 请求头 X-Finnhub-Token = test-key-123，URL 不含；日志与各 API 响应（含错误响应）都找不到 key；拉取成功")
        void keyOnlyInHeader(CapturedOutput output) throws Exception {
            JsonNode s = awaitAttempt();
            assertThat(s.get("lastAttemptResult").asText()).isEqualTo("SUCCESS");
            assertThat(s.get("stale").asBoolean()).isFalse();

            List<FakeFinnhub.Received> received = FINNHUB.received().stream()
                    .filter(r -> KEY.equals(r.token())).toList();
            assertThat(received).as("requests carrying the key").isNotEmpty();
            for (FakeFinnhub.Received r : received) {
                assertThat(r.uri()).startsWith("/api/v1/stock/market-holiday").contains("exchange=US")
                        .doesNotContain(KEY).doesNotContainIgnoringCase("token");
            }

            List<String> responses = List.of(
                    body("/api/trading-calendar/status", false),
                    body("/api/trading-calendar/non-trading-days?from=2026-11-01&to=2026-11-30", false),
                    body("/api/trading-calendar/non-trading-days?from=2026-12-01&to=2026-11-01", false),
                    body("/api/trading-calendar/non-trading-days", false),
                    body("/api/option-positions", true),
                    body("/api/cash/summary", true),
                    body("/api/health", false),
                    body("/v3/api-docs", false),
                    body("/api/nope", true));
            for (String r : responses) {
                assertThat(r).isNotEmpty().doesNotContain(KEY);
            }
            assertThat(output.getAll()).doesNotContain(KEY);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:h2:mem:qa-feat009-nokey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
    @DisplayName("AC7 .env 没有 FINNHUB_API_KEY")
    class WithoutKey extends Base {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            common(r);
            r.add("optionmgr.finnhub.env-files", ENV_WITHOUT_KEY::toString);
        }

        @Test
        @DisplayName("AC7 正常启动；状态 NO_KEY、从未成功提示；11-26 按规则仍是非交易日")
        void startsWithoutKey() throws Exception {
            JsonNode s = awaitAttempt();
            assertThat(s.get("lastAttemptResult").asText()).isEqualTo("NO_KEY");
            assertThat(s.get("stale").asBoolean()).isTrue();
            assertThat(s.get("warning").asText()).isEqualTo("休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）");
            assertThat(s.get("coveredYears")).isEmpty();
            JsonNode days = json.readTree(body("/api/trading-calendar/non-trading-days?from=2026-11-26&to=2026-11-26",
                    false)).get("days");
            assertThat(days).hasSize(1);
            assertThat(days.get(0).get("source").asText()).isEqualTo("RULES");
            assertThat(json.readTree(body("/api/health", false)).get("status").asText()).isEqualTo("UP");
        }
    }
}
