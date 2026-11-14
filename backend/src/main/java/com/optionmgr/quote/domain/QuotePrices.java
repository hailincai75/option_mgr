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
            BigDecimal sum = bid.add(ask);
            return new Mark(sum.divide(TWO, Math.max(sum.scale(), 0) + 1, RoundingMode.UNNECESSARY), MarkBasis.MID);
        }
        if (positive(theo)) {
            return new Mark(theo, MarkBasis.THEO);
        }
        return Mark.NONE;
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
