package com.optionmgr.quote.domain;

/** FEAT-016 Q139: what the automatic mark is based on. */
public enum MarkBasis {
    /** (bid + ask) / 2, both &gt; 0. */
    MID,
    /** CBOE theoretical value. */
    THEO
}
