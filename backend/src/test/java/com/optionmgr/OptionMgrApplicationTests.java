package com.optionmgr;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Context starts, Flyway migrates the schema, and Hibernate's ddl-auto=validate accepts it. */
@SpringBootTest
class OptionMgrApplicationTests {

    @Autowired
    private Flyway flyway;

    @Test
    void contextLoadsAndFlywayAppliesBaselineMigration() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState().isFailed()).isFalse();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
