package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import java.time.LocalDate;

/**
 * FEAT-016 / FEAT-052: the raw requests of a run (status + body text, or a network error). The parsing and session
 * handling are the same for every implementation: {@link HttpQuoteTransport} (real) or {@link InMemoryQuoteTransport}
 * (test / e2e fake sources).
 */
public interface QuoteTransport {

    /** FEAT-052: outcome of a Yahoo session handshake. */
    sealed interface Handshake {

        /** A crumb (the cookies stay inside the transport). */
        record Ok(String crumb) implements Handshake {
        }

        /** Failed; {@code detail} is short, e.g. {@code getcrumb HTTP 401}, without cookie or crumb. */
        record Failed(String detail) implements Handshake {
        }
    }

    /** CBOE delayed quotes (whole chain) of a symbol. */
    FetchAttempt cboe(String symbol);

    /** Finnhub {@code quote} of a symbol; {@link FetchAttempt.NoKey} without a key (no request). */
    FetchAttempt finnhubQuote(String symbol);

    /** FEAT-052: a new Yahoo session (cookie page, then getcrumb); previous cookies are dropped. */
    Handshake yahooHandshake();

    /**
     * FEAT-052: {@code GET <base>/v7/finance/options/{SYMBOL}?crumb=..[&date=<00:00 UTC of the date, Unix s>]};
     * {@code date} null = without {@code date} (stock-only symbol).
     */
    FetchAttempt yahooOptions(String symbol, LocalDate date, String crumb);
}
