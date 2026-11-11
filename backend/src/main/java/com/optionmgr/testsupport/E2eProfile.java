package com.optionmgr.testsupport;

import java.util.Locale;

/**
 * The {@code e2e} Spring profile: enables the test-support endpoints (database reset) used by the Playwright
 * suite. Never active in normal runs. While it is active the datasource must be an in-memory H2 database.
 */
public final class E2eProfile {

    public static final String NAME = "e2e";

    private static final String IN_MEMORY_H2_PREFIX = "jdbc:h2:mem:";

    private E2eProfile() {
    }

    /** True only for a named or unnamed in-memory H2 URL ({@code jdbc:h2:mem:...}); file/tcp/other DBs are false. */
    public static boolean isInMemoryH2(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.strip().toLowerCase(Locale.ROOT).startsWith(IN_MEMORY_H2_PREFIX);
    }

    /** Throws if {@code jdbcUrl} is not an in-memory H2 URL. */
    public static void requireInMemoryH2(String jdbcUrl) {
        if (!isInMemoryH2(jdbcUrl)) {
            throw new IllegalStateException("Profile '" + NAME + "' requires an in-memory H2 datasource ("
                    + IN_MEMORY_H2_PREFIX + "...), refusing to run against: " + jdbcUrl);
        }
    }
}
