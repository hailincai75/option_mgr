package com.optionmgr.quote.domain;

/** FEAT-016 / FEAT-052: outcome of the CBOE, Yahoo or Finnhub requests for a symbol. */
public enum SourceResult {
    SUCCESS,
    /** Finnhub only: no key configured, no request made. */
    NO_KEY,
    NETWORK_ERROR,
    HTTP_ERROR,
    INVALID_RESPONSE,
    /** FEAT-052 Yahoo only: no data request for the symbol because the session handshake failed. */
    HANDSHAKE_FAILED
}
