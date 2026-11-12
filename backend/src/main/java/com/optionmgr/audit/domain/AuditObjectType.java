package com.optionmgr.audit.domain;

/** Kind of audited record. Persisted by name. */
public enum AuditObjectType {
    /** option_trade (FEAT-001 / FEAT-004). */
    OPTION_TRADE,
    /** cash_transfer: deposit or withdrawal (FEAT-007). */
    CASH_TRANSFER
}
