package com.optionmgr.valuation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * FEAT-018 IV / Greeks of one option position row. {@code ivPercent} scale 2 (already x100); each {@link Greek} has
 * the per-contract CBOE value (scale 4, not flipped) and the position value (scale 2, x contracts x multiplier x
 * direction). Values are {@code null} unless {@code status} is OK, and also when CBOE gave no value for that field.
 * {@code quoteTime} is the CBOE quote time of the stored values ({@code null} = never quoted).
 */
public record RowGreeks(GreeksStatus status, BigDecimal ivPercent, Greek delta, Greek gamma, Greek theta, Greek vega,
                        Instant quoteTime, String manualMarkNote) {

    public record Greek(BigDecimal perContract, BigDecimal position) {
        static final Greek NONE = new Greek(null, null);
    }

    public String label() {
        return status.label();
    }
}
