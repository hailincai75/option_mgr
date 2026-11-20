package com.optionmgr.quote.domain;

/**
 * FEAT-052 rule 4 (Q265, Q271): how one Yahoo value of a contract compares with the stored quote. Pure.
 */
public final class YahooQuoteRules {

    /** The class of one Yahoo contract value. */
    public enum Kind {
        /** ask &gt; 0, bid &ge; 0, ask &ge; bid. */
        VALID,
        /** Not valid, lastPrice &gt; 0. */
        LAST_ONLY,
        /** Neither (e.g. bid / ask 0 or missing after the close, last 0 or missing). */
        NONE
    }

    private YahooQuoteRules() {
    }

    public static Kind classify(YahooOption o) {
        if (QuotePrices.validYahoo(o.bid(), o.ask())) {
            return Kind.VALID;
        }
        return QuotePrices.positive(o.last()) ? Kind.LAST_ONLY : Kind.NONE;
    }

    /**
     * The stored quote is usable: quoted and its automatic mark (by its own source) is based on MID or THEO - a valid
     * Yahoo quote, or a CBOE quote that gives a mark. A LAST-only Yahoo quote is not.
     */
    public static boolean usable(OptionState stored) {
        if (stored == null || !stored.quoted()) {
            return false;
        }
        MarkBasis b = stored.automaticMark().basis();
        return b == MarkBasis.MID || b == MarkBasis.THEO;
    }

    /** VALID always replaces; LAST_ONLY only when nothing usable is stored; NONE never. */
    public static boolean replaces(Kind kind, OptionState stored) {
        return kind == Kind.VALID || kind == Kind.LAST_ONLY && !usable(stored);
    }
}
