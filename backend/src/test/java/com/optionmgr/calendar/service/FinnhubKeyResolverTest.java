package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FEAT-009 rule 1: where the key comes from (only fake values here). */
class FinnhubKeyResolverTest {

    @TempDir
    Path dir;

    private Path envFile(String content) throws Exception {
        Path f = dir.resolve(".env");
        Files.writeString(f, content);
        return f;
    }

    @Test
    void readsTheEnvFile() throws Exception {
        Path f = envFile("# comment\nOTHER=1\nFINNHUB_API_KEY=test-key-123\n");
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(f))).contains("test-key-123");
    }

    @Test
    void environmentVariableWins() throws Exception {
        Path f = envFile("FINNHUB_API_KEY=from-file\n");
        assertThat(FinnhubKeyResolver.resolve(null, Map.of("FINNHUB_API_KEY", "from-env"), List.of(f))).contains("from-env");
        // a blank environment variable does not count
        assertThat(FinnhubKeyResolver.resolve(null, Map.of("FINNHUB_API_KEY", " "), List.of(f))).contains("from-file");
    }

    @Test
    void propertyWinsOverEnvironmentAndFile() throws Exception {
        Path f = envFile("FINNHUB_API_KEY=from-file\n");
        assertThat(FinnhubKeyResolver.resolve("from-prop", Map.of("FINNHUB_API_KEY", "from-env"), List.of(f)))
                .contains("from-prop");
        assertThat(FinnhubKeyResolver.resolve("  ", Map.of("FINNHUB_API_KEY", "from-env"), List.of(f)))
                .contains("from-env");
    }

    @Test
    void firstExistingFileIsUsed() throws Exception {
        Path f = envFile("FINNHUB_API_KEY=root-key\n");
        Path missing = dir.resolve("backend").resolve(".env");
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(missing, f))).contains("root-key");
    }

    @Test
    void quotesExportAndBomAreHandled() throws Exception {
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(envFile("export FINNHUB_API_KEY=\"q-key\"\n"))))
                .contains("q-key");
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(envFile("﻿FINNHUB_API_KEY='s-key'\r\n"))))
                .contains("s-key");
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(envFile("FINNHUB_API_KEY = k1 # note\n"))))
                .contains("k1");
    }

    @Test
    void missingOrBlankMeansNoKey() throws Exception {
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(dir.resolve("nope.env")))).isEmpty();
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(envFile("FINNHUB_API_KEY=\n")))).isEmpty();
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of(envFile("OTHER_KEY=x\n")))).isEmpty();
        assertThat(FinnhubKeyResolver.resolve(null, Map.of(), List.of())).isEmpty();
    }
}
