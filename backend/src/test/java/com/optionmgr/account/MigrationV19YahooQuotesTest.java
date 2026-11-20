package com.optionmgr.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * FEAT-052 V19 on a V18 file database holding FEAT-016 quotes: the Q60 backup is taken first, no stored quote value
 * changes, quoted contracts (and all close snapshots) get source CBOE (rule 9), the never-quoted manual-only row stays
 * without source, and the rebuilt constraints accept YAHOO.
 */
class MigrationV19YahooQuotesTest {

    @TempDir
    Path dir;

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    static final String UNDERLYINGS = "SELECT symbol, price, bid, ask, prev_close, price_source, quote_time, "
            + "last_price_success_at, last_chain_success_at, last_attempt_at, last_price_failed, last_chain_failed, "
            + "last_cboe_result, last_finnhub_result, last_failure_detail, manual_price, manual_entered_at "
            + "FROM underlying_quote ORDER BY symbol";
    static final String OPTIONS = "SELECT symbol, expiration_date, strike, option_type, occ_code, bid, ask, last_price, "
            + "theo, iv, delta, gamma, theta, vega, rho, volume, open_interest, quote_time, fetched_at, missing_since, "
            + "manual_mark, manual_entered_at FROM option_quote ORDER BY strike";
    static final String CLOSES = "SELECT snapshot_date, symbol, expiration_date, strike, option_type, bid, ask, theo, "
            + "delta, quote_time, captured_at FROM option_quote_close ORDER BY strike";

    @Test
    void v19_backupFirst_valuesUnchanged_sourceFilled_constraintsRebuilt() throws Exception {
        String url = fileUrl("optionmgr");
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("18").load()
                .migrate();
        JdbcTemplate before = jdbc(url);
        before.update("INSERT INTO underlying_quote (symbol, price, bid, ask, prev_close, price_source, quote_time, "
                + "last_price_success_at, last_chain_success_at, last_attempt_at, last_price_failed, last_chain_failed, "
                + "last_cboe_result, last_finnhub_result, last_failure_detail) VALUES ('QRTV', 52.40, 52.38, 52.42, "
                + "51.90, 'CBOE', TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', FALSE, FALSE, 'SUCCESS', NULL, NULL)");
        before.update("INSERT INTO underlying_quote (symbol, price, price_source, quote_time, last_price_failed, "
                + "last_chain_failed, last_cboe_result, last_finnhub_result, last_failure_detail, manual_price, "
                + "manual_entered_at) VALUES ('IXQZ', 61.10, 'FINNHUB', TIMESTAMP WITH TIME ZONE '2026-11-19 20:59:58+00', "
                + "FALSE, TRUE, 'HTTP_ERROR', 'SUCCESS', 'CBOE HTTP 403', 61.00, "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 21:00:00+00')");
        before.update("INSERT INTO option_quote (symbol, expiration_date, strike, option_type, occ_code, bid, ask, "
                + "last_price, theo, iv, delta, gamma, theta, vega, rho, volume, open_interest, quote_time, fetched_at) "
                + "VALUES ('QRTV', DATE '2027-02-19', 50, 'PUT', 'QRTV270219P00050000', 3.40, 3.60, 3.45, 3.50, "
                + "0.31234567, -0.45, 0.05, -0.02, 0.10, -0.01, 120, 3400, "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00')");
        before.update("INSERT INTO option_quote (symbol, expiration_date, strike, option_type, manual_mark, "
                + "manual_entered_at) VALUES ('SPX', DATE '2026-12-18', 6000, 'CALL', 12.5, "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 21:00:00+00')");
        before.update("INSERT INTO option_quote_close (snapshot_date, symbol, expiration_date, strike, option_type, "
                + "bid, ask, theo, delta, quote_time, captured_at) VALUES (DATE '2026-11-19', 'QRTV', DATE '2027-02-19', "
                + "50, 'PUT', 3.40, 3.60, 3.50, -0.45, TIMESTAMP WITH TIME ZONE '2026-11-19 20:45:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-11-19 21:20:00+00')");
        List<Map<String, Object>> u0 = before.queryForList(UNDERLYINGS);
        List<Map<String, Object>> o0 = before.queryForList(OPTIONS);
        List<Map<String, Object>> c0 = before.queryForList(CLOSES);

        try (ConfigurableApplicationContext c = MigrationV18CloseLotsTest.start(url)) {
            JdbcTemplate after = c.getBean(JdbcTemplate.class);
            assertThat(after.queryForList(UNDERLYINGS)).isEqualTo(u0);
            assertThat(after.queryForList(OPTIONS)).isEqualTo(o0);
            assertThat(after.queryForList(CLOSES)).isEqualTo(c0);
            assertThat(after.queryForList("SELECT quote_source FROM option_quote ORDER BY strike", String.class))
                    .containsExactly("CBOE", null);
            assertThat(after.queryForList("SELECT quote_source FROM option_quote_close", String.class))
                    .containsExactly("CBOE");
            assertThat(after.queryForList("SELECT last_yahoo_result FROM underlying_quote", String.class))
                    .containsOnlyNulls();

            after.update("UPDATE underlying_quote SET price_source = 'YAHOO', last_yahoo_result = 'SUCCESS' "
                    + "WHERE symbol = 'QRTV'");
            assertThatThrownBy(() -> after.update("UPDATE underlying_quote SET price_source = 'OTHER'"))
                    .hasMessageContaining("ck_underlying_quote_source");
            after.update("UPDATE option_quote SET quote_source = 'YAHOO' WHERE strike = 50");
            assertThatThrownBy(() -> after.update("UPDATE option_quote SET quote_source = NULL WHERE strike = 50"))
                    .hasMessageContaining("ck_option_quote_source");
            assertThatThrownBy(() -> after.update("UPDATE option_quote SET quote_source = 'CBOE' WHERE strike = 6000"))
                    .hasMessageContaining("ck_option_quote_source");
        }

        // Q60: the backup before V19 holds the V18 database
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.contains("-backup-before-V")))
                    .containsExactly("optionmgr-backup-before-V19-20261120-150000Z.zip");
        }
    }
}
