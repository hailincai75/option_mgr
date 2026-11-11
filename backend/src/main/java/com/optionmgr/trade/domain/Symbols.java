package com.optionmgr.trade.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Underlying symbol rules (Q25): trimmed, upper-cased, letters A-Z / digits / '.', length 1-10. */
public final class Symbols {

    public static final int MAX_LENGTH = 10;
    /** Valid normalized symbol (length is checked separately so each problem gets its own message). */
    public static final String NORMALIZED_PATTERN = "[A-Z0-9.]*";

    private static final Pattern ASCII_SYMBOL_CHARS = Pattern.compile("[A-Za-z0-9.]*");

    private Symbols() {
    }

    /**
     * Strips surrounding whitespace and upper-cases. Only ASCII letters are upper-cased: text with any other
     * character is returned stripped but otherwise unchanged, so it still fails validation (e.g. "ß" must not
     * turn into the valid "SS").
     *
     * @return null for null input
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        return ASCII_SYMBOL_CHARS.matcher(stripped).matches() ? stripped.toUpperCase(Locale.ROOT) : stripped;
    }
}
