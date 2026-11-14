package com.optionmgr.valuation.domain;

import java.math.BigDecimal;

/**
 * FEAT-017 valuation of one position row. Amounts have scale 2; {@code percent} is the percentage number (already
 * x100) with scale 2. All three are {@code null} unless {@code status} is {@link ValuationStatus#OK}; {@code percent}
 * is also {@code null} when the denominator is 0.00 (Q155).
 */
public record RowValuation(ValuationStatus status, BigDecimal marketValue, BigDecimal unrealizedPnl,
                           BigDecimal percent) {

    static RowValuation unavailable(ValuationStatus status) {
        return new RowValuation(status, null, null, null);
    }

    public String label() {
        return status.label();
    }
}
