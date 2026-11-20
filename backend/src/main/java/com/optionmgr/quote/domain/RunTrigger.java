package com.optionmgr.quote.domain;

/** FEAT-016 / FEAT-052: what started a fetch run. */
public enum RunTrigger {
    /** 10:00-16:00 New York on the hour on trading days (Q270), and at startup inside the window (Q147). */
    SCHEDULED,
    /** 16:20 New York on trading days; also stores the close snapshot (Q142). */
    CLOSE,
    /** "立即刷新". */
    REFRESH
}
