package com.optionmgr.quote.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** FEAT-016 price rules (pure). */
public final class QuotePrices {

    /** The Q139 automatic mark and its basis; {@code value} null = no mark ("无报价"). */
    public record Mark(BigDecimal value, MarkBasis basis) {
        public static final Mark NONE = new Mark(null, null);
    }

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private QuotePrices() {
    }

    /**
     * Q139: (bid + ask) / 2 when both are &gt; 0 (exact: at most one more decimal than the inputs), otherwise theo when
     * &gt; 0, otherwise none.
     */
    public static Mark mark(BigDecimal bid, BigDecimal ask, BigDecimal theo) {
        if (positive(bid) && positive(ask)) {
            return new Mark(mid(bid, ask), MarkBasis.MID);
        }
        if (positive(theo)) {
            return new Mark(theo, MarkBasis.THEO);
        }
        return Mark.NONE;
    }

    /**
     * FEAT-052: the automatic mark of a stored quote by its source. CBOE: {@link #mark(BigDecimal, BigDecimal,
     * BigDecimal)} (Q139). Yahoo (Q271): a valid quote (ask &gt; 0, bid &ge; 0, ask &ge; bid) -&gt; (bid + ask) / 2;
     * otherwise {@code last} &gt; 0 -&gt; last ({@link MarkBasis#LAST}); otherwise none.
     */
    public static Mark mark(QuoteSource source, BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal theo) {
        if (source != QuoteSource.YAHOO) {
            return mark(bid, ask, theo);
        }
        if (validYahoo(bid, ask)) {
            return new Mark(mid(bid, ask), MarkBasis.MID);
        }
        if (positive(last)) {
            return new Mark(last, MarkBasis.LAST);
        }
        return Mark.NONE;
    }

    /** FEAT-052 Q271: a valid Yahoo quote: ask &gt; 0, bid &ge; 0, ask &ge; bid (both present). */
    public static boolean validYahoo(BigDecimal bid, BigDecimal ask) {
        return bid != null && ask != null && ask.signum() > 0 && bid.signum() >= 0 && ask.compareTo(bid) >= 0;
    }

    /** Exact (bid + ask) / 2: at most one more decimal than the inputs ({@code 0 / 0.05} -&gt; 0.025). */
    static BigDecimal mid(BigDecimal bid, BigDecimal ask) {
        BigDecimal sum = bid.add(ask);
        return sum.divide(TWO, Math.max(sum.scale(), 0) + 1, RoundingMode.UNNECESSARY);
    }

    public static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /** Display form: no trailing zeros but at least 2 decimals ("1.2" -> 1.20, "0.035" -> 0.035); null stays null. */
    public static BigDecimal display(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 2 ? s.setScale(2, RoundingMode.UNNECESSARY) : s;
    }

    /** Value for a DECIMAL(19, scale) column: HALF_UP when it has more decimals. */
    public static BigDecimal forStorage(BigDecimal v, int scale) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.scale() > scale ? s.setScale(scale, RoundingMode.HALF_UP) : s;
    }
}
