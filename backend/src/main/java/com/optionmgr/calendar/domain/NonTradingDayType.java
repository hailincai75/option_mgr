package com.optionmgr.calendar.domain;

/** FEAT-009: why a date is not a US trading day. */
public enum NonTradingDayType {
    /** Saturday or Sunday. */
    WEEKEND,
    /** A weekday on which the exchange is closed all day. */
    HOLIDAY
}
