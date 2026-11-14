package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;

/**
 * FEAT-016: the raw requests of a run (status + body text, or a network error). The parsing is the same for every
 * implementation: {@link HttpQuoteTransport} (real) or {@link InMemoryQuoteTransport} (test / e2e fake sources).
 */
public interface QuoteTransport {

    /** CBOE delayed quotes (whole chain) of a symbol. */
    FetchAttempt cboe(String symbol);

    /** Finnhub {@code quote} of a symbol; {@link FetchAttempt.NoKey} without a key (no request). */
    FetchAttempt finnhubQuote(String symbol);
}
