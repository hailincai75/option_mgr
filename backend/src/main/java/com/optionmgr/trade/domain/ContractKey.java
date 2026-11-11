package com.optionmgr.trade.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Option contract identity: symbol + expiration date + strike + call/put. The strike is compared by value
 * (200 == 200.000): it is normalized without trailing zeros, so record equality works.
 */
public record ContractKey(String symbol, LocalDate expirationDate, BigDecimal strike, OptionType optionType) {

    public ContractKey {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(expirationDate, "expirationDate");
        Objects.requireNonNull(strike, "strike");
        Objects.requireNonNull(optionType, "optionType");
        strike = normalizeStrike(strike);
    }

    /** Strike without trailing zeros (as a value); {@link #strikeText} gives its plain display form. */
    public static BigDecimal normalizeStrike(BigDecimal strike) {
        BigDecimal stripped = strike.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** "200", "12.5", "12.345". */
    public static String strikeText(BigDecimal strike) {
        return normalizeStrike(strike).toPlainString();
    }

    /** Display label, e.g. {@code AAPL 2026-12-18 200 P}. */
    public String label() {
        return symbol + " " + expirationDate + " " + strikeText(strike) + " " + optionType.code();
    }
}
