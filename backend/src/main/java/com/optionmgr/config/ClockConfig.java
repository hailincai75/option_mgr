package com.optionmgr.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Single source of "now" so time-dependent logic can be tested with a fixed clock. */
@Configuration
public class ClockConfig {

    @Bean
    @Profile("!e2e")
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** FEAT-009: in the e2e profile "now" can be fixed through PUT /api/test-support/clock. */
    @Bean
    @Profile("e2e")
    public SettableClock settableClock() {
        return new SettableClock();
    }
}
