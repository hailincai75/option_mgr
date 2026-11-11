package com.optionmgr.testsupport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fail-fast safeguard (registered in {@code META-INF/spring.factories}): if the {@code e2e} profile is active,
 * the effective {@code spring.datasource.url} must be an in-memory H2 URL, otherwise startup aborts before any
 * bean (DataSource, Flyway) is created, so the dev file DB under {@code backend/data/} is never opened.
 * Runs after config data (all application*.yml, env vars, command-line args) has been applied.
 * Does nothing when the profile is not active.
 */
public class E2eDatasourceGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of(E2eProfile.NAME))) {
            E2eProfile.requireInMemoryH2(environment.getProperty("spring.datasource.url"));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
