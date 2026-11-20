package com.optionmgr.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * FEAT-052: a parsed Yahoo {@code /v7/finance/options} response. {@code time} = {@code quote.regularMarketTime}
 * (Q269), the quote time of the underlying and of every contract of the response; {@code expirationDates} = the UTC
 * dates of {@code result[0].expirationDates}.
 */
public record YahooChain(Instant time, BigDecimal price, BigDecimal bid, BigDecimal ask, BigDecimal prevClose,
                         List<LocalDate> expirationDates, List<YahooOption> options) {

    public YahooChain {
        expirationDates = List.copyOf(expirationDates);
        options = List.copyOf(options);
    }

    /** {@code regularMarketPrice} &gt; 0. */
    public boolean hasPrice() {
        return QuotePrices.positive(price);
    }

    /** The requested expiration is one Yahoo lists (otherwise Yahoo answered another expiration's chain). */
    public boolean listsExpiration(LocalDate date) {
        return expirationDates.contains(date);
    }
}
