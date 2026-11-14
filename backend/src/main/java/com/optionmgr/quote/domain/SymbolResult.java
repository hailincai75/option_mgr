package com.optionmgr.quote.domain;

/** FEAT-016: overall outcome of one symbol in a run. */
public enum SymbolResult {
    /** The CBOE chain was fetched. */
    SUCCESS,
    /** The CBOE chain failed, Finnhub gave the underlying price. */
    FALLBACK,
    /** Neither the chain nor a price. */
    FAILED
}
