package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.OptionMgrApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * FEAT-033 migration cases complementing backend-dev's MigrationFromM1Test (which covers scenario M, a failing
 * migration and a failing backup): an M1 database with no data at all, one with only deposits / withdrawals, a
 * second start of an already migrated database (no second backup, data unchanged) and id continuity after the
 * upgrade. Only temporary file databases under a JUnit temp dir are used - never backend/data.
 */
class Feat033MigrationSystemTest {

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    static final class ExcludeTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            var meta = reader.getAnnotationMetadata();
            return meta.hasAnnotation(TestConfiguration.class.getName())
                    || meta.hasAnnotation(TestComponent.class.getName());
        }

        @Override
        public boolean equals(Object o) {
            return o != null && o.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(OptionMgrApplication.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("qaExcludeTestConfigurations",
                        new ExcludeTestConfigurations()))
                .run("--spring.datasource.url=" + url, "--server.port=0", "--spring.profiles.active=test");
    }

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    private static void migrateToM1(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("4").load().migrate();
    }

    private static void exec(String url, String... sql) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", ""); Statement st = con.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    private static long count(String url, String sql) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", "");
             Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> backups(String name) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(f -> f.startsWith(name + "-backup-before-V5-")).toList();
        }
    }

    private JsonNode call(MockMvc mvc, MockHttpServletRequestBuilder req, Long account, int status) throws Exception {
        if (account != null) {
            req.header("X-Account-Id", account);
        }
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? null : json.readTree(body);
    }

    private static MockMvc mvc(ConfigurableApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build();
    }

    private static String transfer(String type, String amount, String at) {
        return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"occurredAtLocal\":\"" + at + "\"}";
    }

    @Test
    @DisplayName("没有任何数据的 M1 文件库：备份后迁移，只剩默认账户，各页面为空；新记录 id 从 1 开始，新账户从零开始")
    void emptyM1Database() throws Exception {
        String url = fileUrl("emptydb");
        migrateToM1(url);
        assertThat(backups("emptydb")).isEmpty();
        try (ConfigurableApplicationContext c = start(url)) {
            MockMvc m = mvc(c);
            JsonNode accounts = call(m, get("/api/accounts"), null, 200);
            assertThat(accounts).hasSize(1);
            assertThat(accounts.get(0).get("id").asLong()).isEqualTo(1);
            assertThat(accounts.get(0).get("name").asText()).isEqualTo("默认账户");
            assertThat(call(m, get("/api/cash/summary"), 1L, 200).get("totalCash").asText()).isEqualTo("0.00");
            assertThat(call(m, get("/api/option-trades"), 1L, 200).get("totalItems").asInt()).isZero();
            assertThat(call(m, get("/api/audit-log"), 1L, 200).get("totalItems").asInt()).isZero();
            JsonNode dep = call(m, post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON)
                    .content(transfer("DEPOSIT", "10.00", "2026-11-02T10:00")), 1L, 201);
            assertThat(dep.get("id").asLong()).isEqualTo(1);
            JsonNode acc = call(m, post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"小账户\"}"), null, 201);
            assertThat(acc.get("id").asLong()).isEqualTo(2);
            assertThat(call(m, get("/api/cash/summary"), 2L, 200).get("totalCash").asText()).isEqualTo("0.00");
        }
        assertThat(backups("emptydb")).hasSize(1);
    }

    @Test
    @DisplayName("只有入金/出金的 M1 文件库：迁移后默认账户数字、id、余额不变；再次启动不再备份、数据不变；新记录 id 接着往后")
    void depositsOnlyM1Database() throws Exception {
        String url = fileUrl("cashdb");
        migrateToM1(url);
        // ids as M1 would have produced them, including a gap (a deleted record) and same-instant entry order
        exec(url,
                "INSERT INTO cash_transfer (id, type, amount, occurred_at, remark, created_at) VALUES "
                        + "(1, 'DEPOSIT', 1000.00, TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00', '初始', TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00'),"
                        + "(3, 'WITHDRAWAL', 400.00, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', NULL, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00'),"
                        + "(4, 'DEPOSIT', 250.50, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', '同一时间', TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00')",
                "INSERT INTO cash_ledger_entry (id, occurred_at, source_type, source_id, amount, frozen_change, description, remark, created_at) VALUES "
                        + "(1, TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00', 'DEPOSIT', 1, 1000.00, 0, NULL, '初始', TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00'),"
                        + "(3, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', 'WITHDRAWAL', 3, -400.00, 0, NULL, NULL, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00'),"
                        + "(4, TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', 'DEPOSIT', 4, 250.50, 0, NULL, '同一时间', TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00')",
                "ALTER TABLE cash_transfer ALTER COLUMN id RESTART WITH 5",
                "ALTER TABLE cash_ledger_entry ALTER COLUMN id RESTART WITH 5");

        String ledgerAfterFirstStart;
        try (ConfigurableApplicationContext c = start(url)) {
            MockMvc m = mvc(c);
            JsonNode s = call(m, get("/api/cash/summary"), 1L, 200);
            assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(), s.get("availableCash").asText()))
                    .containsExactly("850.50", "0.00", "850.50");
            JsonNode ledger = call(m, get("/api/cash/ledger"), 1L, 200);
            assertThat(ledger.get("totalItems").asInt()).isEqualTo(3);
            List<String> rows = new java.util.ArrayList<>();
            ledger.get("items").forEach(i -> rows.add(i.get("id").asLong() + " " + i.get("sourceType").asText() + " "
                    + i.get("amount").asText() + " " + i.get("balanceAfter").asText() + " "
                    + (i.get("remark").isNull() ? "-" : i.get("remark").asText())));
            assertThat(rows).containsExactly(
                    "4 DEPOSIT 250.50 850.50 同一时间",
                    "3 WITHDRAWAL -400.00 600.00 -",
                    "1 DEPOSIT 1000.00 1000.00 初始");
            ledgerAfterFirstStart = ledger.toString();
            // a new record continues the ids; editing a migrated transfer works in the default account
            JsonNode dep = call(m, post("/api/cash/transfers").contentType(MediaType.APPLICATION_JSON)
                    .content(transfer("DEPOSIT", "1.00", "2026-11-04T10:00")), 1L, 201);
            assertThat(dep.get("id").asLong()).isEqualTo(5);
            assertThat(dep.get("ledgerEntryId").asLong()).isEqualTo(5);
            call(m, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cash/transfers/5"),
                    1L, 204);
            // the migrated records are invisible from a new account
            long sid = call(m, post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"B\"}"), null, 201).get("id").asLong();
            call(m, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cash/transfers/1"),
                    sid, 404);
        }
        assertThat(backups("cashdb")).hasSize(1);
        assertThat(count(url, "SELECT COUNT(*) FROM cash_transfer WHERE account_id <> 1")).isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM cash_ledger_entry WHERE account_id <> 1")).isZero();

        // second start: nothing pending -> no new backup, same data
        try (ConfigurableApplicationContext c = start(url)) {
            MockMvc m = mvc(c);
            JsonNode ledger = call(m, get("/api/cash/ledger"), 1L, 200);
            JsonNode before = json.readTree(ledgerAfterFirstStart);
            assertThat(ledger.get("items")).isEqualTo(before.get("items"));
            assertThat(call(m, get("/api/accounts"), null, 200)).hasSize(2);
        }
        assertThat(backups("cashdb")).hasSize(1);
        assertThat(count(url, "SELECT MAX(version) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(5);
    }
}
