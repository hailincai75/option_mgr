package com.optionmgr.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * FEAT-016: a parsed CBOE delayed-quote response. {@code timestamp} is the CBOE snapshot time (top-level
 * {@code timestamp}, New York local), used as the quote time of the underlying and of every option.
 */
public record CboeChain(Instant timestamp, BigDecimal currentPrice, BigDecimal bid, BigDecimal ask,
                        BigDecimal prevClose, List<CboeOption> options) {

    public CboeChain {
        options = List.copyOf(options);
    }

    public boolean hasPrice() {
        return QuotePrices.positive(currentPrice);
    }
}
