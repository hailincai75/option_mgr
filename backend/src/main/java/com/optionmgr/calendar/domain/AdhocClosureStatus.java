package com.optionmgr.calendar.domain;

/** FEAT-042: state of a detected ad-hoc closure. */
public enum AdhocClosureStatus {
    /** Alerted / tagged (rules 3-5). */
    ACTIVE,
    /** Removed by a later fetch (Q127): no longer alerted. */
    REVOKED
}
