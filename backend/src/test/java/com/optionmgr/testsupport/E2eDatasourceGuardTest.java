package com.optionmgr.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.optionmgr.OptionMgrApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

/** The e2e profile may only ever run against an in-memory H2 database. */
class E2eDatasourceGuardTest {

    private final E2eDatasourceGuard guard = new E2eDatasourceGuard();

    @Test
    void recognisesInMemoryH2Urls() {
        assertThat(E2eProfile.isInMemoryH2("jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")).isTrue();
        assertThat(E2eProfile.isInMemoryH2("JDBC:H2:MEM:")).isTrue();
        assertThat(E2eProfile.isInMemoryH2("jdbc:h2:file:./data/optionmgr;AUTO_SERVER=TRUE")).isFalse();
        assertThat(E2eProfile.isInMemoryH2("jdbc:h2:./data/optionmgr")).isFalse();
        assertThat(E2eProfile.isInMemoryH2("jdbc:h2:tcp://localhost/mem:e2e")).isFalse();
        assertThat(E2eProfile.isInMemoryH2("jdbc:postgresql://localhost/optionmgr")).isFalse();
        assertThat(E2eProfile.isInMemoryH2(null)).isFalse();
    }

    @Test
    void e2eProfileWithFileDatabaseIsRejected() {
        MockEnvironment env = env("jdbc:h2:file:./data/optionmgr", E2eProfile.NAME);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-memory H2")
                .hasMessageContaining("jdbc:h2:file:./data/optionmgr");
    }

    @Test
    void e2eProfileWithoutDatasourceUrlIsRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(E2eProfile.NAME);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void e2eProfileWithInMemoryDatabaseIsAccepted() {
        MockEnvironment env = env("jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1", "test", E2eProfile.NAME);
        assertThatCode(() -> guard.postProcessEnvironment(env, new SpringApplication())).doesNotThrowAnyException();
    }

    @Test
    void withoutE2eProfileAnyDatabaseIsLeftAlone() {
        MockEnvironment env = env("jdbc:h2:file:./data/optionmgr");
        assertThatCode(() -> guard.postProcessEnvironment(env, new SpringApplication())).doesNotThrowAnyException();
    }

    /** Full startup: the guard aborts before a DataSource is created, so the file DB is never opened. */
    @Test
    void applicationRefusesToStartWithE2eProfileOnAFileDatabase(@TempDir Path dir) {
        Path db = dir.resolve("must-not-exist");
        SpringApplicationBuilder app = new SpringApplicationBuilder(OptionMgrApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(E2eProfile.NAME);

        assertThatThrownBy(() -> app.run("--spring.datasource.url=jdbc:h2:file:" + db.toAbsolutePath()))
                .hasStackTraceContaining("requires an in-memory H2 datasource");
        assertThat(Files.exists(dir.resolve("must-not-exist.mv.db"))).isFalse();
    }

    private static MockEnvironment env(String url, String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", url);
        env.setActiveProfiles(profiles);
        return env;
    }
}
