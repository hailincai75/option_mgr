package com.optionmgr.quote.domain;

/** FEAT-016: what started a fetch run. */
public enum RunTrigger {
    /** Every 15 minutes 09:30-16:00 New York on trading days (and at startup inside the window). */
    SCHEDULED,
    /** 16:20 New York on trading days; also stores the close snapshot (Q142). */
    CLOSE,
    /** "立即刷新". */
    REFRESH
}
