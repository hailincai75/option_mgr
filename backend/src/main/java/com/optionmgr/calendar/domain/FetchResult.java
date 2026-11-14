package com.optionmgr.calendar.domain;

/** FEAT-009: outcome of one attempt to fetch the Finnhub market holidays. */
public enum FetchResult {
    SUCCESS,
    /** No FINNHUB_API_KEY (environment and .env) or a blank one. */
    NO_KEY,
    /** Connection failure or timeout. */
    NETWORK_ERROR,
    /** Response status other than 200. */
    HTTP_ERROR,
    /** Not JSON, no data array, or an entry with an invalid date. */
    INVALID_RESPONSE
}
