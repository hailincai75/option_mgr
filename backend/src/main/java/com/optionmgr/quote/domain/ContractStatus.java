package com.optionmgr.quote.domain;

/** FEAT-016 Q143: quote state of a held contract. */
public enum ContractStatus {
    /** In the latest successful chain of its symbol. */
    QUOTED,
    /** Had a quote, but the latest successful chain no longer lists it (last quote kept). */
    MISSING_FROM_SOURCE,
    /** Never had a quote ("无报价"). */
    NEVER_QUOTED
}
