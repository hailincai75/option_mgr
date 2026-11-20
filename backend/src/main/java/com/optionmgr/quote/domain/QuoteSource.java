package com.optionmgr.quote.domain;

import java.util.Locale;

/**
 * FEAT-052 Q261: the option data source of a run ({@code optionmgr.quotes.option-source}) and the source of a stored
 * contract quote. The mark rule (Q139 / Q271) depends on the stored quote's source.
 */
public enum QuoteSource {
    CBOE,
    YAHOO;

    /** {@code yahoo} / {@code cboe}, case-insensitive; anything else is rejected. */
    public static QuoteSource parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("option source is required (yahoo / cboe)");
        }
        String t = text.strip().toUpperCase(Locale.ROOT);
        for (QuoteSource s : values()) {
            if (s.name().equals(t)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown option source '" + text + "' (yahoo / cboe)");
    }
}
