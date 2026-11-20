package com.optionmgr.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * FEAT-016: stored latest data of one underlying symbol: automatic price (null = never), per-source results of the
 * last attempt, freshness of the price and of the option chain, manual override. FEAT-052: {@code lastYahooResult}.
 */
public record UnderlyingState(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, BigDecimal prevClose,
                              PriceSource source, Instant quoteTime, Instant lastPriceSuccessAt,
                              Instant lastChainSuccessAt, Instant lastAttemptAt, boolean priceFailed,
                              boolean chainFailed, SourceResult lastCboeResult, SourceResult lastFinnhubResult,
                              String lastFailureDetail, BigDecimal manualPrice, Instant manualEnteredAt,
                              SourceResult lastYahooResult) {

    /** FEAT-016 form (no Yahoo result). */
    public UnderlyingState(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, BigDecimal prevClose,
                           PriceSource source, Instant quoteTime, Instant lastPriceSuccessAt,
                           Instant lastChainSuccessAt, Instant lastAttemptAt, boolean priceFailed,
                           boolean chainFailed, SourceResult lastCboeResult, SourceResult lastFinnhubResult,
                           String lastFailureDetail, BigDecimal manualPrice, Instant manualEnteredAt) {
        this(symbol, price, bid, ask, prevClose, source, quoteTime, lastPriceSuccessAt, lastChainSuccessAt,
                lastAttemptAt, priceFailed, chainFailed, lastCboeResult, lastFinnhubResult, lastFailureDetail,
                manualPrice, manualEnteredAt, null);
    }

    /** Nothing fetched, no override. */
    public static UnderlyingState empty(String symbol) {
        return new UnderlyingState(symbol, null, null, null, null, null, null, null, null, null, false, false, null,
                null, null, null, null, null);
    }

    public UnderlyingState withManual(BigDecimal price, Instant at) {
        return new UnderlyingState(symbol, this.price, bid, ask, prevClose, source, quoteTime, lastPriceSuccessAt,
                lastChainSuccessAt, lastAttemptAt, priceFailed, chainFailed, lastCboeResult, lastFinnhubResult,
                lastFailureDetail, price, price == null ? null : at, lastYahooResult);
    }

    public boolean manual() {
        return manualPrice != null;
    }

    /** Displayed price: the override, else the automatic one. */
    public BigDecimal effectivePrice() {
        return manualPrice != null ? manualPrice : price;
    }
}
