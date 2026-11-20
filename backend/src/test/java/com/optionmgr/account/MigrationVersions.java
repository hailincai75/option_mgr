package com.optionmgr.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Highest Flyway version found on the classpath, so tests that check the pre-migration backup name
 * ({@code <db>-backup-before-V<target>-...}, target = latest version) do not hard-code it (BUG-005).
 */
final class MigrationVersions {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.(sql|class)$");

    private MigrationVersions() {
    }

    /** Latest real migration ({@code classpath:db/migration}): SQL or Java (FEAT-051 V18). */
    static int latest() {
        return latestIn("db/migration");
    }

    /** The test-only failing migration ({@code classpath:db/failing-migration}). */
    static int failing() {
        return latestIn("db/failing-migration");
    }

    static int latestIn(String location) {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:" + location + "/V*");
            return Arrays.stream(files).map(Resource::getFilename).filter(n -> n != null)
                    .map(VERSION::matcher).filter(Matcher::matches).mapToInt(m -> Integer.parseInt(m.group(1)))
                    .max().orElseThrow(() -> new IllegalStateException("no migrations in " + location));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code <db>-backup-before-V<latest>-}. */
    static String backupPrefix(String db) {
        return db + "-backup-before-V" + latest() + "-";
    }
}
