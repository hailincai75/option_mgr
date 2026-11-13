package com.optionmgr.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Q60 (FEAT-033): before running pending Flyway migrations on an existing local H2 <b>file</b> database, take a
 * consistent online backup with H2's {@code BACKUP TO} into the database's own directory ({@code data/}):
 * {@code <db>-backup-before-V<version>-<yyyyMMdd-HHmmss>Z.zip}. If the backup fails, nothing is migrated and the
 * application does not start. In-memory databases (tests, e2e) and brand-new databases (nothing applied yet) are
 * not backed up.
 *
 * <p>Restore: stop the application, move the current {@code <db>.mv.db} aside, unzip the backup into the same
 * directory (it contains {@code <db>.mv.db}), start the previous application version.
 */
@Configuration
public class DatabaseBackupBeforeMigration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupBeforeMigration.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Bean
    public FlywayMigrationStrategy backupThenMigrate(Clock clock) {
        return flyway -> {
            backupIfNeeded(flyway, clock);
            flyway.migrate();
        };
    }

    /** @return the backup file, or null when no backup was needed */
    static Path backupIfNeeded(Flyway flyway, Clock clock) {
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length == 0 || flyway.info().applied().length == 0) {
            return null;
        }
        String target = pending[pending.length - 1].getVersion().getVersion();
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        try (Connection con = dataSource.getConnection()) {
            String url = con.getMetaData().getURL();
            if (url == null || !url.startsWith("jdbc:h2:") || url.startsWith("jdbc:h2:mem:")) {
                return null;
            }
            String dbPath = databasePath(con);
            if (dbPath == null) {
                return null;
            }
            Path db = Path.of(dbPath).toAbsolutePath();
            Path backup = db.resolveSibling(db.getFileName() + "-backup-before-V" + target + "-"
                    + STAMP.format(clock.instant()) + "Z.zip");
            log.warn("Pending database migrations up to V{}: backing up {} to {}", target, db, backup);
            try (Statement st = con.createStatement()) {
                st.execute("BACKUP TO '" + backup.toString().replace("'", "''") + "'");
            }
            if (!Files.isRegularFile(backup) || Files.size(backup) == 0) {
                throw new IllegalStateException("backup file was not written: " + backup);
            }
            log.warn("Database backup written ({} bytes): {}", Files.size(backup), backup);
            return backup;
        } catch (Exception e) {
            throw new IllegalStateException("Database backup before migration failed; migrations were NOT run and "
                    + "the application will not start (Q60). Cause: " + e.getMessage(), e);
        }
    }

    /** H2's path of a file database (without extension), null for an in-memory one. */
    private static String databasePath(Connection con) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT DATABASE_PATH()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
