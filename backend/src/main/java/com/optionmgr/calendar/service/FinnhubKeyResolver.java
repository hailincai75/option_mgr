package com.optionmgr.calendar.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FEAT-009 rule 1: finds {@code FINNHUB_API_KEY}. The property {@code optionmgr.finnhub.api-key} (test seam) wins, then
 * the environment variable; otherwise the first existing
 * {@code .env} file of {@code envFiles} is read. A blank value counts as missing. The key is never logged.
 * Pure (no Spring).
 */
public final class FinnhubKeyResolver {

    public static final String VARIABLE = "FINNHUB_API_KEY";

    private FinnhubKeyResolver() {
    }

    public static Optional<String> resolve(String property, Map<String, String> environment, List<Path> envFiles) {
        if (property != null && !property.isBlank()) {
            return Optional.of(property.strip());
        }
        String fromEnv = environment.get(VARIABLE);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.strip());
        }
        for (Path file : envFiles) {
            if (Files.isRegularFile(file)) {
                return fromEnvFile(file);
            }
        }
        return Optional.empty();
    }

    /** {@code KEY=value} lines; {@code #} comments, an optional {@code export } prefix and quotes are handled. */
    static Optional<String> fromEnvFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        String value = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("﻿")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).strip().equals(VARIABLE)) {
                continue;
            }
            value = unquote(line.substring(eq + 1).strip());
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        int comment = v.indexOf(" #");
        return comment >= 0 ? v.substring(0, comment) : v;
    }
}
