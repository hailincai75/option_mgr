package com.optionmgr.quote.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** FEAT-016: Finnhub {@code quote}: current price ({@code c} &gt; 0), previous close, quote time (null if missing). */
public record FinnhubQuote(BigDecimal price, BigDecimal prevClose, Instant time) {
}
