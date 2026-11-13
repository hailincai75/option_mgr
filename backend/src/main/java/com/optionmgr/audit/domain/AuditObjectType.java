package com.optionmgr.audit.domain;

/** Kind of audited record. Persisted by name. */
public enum AuditObjectType {
    /** option_trade (FEAT-001 / FEAT-004). */
    OPTION_TRADE,
    /** cash_transfer: deposit or withdrawal (FEAT-007). */
    CASH_TRANSFER,
    /** FEAT-033 account (create / rename, Q59). */
    ACCOUNT,
    /** FEAT-011 stock_trade (buy / sell / transfer-in). */
    STOCK_TRADE
}
