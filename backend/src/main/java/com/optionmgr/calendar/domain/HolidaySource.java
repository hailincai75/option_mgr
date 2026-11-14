package com.optionmgr.calendar.domain;

/** FEAT-009: where a full-day closure comes from. */
public enum HolidaySource {
    /** Stored Finnhub data (the year is covered by Finnhub). */
    FINNHUB,
    /** Jollyday NYSE rules (the year is not covered by Finnhub). */
    RULES
}
