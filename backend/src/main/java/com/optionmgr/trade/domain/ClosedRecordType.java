package com.optionmgr.trade.domain;

/**
 * FEAT-040: kind of a realized-P&amp;L record on the closed-trades list, the P&amp;L summary and the dashboard. Option
 * trades and stock records have separate id sequences, so a record is identified by type + id.
 */
public enum ClosedRecordType {
    /** An option close (BTC / STC) or expiry (EXP). */
    OPTION,
    /** A stock sale (FEAT-011 SELL). */
    STOCK
}
