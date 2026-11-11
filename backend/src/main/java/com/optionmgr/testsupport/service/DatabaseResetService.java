package com.optionmgr.testsupport.service;

import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.testsupport.E2eProfile;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resets the database to the state of a freshly migrated one. Exists only with the {@code e2e} profile.
 *
 * <p><b>Snapshot.</b> Right after Flyway has migrated the (fresh, in-memory) database at startup, every table of
 * the current schema except Flyway's history table is read: its columns, rows (the seed data inserted by
 * migrations, e.g. the single {@code cash_account} row), foreign-key parents and identity columns. Tables are
 * discovered through JDBC metadata, so tables added by future migrations are covered without code changes.
 *
 * <p><b>Reset</b> (one transaction, holding the {@code cash_account} row lock that every cash-changing
 * transaction takes, so it cannot interleave with a write):
 * <ol>
 *   <li>every table whose content differs from the snapshot (plus, transitively, the tables referencing it) is
 *       emptied children-first and refilled with its snapshot rows parents-first; unchanged tables, such as
 *       {@code cash_account}, are left untouched so the locked row stays in place;</li>
 *   <li>every identity column is restarted at (max id in the snapshot) + 1, i.e. 1 for empty tables, so new
 *       ids - and with them ledger entry order - continue exactly as in a fresh database. This is done last:
 *       in H2 the restart does not commit the transaction but takes effect immediately (not rolled back).</li>
 * </ol>
 */
@Service
@Profile(E2eProfile.NAME)
public class DatabaseResetService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResetService.class);

    private final JdbcTemplate jdbc;
    private final CashLedgerService cashLedger;
    /** Parents before children. */
    private final List<TableSnapshot> snapshot;

    /**
     * Taking {@link FlywayMigrationInitializer} as a dependency guarantees the migration has run before the
     * snapshot is read.
     */
    public DatabaseResetService(JdbcTemplate jdbc, CashLedgerService cashLedger, Flyway flyway,
            FlywayMigrationInitializer migrated) {
        this.jdbc = jdbc;
        this.cashLedger = cashLedger;
        requireInMemoryDatabase();
        String historyTable = flyway.getConfiguration().getTable();
        this.snapshot = jdbc.execute((ConnectionCallback<List<TableSnapshot>>) con -> takeSnapshot(con, historyTable));
        log.warn("Profile '{}' active: POST /api/test-support/reset is enabled (in-memory DB, tables {})",
                E2eProfile.NAME, snapshot.stream().map(TableSnapshot::name).toList());
    }

    /** Table names covered by reset, parents before children. */
    public List<String> tables() {
        return snapshot.stream().map(TableSnapshot::name).toList();
    }

    @Transactional
    public void reset() {
        requireInMemoryDatabase();
        cashLedger.lockAccount();

        Set<String> dirty = new HashSet<>();
        for (TableSnapshot table : snapshot) {
            if (!sameRows(table.rows(), readRows(table)) || table.parents().stream().anyMatch(dirty::contains)) {
                dirty.add(table.name());
            }
        }
        List<TableSnapshot> reversed = new ArrayList<>(snapshot);
        Collections.reverse(reversed);
        for (TableSnapshot table : reversed) {
            if (dirty.contains(table.name())) {
                jdbc.update("DELETE FROM " + table.quotedName());
            }
        }
        for (TableSnapshot table : snapshot) {
            if (dirty.contains(table.name()) && !table.rows().isEmpty()) {
                jdbc.batchUpdate(table.insertSql(), table.rows());
            }
        }
        for (TableSnapshot table : snapshot) {
            table.identityRestart().forEach((column, next) -> jdbc.execute("ALTER TABLE " + table.quotedName()
                    + " ALTER COLUMN " + table.quote(column) + " RESTART WITH " + next));
        }
        log.info("Test-support reset: restored tables {}", new TreeSet<>(dirty));
    }

    private void requireInMemoryDatabase() {
        E2eProfile.requireInMemoryH2(jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getURL()));
    }

    private List<Object[]> readRows(TableSnapshot table) {
        return jdbc.query(table.selectSql(), (rs, rowNum) -> {
            Object[] row = new Object[table.columns().size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = rs.getObject(i + 1);
            }
            return row;
        });
    }

    private static boolean sameRows(List<Object[]> a, List<Object[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.deepEquals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ snapshot

    private List<TableSnapshot> takeSnapshot(Connection con, String historyTable) throws SQLException {
        DatabaseMetaData md = con.getMetaData();
        String catalog = con.getCatalog();
        String schema = con.getSchema();
        String quote = md.getIdentifierQuoteString().strip();

        Map<String, TableSnapshot> byName = new TreeMap<>();
        for (String table : tableNames(md, catalog, schema, historyTable)) {
            List<String> columns = new ArrayList<>();
            Set<String> identity = new LinkedHashSet<>();
            try (ResultSet rs = md.getColumns(catalog, schema, table, null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                    if ("YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"))) {
                        identity.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            List<String> orderBy = primaryKey(md, catalog, schema, table);
            if (orderBy.isEmpty()) {
                orderBy = columns;
            }
            Set<String> parents = new TreeSet<>();
            try (ResultSet rs = md.getImportedKeys(catalog, schema, table)) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    if (!parent.equals(table)) {
                        parents.add(parent);
                    }
                }
            }
            TableSnapshot partial = new TableSnapshot(table, quote, columns, orderBy, List.of(), parents, Map.of());
            List<Object[]> rows = readRows(con, partial);
            Map<String, Long> restart = new LinkedHashMap<>();
            for (String column : identity) {
                int index = columns.indexOf(column);
                long max = rows.stream().mapToLong(r -> ((Number) r[index]).longValue()).max().orElse(0L);
                restart.put(column, max + 1);
            }
            byName.put(table, new TableSnapshot(table, quote, columns, orderBy, rows, parents, restart));
        }
        return parentsFirst(byName);
    }

    private static List<String> tableNames(DatabaseMetaData md, String catalog, String schema, String historyTable)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, schema, "%", null)) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE").toUpperCase(Locale.ROOT);
                String name = rs.getString("TABLE_NAME");
                if ((type.equals("TABLE") || type.equals("BASE TABLE")) && !name.equalsIgnoreCase(historyTable)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<String> primaryKey(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        Map<Integer, String> bySeq = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                bySeq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(bySeq.values());
    }

    private static List<Object[]> readRows(Connection con, TableSnapshot table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (var st = con.createStatement(); ResultSet rs = st.executeQuery(table.selectSql())) {
            while (rs.next()) {
                Object[] row = new Object[table.columns().size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    /** Kahn's algorithm; ties alphabetical. Parents that are not part of the snapshot are ignored. */
    private static List<TableSnapshot> parentsFirst(Map<String, TableSnapshot> byName) {
        Map<String, Set<String>> pending = new HashMap<>();
        byName.values().forEach(t -> pending.put(t.name(),
                t.parents().stream().filter(byName::containsKey).collect(Collectors.toCollection(HashSet::new))));
        List<TableSnapshot> ordered = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>(new TreeSet<>(
                pending.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList()));
        pending.keySet().removeAll(ready);
        while (!ready.isEmpty()) {
            String done = ready.poll();
            ordered.add(byName.get(done));
            TreeSet<String> next = new TreeSet<>();
            pending.forEach((name, parents) -> {
                if (parents.remove(done) && parents.isEmpty()) {
                    next.add(name);
                }
            });
            pending.keySet().removeAll(next);
            ready.addAll(next);
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("Foreign-key cycle between tables " + pending.keySet()
                    + "; DatabaseResetService cannot order them");
        }
        return List.copyOf(ordered);
    }

    record TableSnapshot(String name, String quoteString, List<String> columns, List<String> orderBy,
            List<Object[]> rows, Set<String> parents, Map<String, Long> identityRestart) {

        String quote(String identifier) {
            return quoteString + identifier.replace(quoteString, quoteString + quoteString) + quoteString;
        }

        String quotedName() {
            return quote(name);
        }

        String selectSql() {
            return "SELECT " + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + " FROM "
                    + quotedName() + " ORDER BY " + orderBy.stream().map(this::quote).collect(Collectors.joining(", "));
        }

        String insertSql() {
            return "INSERT INTO " + quotedName() + " ("
                    + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + ") VALUES ("
                    + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        }
    }
}
