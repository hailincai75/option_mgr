package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.OptionMgrApplication;
import com.optionmgr.account.web.AccountRef;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * FEAT-051 acceptance 8 / rule 6 (Q255 A): a database at V16 holding closes recorded before FEAT-051 is upgraded by
 * the application (Q60 backup, V17 table, V18 Java back-fill) and every read endpoint of every account returns
 * exactly what it returned before; {@code option_close_lot} holds each BTC / STC's FIFO lots. A V16 database whose
 * stored frozen change contradicts the FIFO lots makes V18 fail: the application does not start and the backup
 * restores the V16 database.
 *
 * <p>How the V16 data is produced: the scenarios are entered through the API of the current code (every close in
 * FIFO mode, except one close whose lot is re-selected the way Q39 used to re-match it, so that its stored lots equal
 * the pre-FEAT-051 FIFO derivation) in a scratch in-memory database; all rows of every table except
 * {@code option_close_lot} are copied with their ids into a fresh file database migrated only to V16. ZZQ is a
 * fictional symbol.
 */
class MigrationV18CloseLotsTest {

    static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock migrationV18TestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(OptionMgrApplication.class, FixedClock.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("excludeTestConfigurations",
                        new MigrationFromM1Test.ExcludeTestConfigurations()))
                .run("--spring.datasource.url=" + url, "--server.port=0", "--spring.profiles.active=test");
    }

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    // ---------------------------------------------------------------- API client

    private static final class Api {
        private final MockMvc mvc;
        private final ObjectMapper json;

        Api(ConfigurableApplicationContext ctx) {
            this.mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build();
            this.json = ctx.getBean(ObjectMapper.class);
        }

        JsonNode call(MockHttpServletRequestBuilder req, String account, ObjectNode body, int status) throws Exception {
            req.header(AccountRef.HEADER, account);
            if (body != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            }
            MvcResult r = mvc.perform(req).andReturn();
            String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(status);
            return raw.isEmpty() ? null : json.readTree(raw);
        }

        JsonNode get(String url, String account) throws Exception {
            return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(url)),
                    account, null, 200);
        }
    }

    // ---------------------------------------------------------------- scenarios (entered with the current code)

    private ObjectNode contract(String exp, String strike, String type) {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", "ZZQ");
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        return n;
    }

    private long trade(Api api, String account, String action, String exp, String strike, String type, int contracts,
                       Integer multiplier, String net, String nyTime) throws Exception {
        ObjectNode n = contract(exp, strike, type);
        n.put("action", action);
        n.put("contracts", contracts);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        return api.call(post(url), account, n, 201).get("id").asLong();
    }

    private void deposit(Api api, String account, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", "DEPOSIT");
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        api.call(post("/api/cash/transfers"), account, n, 201);
    }

    /** Ids of the closes the test checks afterwards. */
    private final Map<String, Long> ids = new LinkedHashMap<>();

    private void buildScenarios(Api api) throws Exception {
        ObjectNode acct = json.createObjectNode();
        acct.put("name", "乙");
        assertThat(api.call(post("/api/accounts"), "1", acct, 201).get("id").asLong()).isEqualTo(2);

        // account 1 - acceptance 8 (FEAT-004 scenario A): L1 STO 2, L2 STO 1, BTC 2 closes L1
        deposit(api, "1", "80000.00", "2026-10-26T10:00");
        ids.put("L1", trade(api, "1", "STO", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-03T10:30"));
        ids.put("L2", trade(api, "1", "STO", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00"));
        ids.put("A8", trade(api, "1", "BTC", "2026-12-18", "200", "PUT", 2, null, "241.30", "2026-11-05T10:00"));

        // account 1 - long calls: one STC over two lots, then a second round on the same contract
        deposit(api, "1", "20000.00", "2026-10-27T10:00");
        trade(api, "1", "BTO", "2026-12-18", "10", "CALL", 1, 100, "50.00", "2026-11-02T10:00");
        trade(api, "1", "BTO", "2026-12-18", "10", "CALL", 2, 100, "90.00", "2026-11-03T10:00");
        ids.put("MULTI", trade(api, "1", "STC", "2026-12-18", "10", "CALL", 3, null, "200.00", "2026-11-04T10:00"));
        trade(api, "1", "BTO", "2026-12-18", "10", "CALL", 1, 100, "40.00", "2026-11-05T10:00");
        ids.put("ROUND2", trade(api, "1", "STC", "2026-12-18", "10", "CALL", 1, null, "45.00", "2026-11-06T10:00"));

        // account 1 - short puts: BTC, put assignment (FIFO), BTC of the last lot
        trade(api, "1", "STO", "2026-12-18", "7", "PUT", 1, 100, "11.00", "2026-11-02T10:00");
        trade(api, "1", "STO", "2026-12-18", "7", "PUT", 1, 100, "12.00", "2026-11-03T10:00");
        trade(api, "1", "STO", "2026-12-18", "7", "PUT", 1, 100, "13.00", "2026-11-04T10:00");
        trade(api, "1", "BTC", "2026-12-18", "7", "PUT", 1, null, "5.00", "2026-11-05T10:00");
        ObjectNode asg = contract("2026-12-18", "7", "PUT");
        asg.put("contracts", 1);
        asg.put("assignmentDate", "2026-11-06");
        api.call(post("/api/option-trades/assignments"), "1", asg, 201);
        ids.put("AFTER_ASG", trade(api, "1", "BTC", "2026-12-18", "7", "PUT", 1, null, "6.00", "2026-11-09T10:00"));

        // account 1 - long calls: STC, exercise (FIFO), STC of the last lot
        trade(api, "1", "BTO", "2026-12-18", "5", "CALL", 1, 100, "30.00", "2026-11-02T10:00");
        trade(api, "1", "BTO", "2026-12-18", "5", "CALL", 1, 100, "31.00", "2026-11-03T10:00");
        trade(api, "1", "BTO", "2026-12-18", "5", "CALL", 1, 100, "32.00", "2026-11-04T10:00");
        trade(api, "1", "STC", "2026-12-18", "5", "CALL", 1, null, "35.00", "2026-11-05T10:00");
        ObjectNode exr = contract("2026-12-18", "5", "CALL");
        exr.put("contracts", 1);
        exr.put("exerciseDate", "2026-11-06");
        api.call(post("/api/option-trades/exercises"), "1", exr, 201);
        ids.put("AFTER_EXR", trade(api, "1", "STC", "2026-12-18", "5", "CALL", 1, null, "36.00", "2026-11-09T10:00"));

        // account 1 - an expired contract: BTC, then the expiry of the rest
        trade(api, "1", "STO", "2026-11-13", "6", "PUT", 1, 100, "8.00", "2026-11-02T10:00");
        trade(api, "1", "STO", "2026-11-13", "6", "PUT", 1, 100, "9.00", "2026-11-03T10:00");
        ids.put("BEFORE_EXP", trade(api, "1", "BTC", "2026-11-13", "6", "PUT", 1, null, "2.00", "2026-11-05T10:00"));
        ObjectNode exp = contract("2026-11-13", "6", "PUT");
        exp.put("contracts", 1);
        api.call(post("/api/option-trades/expirations"), "1", exp, 201);

        // account 1 - Q28 rounded frozen cash (strike 12.345, multiplier 1): A 2 = 24.69, B / C 1 = 12.35 each
        trade(api, "1", "STO", "2026-12-18", "12.345", "PUT", 2, 1, "1.00", "2026-11-02T10:00");
        trade(api, "1", "STO", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-03T10:00");
        trade(api, "1", "STO", "2026-12-18", "12.345", "PUT", 1, 1, "0.50", "2026-11-04T10:00");
        ids.put("ROUND_A", trade(api, "1", "BTC", "2026-12-18", "12.345", "PUT", 2, null, "0.20", "2026-11-06T10:00"));
        ids.put("ROUND_BC", trade(api, "1", "BTC", "2026-12-18", "12.345", "PUT", 2, null, "0.20", "2026-11-07T10:00"));

        // account 1 - a close Q39 re-matched: BTC 1 (entered first) on A, then an earlier lot B back-filled; before
        // FEAT-051 the BTC then covered B (FIFO by time); re-select B so the stored state is exactly that
        long q39a = trade(api, "1", "STO", "2026-12-18", "11", "PUT", 1, 100, "20.00", "2026-11-04T10:00");
        long q39 = trade(api, "1", "BTC", "2026-12-18", "11", "PUT", 1, null, "4.00", "2026-11-06T10:00");
        long q39b = trade(api, "1", "STO", "2026-12-18", "11", "PUT", 1, 100, "25.00", "2026-11-03T10:00");
        ObjectNode edit = json.createObjectNode();
        edit.put("netAmount", "4.00");
        edit.put("tradedAtLocal", "2026-11-06T10:00");
        edit.putArray("lotTradeIds").add(q39b);
        api.call(put("/api/option-trades/" + q39), "1", edit, 200);
        ids.put("Q39", q39);
        ids.put("Q39_B", q39b);
        ids.put("Q39_A", q39a);

        // account 2 - the same contract as acceptance 8, independent lots (Q53)
        deposit(api, "2", "50000.00", "2026-10-26T10:00");
        trade(api, "2", "STO", "2026-12-18", "200", "PUT", 1, 100, "300.00", "2026-11-03T10:00");
        trade(api, "2", "STO", "2026-12-18", "200", "PUT", 1, 100, "310.00", "2026-11-04T10:00");
        ids.put("ACCT2", trade(api, "2", "BTC", "2026-12-18", "200", "PUT", 1, null, "100.00", "2026-11-05T10:00"));
    }

    /** Every read endpoint of both accounts and of ALL, plus each trade by id. */
    private Map<String, JsonNode> snapshot(Api api) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String account : List.of("1", "2")) {
            for (String url : List.of("/api/cash/summary", "/api/cash/ledger?size=100", "/api/option-trades?size=100",
                    "/api/option-positions", "/api/closed-trades?size=100", "/api/stock-positions",
                    "/api/stock-trades?size=100", "/api/pnl-summary", "/api/dashboard", "/api/audit-log?size=100")) {
                out.put(account + " " + url, api.get(url, account));
            }
            for (JsonNode t : api.get("/api/option-trades?size=100", account).get("items")) {
                String url = "/api/option-trades/" + t.get("id").asLong();
                out.put(account + " " + url, api.get(url, account));
            }
        }
        for (String url : List.of("/api/option-trades?size=100", "/api/option-positions",
                "/api/closed-trades?size=100", "/api/cash/summary")) {
            out.put("ALL " + url, api.get(url, "ALL"));
        }
        return out;
    }

    private record Source(Map<String, List<Map<String, Object>>> rows, Map<String, JsonNode> snapshot,
                          List<Map<String, Object>> closeLots) {
    }

    private Source buildSource() throws Exception {
        String url = "jdbc:h2:mem:v18src-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (ConfigurableApplicationContext a = start(url)) {
            Api api = new Api(a);
            buildScenarios(api);
            Map<String, JsonNode> snapshot = snapshot(api);
            JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
            Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
            for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name", String.class)) {
                if (!table.equals("flyway_schema_history") && !table.equals("option_close_lot")) {
                    rows.put(table, jdbc.queryForList("SELECT * FROM \"" + table + "\""));
                }
            }
            List<Map<String, Object>> closeLots = jdbc.queryForList(
                    "SELECT * FROM option_close_lot ORDER BY close_trade_id, lot_trade_id");
            return new Source(rows, snapshot, closeLots);
        }
    }

    /** Replaces the content of every table of a V16 database by the source rows (same ids). */
    private static void copyRows(String url, Map<String, List<Map<String, Object>>> rows) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            try (Statement st = con.createStatement()) {
                st.execute("SET REFERENTIAL_INTEGRITY FALSE");
            }
            for (var e : rows.entrySet()) {
                try (Statement st = con.createStatement()) {
                    st.execute("DELETE FROM \"" + e.getKey() + "\"");
                }
                for (Map<String, Object> row : e.getValue()) {
                    List<String> cols = new ArrayList<>(row.keySet());
                    String sql = "INSERT INTO \"" + e.getKey() + "\" (" + String.join(", ",
                            cols.stream().map(c -> "\"" + c + "\"").toList()) + ") VALUES ("
                            + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (int i = 0; i < cols.size(); i++) {
                            ps.setObject(i + 1, row.get(cols.get(i)));
                        }
                        ps.executeUpdate();
                    }
                }
            }
            try (Statement st = con.createStatement()) {
                st.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
            List<String[]> identities = new ArrayList<>();
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND is_identity = 'YES'")) {
                while (rs.next()) {
                    identities.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
            for (String[] id : identities) {
                long max;
                try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                        "SELECT COALESCE(MAX(\"" + id[1] + "\"), 0) FROM \"" + id[0] + "\"")) {
                    rs.next();
                    max = rs.getLong(1);
                }
                try (Statement st = con.createStatement()) {
                    st.execute("ALTER TABLE \"" + id[0] + "\" ALTER COLUMN \"" + id[1] + "\" RESTART WITH " + (max + 1));
                }
            }
        }
    }

    private static void migrateTo16(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("16").load()
                .migrate();
    }

    private static long count(String url, String sql) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", "");
             Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<Path> backups(String db) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(db + "-backup-before-V18-")).toList();
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void ac8_closesKeepTheirLotsAndEveryNumberAfterTheUpgrade() throws Exception {
        Source source = buildSource();
        Map<String, JsonNode> before = source.snapshot();
        // acceptance 8 as entered before the upgrade
        JsonNode a8 = before.get("1 /api/option-trades/" + ids.get("A8"));
        assertThat(a8.get("realizedPnl").asText()).isEqualTo("457.40");
        assertThat(a8.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("L1"));
        assertThat(before.get("1 /api/option-trades/" + ids.get("Q39")).get("coveredLots").get(0).get("tradeId")
                .asLong()).isEqualTo(ids.get("Q39_B"));
        assertThat(before.get("1 /api/option-trades/" + ids.get("ROUND_BC")).get("frozenChange").asText())
                .isEqualTo("-24.70");
        assertThat(before.get("1 /api/option-trades/" + ids.get("ROUND_A")).get("frozenChange").asText())
                .isEqualTo("-24.69");

        String url = fileUrl("optionmgr");
        migrateTo16(url);
        copyRows(url, source.rows());
        assertThat(count(url, "SELECT MAX(CAST(version AS INT)) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(16);
        long trades = count(url, "SELECT COUNT(*) FROM option_trade");
        long ledger = count(url, "SELECT COUNT(*) FROM cash_ledger_entry");
        long audits = count(url, "SELECT COUNT(*) FROM audit_record");

        try (ConfigurableApplicationContext b = start(url)) {
            Api api = new Api(b);
            Map<String, JsonNode> after = snapshot(api);
            assertThat(after.keySet()).isEqualTo(before.keySet());
            for (String key : before.keySet()) {
                assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
            }
            JdbcTemplate jdbc = b.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForList("SELECT * FROM option_close_lot ORDER BY close_trade_id, lot_trade_id"))
                    .isEqualTo(source.closeLots());
            // only BTC / STC have rows; every one of them has
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade t WHERE t.action IN ('BTC', 'STC') "
                    + "AND NOT EXISTS (SELECT 1 FROM option_close_lot l WHERE l.close_trade_id = t.id)", Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_close_lot l JOIN option_trade t "
                    + "ON t.id = l.close_trade_id WHERE t.action NOT IN ('BTC', 'STC')", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class)).isEqualTo(trades);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_ledger_entry", Long.class)).isEqualTo(ledger);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isEqualTo(audits);
            assertThat(jdbc.queryForList("SELECT lot_trade_id FROM option_close_lot WHERE close_trade_id = ?",
                    Long.class, ids.get("A8"))).containsExactly(ids.get("L1"));
            assertThat(jdbc.queryForList("SELECT lot_trade_id FROM option_close_lot WHERE close_trade_id = ?",
                    Long.class, ids.get("ACCT2"))).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT account_id FROM option_close_lot WHERE close_trade_id = ?",
                    Long.class, ids.get("ACCT2"))).isEqualTo(2);

            // acceptance 8 after the upgrade
            JsonNode t = api.get("/api/option-trades/" + ids.get("A8"), "1");
            assertThat(t.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("L1"));
            assertThat(t.get("realizedPnl").asText()).isEqualTo("457.40");
            // and new closes use the stored lots
            JsonNode q39 = api.get("/api/option-trades/" + ids.get("Q39"), "1");
            assertThat(q39.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(ids.get("Q39_B"));
        }

        // Q60: backup before V18 holds the V16 database
        assertThat(backups("optionmgr")).hasSize(1);
        assertThat(backups("optionmgr").get(0).getFileName().toString())
                .isEqualTo("optionmgr-backup-before-V18-20261120-150000Z.zip");
    }

    @Test
    void acceptance8_numbers() throws Exception {
        String url = "jdbc:h2:mem:v18a8-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (ConfigurableApplicationContext a = start(url)) {
            Api api = new Api(a);
            deposit(api, "1", "80000.00", "2026-10-26T10:00");
            long l1 = trade(api, "1", "STO", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-03T10:30");
            long l2 = trade(api, "1", "STO", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-04T11:00");
            trade(api, "1", "BTC", "2026-12-18", "200", "PUT", 2, null, "241.30", "2026-11-05T10:00");
            JsonNode s = api.get("/api/cash/summary", "1");
            assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                    s.get("availableCash").asText())).containsExactly("80817.40", "20000.00", "60817.40");
            JsonNode p = api.get("/api/option-positions", "1").get("positions").get(0);
            assertThat(p.get("contracts").asInt()).isEqualTo(1);
            assertThat(p.get("lots").get(0).get("tradeId").asLong()).isEqualTo(l2);
            assertThat(l1).isLessThan(l2);
        }
    }

    @Test
    void v18FailsOnInconsistentFrozenChange_appDoesNotStart_backupRestoresV16() throws Exception {
        Source source = buildSource();
        String url = fileUrl("baddb");
        migrateTo16(url);
        copyRows(url, source.rows());
        // a BTC whose stored frozen change contradicts its FIFO lots (cannot happen with Q39 corrections)
        try (Connection con = DriverManager.getConnection(url, "sa", ""); Statement st = con.createStatement()) {
            st.execute("UPDATE cash_ledger_entry SET frozen_change = -1.00 WHERE source_type = 'OPTION_CLOSE' "
                    + "AND source_id = " + ids.get("A8"));
        }
        assertThatThrownBy(() -> start(url)).hasStackTraceContaining("V18: BTC #" + ids.get("A8"));

        assertThat(backups("baddb")).hasSize(1);
        assertThat(count(url, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE success AND version = '18'"))
                .isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM option_trade")).isEqualTo(source.rows().get("option_trade").size());
        String restored = restore(backups("baddb").get(0));
        assertThat(count(restored, "SELECT MAX(CAST(version AS INT)) FROM \"flyway_schema_history\" WHERE success")).isEqualTo(16);
        assertThat(count(restored, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'option_close_lot'"))
                .isZero();
        assertThat(count(restored, "SELECT COUNT(*) FROM option_trade"))
                .isEqualTo(source.rows().get("option_trade").size());
    }

    private String restore(Path zip) throws Exception {
        Path target = Files.createDirectories(dir.resolve("restored"));
        String dbName = null;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
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
}
