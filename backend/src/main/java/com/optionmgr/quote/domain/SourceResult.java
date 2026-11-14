package com.optionmgr.quote.domain;

/** FEAT-016: outcome of one CBOE or Finnhub request for a symbol. */
public enum SourceResult {
    SUCCESS,
    /** Finnhub only: no key configured, no request made. */
    NO_KEY,
    NETWORK_ERROR,
    HTTP_ERROR,
    INVALID_RESPONSE
}
