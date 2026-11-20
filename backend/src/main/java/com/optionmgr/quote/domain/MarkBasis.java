package com.optionmgr.quote.domain;

/** FEAT-016 Q139 / FEAT-052 Q271: what the automatic mark is based on. */
public enum MarkBasis {
    /** (bid + ask) / 2: CBOE both &gt; 0; Yahoo a valid quote (ask &gt; 0, bid &ge; 0, ask &ge; bid). */
    MID,
    /** CBOE theoretical value. */
    THEO,
    /** FEAT-052 Q271: Yahoo {@code lastPrice} of a contract that never had a valid Yahoo quote. */
    LAST
}
