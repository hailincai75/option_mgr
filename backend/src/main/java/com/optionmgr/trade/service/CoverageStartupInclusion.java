package com.optionmgr.trade.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * FEAT-039 (Q101): at startup (after the migrations, incl. V11 marking existing STO calls LEGACY), include every
 * open LEGACY lot that is covered. Idempotent; no audit.
 */
@Component
public class CoverageStartupInclusion implements ApplicationRunner {

    private final CoverageService coverage;

    public CoverageStartupInclusion(CoverageService coverage) {
        this.coverage = coverage;
    }

    @Override
    public void run(ApplicationArguments args) {
        coverage.includeLegacyEverywhere();
    }
}
