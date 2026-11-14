package com.optionmgr.valuation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * FEAT-017 market value and unrealized P&L of one position (pure Java). Rules: Q152 (signed option market value),
 * Q153 (no closing fee estimate), Q154 (market value rounded HALF_UP to cents, P&L from the rounded value), Q155
 * (percentage HALF_UP to 2 decimals, {@code null} for a 0.00 denominator), Q156 (expired, unhandled options are not
 * valued; the expiration day itself still is).
 */
public final class UnrealizedValuation {

    static final int SCALE = 2;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private UnrealizedValuation() {
    }

    /**
     * @param longPosition    {@code true} for a long (BTO) position, {@code false} for short (STO)
     * @param openingNetTotal remaining opening net amount with cash sign (short +, long −), scale 2
     * @param mark            displayed mark (FEAT-016, incl. manual override); {@code null} = no quote
     */
    public static RowValuation option(boolean longPosition, int contracts, int multiplier, BigDecimal openingNetTotal,
                                      LocalDate expirationDate, LocalDate today, BigDecimal mark) {
        if (expirationDate.isBefore(today)) {
            return RowValuation.unavailable(ValuationStatus.EXPIRED_PENDING);
        }
        if (mark == null) {
            return RowValuation.unavailable(ValuationStatus.NO_QUOTE);
        }
        BigDecimal size = mark.multiply(BigDecimal.valueOf((long) contracts * multiplier)).setScale(SCALE, ROUNDING);
        BigDecimal marketValue = longPosition ? size : size.negate();
        BigDecimal pnl = openingNetTotal.setScale(SCALE, RoundingMode.UNNECESSARY).add(marketValue);
        return new RowValuation(ValuationStatus.OK, marketValue, pnl, percent(pnl, openingNetTotal.abs()));
    }

    /**
     * @param totalCost remaining cost of the shares (FEAT-011), scale 2
     * @param price     displayed price (FEAT-016, incl. manual override); {@code null} = no quote
     */
    public static RowValuation stock(long shares, BigDecimal totalCost, BigDecimal price) {
        if (price == null) {
            return RowValuation.unavailable(ValuationStatus.NO_QUOTE);
        }
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(shares)).setScale(SCALE, ROUNDING);
        BigDecimal pnl = marketValue.subtract(totalCost.setScale(SCALE, RoundingMode.UNNECESSARY));
        return new RowValuation(ValuationStatus.OK, marketValue, pnl, percent(pnl, totalCost));
    }

    static BigDecimal percent(BigDecimal pnl, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return null;
        }
        return pnl.multiply(HUNDRED).divide(denominator, SCALE, ROUNDING);
    }
}
