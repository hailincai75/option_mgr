package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.OptionMgrApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * FEAT-033 acceptance 1 and 2: an M1-schema database (Flyway V1-V4) holding scenario M is upgraded by the
 * application (backup, then V5) and every read endpoint of the default account returns exactly what it returned
 * before; a failing migration or a failing backup leaves a restorable database and the application does not start.
 *
 * <p>How the M1 data is produced: scenario M is entered through the API of the current code (account 1) in a
 * scratch in-memory database; its rows (all columns except the new {@code account_id}) are copied with their ids
 * into a fresh file database migrated only to V4 - the M1 schema. The endpoint responses captured before are the
 * expected results after the upgrade.
 */
class MigrationFromM1Test {

    static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");
    static final String[] DATA_TABLES = {"cash_transfer", "cash_ledger_entry", "option_trade", "audit_record"};

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock migrationTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static ConfigurableApplicationContext start(String url, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + url, "--server.port=0",
                "--spring.profiles.active=test"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(OptionMgrApplication.class, FixedClock.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("excludeTestConfigurations",
                        new ExcludeTestConfigurations()))
                .run(args.toArray(String[]::new));
    }

    /**
     * Outside the Spring test framework nothing excludes the test classes' own @TestConfiguration classes from
     * component scanning (they would add several @Primary clocks); this filter does.
     */
    static final class ExcludeTestConfigurations extends org.springframework.boot.context.TypeExcludeFilter {
        @Override
        public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                             org.springframework.core.type.classreading.MetadataReaderFactory factory) {
            var meta = reader.getAnnotationMetadata();
            return meta.hasAnnotation(TestConfiguration.class.getName())
                    || meta.hasAnnotation(org.springframework.boot.test.context.TestComponent.class.getName());
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

    static MockMvc mvc(ConfigurableApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build();
    }

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    private static void migrateToM1(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("4").load().migrate();
    }

    /** Scenario M through the API into a scratch in-memory DB; returns its rows per table and the API snapshot. */
    private record Source(Map<String, List<Map<String, Object>>> rows, Map<String, JsonNode> snapshot) {
    }

    private Source buildSource() throws Exception {
        String url = "jdbc:h2:mem:m1src-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (ConfigurableApplicationContext a = start(url)) {
            ScenarioM m = new ScenarioM(mvc(a), a.getBean(ObjectMapper.class), 1);
            m.build();
            Map<String, JsonNode> snapshot = m.snapshot();
            JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
            Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
            for (String table : DATA_TABLES) {
                List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
                list.forEach(r -> {
                    r.remove("account_id");
                    r.remove("has_audit_log"); // V6
                    r.remove("assignment_fee"); // V9 (FEAT-013)
                    r.remove("coverage_status"); // V11 (FEAT-039)
                    r.remove("entry_seq"); // V11 (FEAT-039)
                });
                rows.put(table, list);
            }
            return new Source(rows, snapshot);
        }
    }

    private static void insertRows(String url, Map<String, List<Map<String, Object>>> rows) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            for (var e : rows.entrySet()) {
                long maxId = 0;
                for (Map<String, Object> row : e.getValue()) {
                    List<String> cols = new ArrayList<>(row.keySet());
                    String sql = "INSERT INTO " + e.getKey() + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (int i = 0; i < cols.size(); i++) {
                            ps.setObject(i + 1, row.get(cols.get(i)));
                        }
                        ps.executeUpdate();
                    }
                    maxId = Math.max(maxId, ((Number) row.get("id")).longValue());
                }
                try (Statement st = con.createStatement()) {
                    st.execute("ALTER TABLE " + e.getKey() + " ALTER COLUMN id RESTART WITH " + (maxId + 1));
                }
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

    private List<Path> backups(String dbName) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(dbName + "-backup-before-V11-"))
                    .toList();
        }
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void ac1_m1DataMovesIntoDefaultAccountWithEveryNumberUnchanged() throws Exception {
        Source source = buildSource();
        Map<String, JsonNode> before = source.snapshot();
        // the M1 numbers of the requirement
        JsonNode summary = before.get("/api/cash/summary");
        assertThat(List.of(summary.get("totalCash").asText(), summary.get("frozenCash").asText(),
                summary.get("availableCash").asText())).containsExactly("99603.05", "44000.00", "55603.05");
        assertThat(before.get("/api/cash/ledger?size=100").get("totalItems").asInt()).isEqualTo(18);
        assertThat(before.get("/api/option-trades?size=100").get("totalItems").asInt()).isEqualTo(16);
        assertThat(before.get("/api/option-positions").get("positionCount").asInt()).isEqualTo(3);
        assertThat(before.get("/api/closed-trades?size=100").get("filteredRealizedPnlTotal").asText())
                .isEqualTo("763.05");
        JsonNode dashboard = before.get("/api/dashboard");
        assertThat(dashboard.get("realizedPnl").get("total").asText()).isEqualTo("763.05");
        assertThat(dashboard.get("currentMonthRealizedPnl").get("total").asText()).isEqualTo("693.05");
        assertThat(dashboard.get("positions").get("expiringWithin7DaysCount").asInt()).isEqualTo(1);
        assertThat(dashboard.get("positions").get("expiredUnprocessedCount").asInt()).isEqualTo(1);
        JsonNode audits = before.get("/api/audit-log?size=100").get("items");
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).get("changes").get(0).get("text").asText()).isEqualTo("金额：1,000.00 → 1,500.00");
        assertThat(audits.get(1).get("changes").get(0).get("text").asText()).isEqualTo("净额：640.00 → 700.00");

        // an M1-schema file database with the same rows and ids
        String url = fileUrl("optionmgr");
        migrateToM1(url);
        insertRows(url, source.rows());
        assertThat(count(url, "SELECT MAX(version) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(4);
        assertThat(backups("optionmgr")).isEmpty();

        // upgrade: backup + V5 (+ V6, V7, V8, V9, V10, V11)
        try (ConfigurableApplicationContext b = start(url)) {
            ScenarioM m = new ScenarioM(mvc(b), b.getBean(ObjectMapper.class), 1);
            Map<String, JsonNode> after = m.snapshot();
            assertThat(after.keySet()).isEqualTo(before.keySet());
            for (String key : before.keySet()) {
                assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
            }
            JsonNode accounts = m.get("/api/accounts");
            assertThat(accounts).hasSize(1);
            assertThat(accounts.get(0).get("id").asLong()).isEqualTo(1);
            assertThat(accounts.get(0).get("name").asText()).isEqualTo("默认账户");
            assertThat(accounts.get(0).get("brokerNote").isNull()).isTrue();
            JdbcTemplate jdbc = b.getBean(JdbcTemplate.class);
            for (String table : DATA_TABLES) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id <> 1", Long.class))
                        .as(table).isZero();
            }
            // a new account gets id 2 and starts empty
            com.fasterxml.jackson.databind.node.ObjectNode n = json.createObjectNode();
            n.put("name", "小账户");
            assertThat(m.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/accounts"),
                    n, 201).get("id").asLong()).isEqualTo(2);
        }

        // Q60: backup taken before migrating, restorable to the M1 database
        List<Path> backups = backups("optionmgr");
        assertThat(backups).hasSize(1);
        assertThat(backups.get(0).getFileName().toString())
                .isEqualTo("optionmgr-backup-before-V11-20261112-150000Z.zip");
        String restored = restore(backups.get(0), "restored");
        assertThat(count(restored, "SELECT MAX(version) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(4);
        assertThat(count(restored, "SELECT COUNT(*) FROM option_trade")).isEqualTo(16);
        assertThat(count(restored, "SELECT COUNT(*) FROM cash_ledger_entry")).isEqualTo(18);
        assertThat(count(restored, "SELECT COUNT(*) FROM audit_record")).isEqualTo(2);
        assertThat(count(restored, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'account'"))
                .isZero();
    }

    /** Unzips an H2 backup into a new directory and returns a JDBC URL for it. */
    private String restore(Path zip, String into) throws Exception {
        Path target = Files.createDirectories(dir.resolve(into));
        String dbName = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = target.resolve(Path.of(e.getName()).getFileName().toString());
                Files.copy(in, out);
                if (out.getFileName().toString().endsWith(".mv.db")) {
                    dbName = out.getFileName().toString().replace(".mv.db", "");
                }
            }
        }
        assertThat(dbName).isNotNull();
        return "jdbc:h2:file:" + target.resolve(dbName).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;IFEXISTS=TRUE";
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void ac2_failingMigrationDoesNotStartAndTheBackupRestoresTheM1Database() throws Exception {
        String url = fileUrl("faildb");
        migrateToM1(url);
        insertOneDeposit(url);

        // V5 to V11 run, then a simulated failure in a later migration (test-only V12) of the same upgrade
        assertThatThrownBy(() -> start(url,
                "--spring.flyway.locations=classpath:db/migration,classpath:db/failing-migration"))
                .hasStackTraceContaining("simulated_failure");

        List<Path> backups;
        try (Stream<Path> files = Files.list(dir)) {
            backups = files.filter(p -> p.getFileName().toString().startsWith("faildb-backup-before-V12-")).toList();
        }
        assertThat(backups).hasSize(1);
        String restored = restore(backups.get(0), "restored");
        assertThat(count(restored, "SELECT MAX(version) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(4);
        assertThat(count(restored, "SELECT COUNT(*) FROM cash_transfer")).isEqualTo(1);
        assertThat(count(restored, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'cash_transfer' AND column_name = 'account_id'")).isZero();
    }

    @Test
    void ac2_failingBackupMeansNoMigrationAndNoStart() throws Exception {
        String url = fileUrl("nobackup");
        migrateToM1(url);
        insertOneDeposit(url);
        // the (deterministic, fixed clock) backup file name is taken by a directory: BACKUP TO must fail
        Files.createDirectories(dir.resolve("nobackup-backup-before-V11-20261112-150000Z.zip"));

        assertThatThrownBy(() -> start(url)).hasStackTraceContaining("Database backup before migration failed");
        assertThat(count(url, "SELECT MAX(version) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(4);
        assertThat(count(url, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'account'"))
                .isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM cash_transfer")).isEqualTo(1);
    }

    @Test
    void inMemoryDatabasesAreNotBackedUpAndStartWithTheDefaultAccountOnly() throws Exception {
        String url = "jdbc:h2:mem:fresh-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (ConfigurableApplicationContext c = start(url)) {
            ScenarioM m = new ScenarioM(mvc(c), c.getBean(ObjectMapper.class), 1);
            JsonNode accounts = m.get("/api/accounts");
            assertThat(accounts).hasSize(1);
            assertThat(accounts.get(0).get("name").asText()).isEqualTo("默认账户");
            assertThat(m.get("/api/cash/summary").get("totalCash").asText()).isEqualTo("0.00");
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private static void insertOneDeposit(String url) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", ""); Statement st = con.createStatement()) {
            st.execute("INSERT INTO cash_transfer (id, type, amount, occurred_at, remark, created_at) VALUES "
                    + "(1, 'DEPOSIT', 1000.00, TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00', NULL, "
                    + "TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00')");
            st.execute("INSERT INTO cash_ledger_entry (id, occurred_at, source_type, source_id, amount, frozen_change, "
                    + "description, remark, created_at) VALUES (1, TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00', "
                    + "'DEPOSIT', 1, 1000.00, 0, NULL, NULL, TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00')");
        }
    }
}
