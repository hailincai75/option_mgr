package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.account.web.AccountRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * V6 backfill of {@code has_audit_log}: an existing (V5) file database is upgraded by the application (backup, then
 * V6). A trade / cash transfer gets TRUE iff it already has an audit record with its own object type, object id and
 * account; the lists then show {@code hasAuditLog} from that stored flag (e.g. an edited deposit keeps its 历史 link).
 */
class MigrationV6HasAuditLogTest {

    @TempDir
    Path dir;

    private static final String TS = "TIMESTAMP WITH TIME ZONE '2026-11-02 15:00:00+00'";

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    @Test
    void backfillSetsTheFlagForRecordsWithTheirOwnAuditRecords() throws Exception {
        String url = fileUrl("v5db");
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("5").load().migrate();
        try (Connection con = DriverManager.getConnection(url, "sa", ""); Statement st = con.createStatement()) {
            st.execute("INSERT INTO account (id, name, name_key, broker_note, created_at) VALUES (2, '另一个', '另一个', NULL, "
                    + TS + ")");
            st.execute("INSERT INTO cash_account (id, currency, created_at) VALUES (2, 'USD', " + TS + ")");
            // account 1: deposits 1 (edited) and 2 (not edited); account 2: deposit 3 (not edited)
            for (long[] d : new long[][] {{1, 1}, {2, 1}, {3, 2}}) {
                st.execute("INSERT INTO cash_transfer (id, account_id, type, amount, occurred_at, remark, created_at)"
                        + " VALUES (" + d[0] + ", " + d[1] + ", 'DEPOSIT', 10000.00, " + TS + ", NULL, " + TS + ")");
                st.execute("INSERT INTO cash_ledger_entry (id, account_id, occurred_at, source_type, source_id, amount,"
                        + " frozen_change, description, remark, created_at) VALUES (" + d[0] + ", " + d[1] + ", " + TS
                        + ", 'DEPOSIT', " + d[0] + ", 10000.00, 0, NULL, NULL, " + TS + ")");
            }
            // account 1: trades 11 (edited), 12 (not edited), 13 (audit only in account 2 under the same id)
            for (long t : new long[] {11, 12, 13}) {
                st.execute("INSERT INTO option_trade (id, account_id, symbol, expiration_date, strike, option_type,"
                        + " action, contracts, multiplier, net_amount, traded_at, note, created_at) VALUES (" + t
                        + ", 1, 'IXQZ', DATE '2026-12-18', " + t + ", 'PUT', 'STO', 1, 100, 50.00, "
                        + "TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', NULL, " + TS + ")");
                st.execute("INSERT INTO cash_ledger_entry (id, account_id, occurred_at, source_type, source_id, amount,"
                        + " frozen_change, description, remark, created_at) VALUES (" + (100 + t) + ", 1, "
                        + "TIMESTAMP WITH TIME ZONE '2026-11-03 15:00:00+00', 'OPTION_OPEN', " + t + ", 50.00, "
                        + (t * 100) + ".00, 'STO', NULL, " + TS + ")");
            }
            audit(st, 1, "CASH_TRANSFER", 1, "UPDATE");
            audit(st, 1, "CASH_TRANSFER", 1, "UPDATE");    // two records: still just TRUE
            audit(st, 1, "OPTION_TRADE", 11, "UPDATE");
            audit(st, 2, "OPTION_TRADE", 13, "UPDATE");    // other account: does not count
            audit(st, 1, "OPTION_TRADE", 2, "UPDATE");     // wrong object type for transfer 2: does not count
            audit(st, 1, "CASH_TRANSFER", 12, "UPDATE");   // wrong object type for trade 12: does not count
            audit(st, 2, "CASH_TRANSFER", 1, "UPDATE");    // other account, same id as transfer 1 (already TRUE)
            audit(st, 1, "OPTION_TRADE", 99, "DELETE");    // a deleted trade: no row to flag
            audit(st, 1, "ACCOUNT", 1, "UPDATE");          // account audits: no flag anywhere
            st.execute("ALTER TABLE cash_transfer ALTER COLUMN id RESTART WITH 4");
            st.execute("ALTER TABLE option_trade ALTER COLUMN id RESTART WITH 14");
            st.execute("ALTER TABLE cash_ledger_entry ALTER COLUMN id RESTART WITH 200");
        }

        try (ConfigurableApplicationContext app = MigrationFromM1Test.start(url)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForList("SELECT id FROM cash_transfer WHERE has_audit_log ORDER BY id", Long.class))
                    .containsExactly(1L);
            assertThat(jdbc.queryForList("SELECT id FROM option_trade WHERE has_audit_log ORDER BY id", Long.class))
                    .containsExactly(11L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transfer", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM option_trade", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Long.class)).isEqualTo(9);

            MockMvc mvc = MigrationFromM1Test.mvc(app);
            ObjectMapper json = app.getBean(ObjectMapper.class);
            JsonNode ledger = getJson(mvc, json, "/api/cash/ledger?size=100", 1);
            assertThat(flag(ledger, "DEPOSIT", 1)).isTrue();   // the edited deposit keeps its 历史 link
            assertThat(flag(ledger, "DEPOSIT", 2)).isFalse();
            assertThat(flag(ledger, "OPTION_OPEN", 11)).isFalse(); // option rows: always false
            assertThat(flag(getJson(mvc, json, "/api/cash/ledger?size=100", 2), "DEPOSIT", 3)).isFalse();

            JsonNode trades = getJson(mvc, json, "/api/option-trades?size=100", 1).get("items");
            assertThat(trades).hasSize(3);
            for (JsonNode t : trades) {
                assertThat(t.get("hasAuditLog").asBoolean()).as("trade " + t.get("id"))
                        .isEqualTo(t.get("id").asLong() == 11);
            }
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<String> backups = files.map(p -> p.getFileName().toString())
                    .filter(f -> f.startsWith("v5db-backup-before-V13-")).toList();
            assertThat(backups).hasSize(1);
        }
    }

    private static void audit(Statement st, long account, String type, long objectId, String operation)
            throws Exception {
        st.execute("INSERT INTO audit_record (account_id, operated_at, operation, object_type, object_id, summary,"
                + " before_json, after_json, changes_json, side_effects_json) VALUES (" + account + ", " + TS + ", '"
                + operation + "', '" + type + "', " + objectId + ", 's', '{}', NULL, '[]', '[]')");
    }

    private static JsonNode getJson(MockMvc mvc, ObjectMapper json, String url, long account) throws Exception {
        MvcResult r = mvc.perform(get(url).header(AccountRef.HEADER, String.valueOf(account))).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body);
    }

    private static boolean flag(JsonNode ledger, String sourceType, long sourceId) {
        for (JsonNode n : ledger.get("items")) {
            if (n.get("sourceType").asText().equals(sourceType) && n.get("sourceId").asLong() == sourceId) {
                return n.get("hasAuditLog").asBoolean();
            }
        }
        throw new AssertionError("no ledger row " + sourceType + " " + sourceId);
    }
}
