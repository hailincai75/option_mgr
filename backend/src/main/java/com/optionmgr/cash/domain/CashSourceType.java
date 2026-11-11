package com.optionmgr.cash.domain;

/**
 * Where a cash ledger entry comes from. Later features add values (e.g. OPTION_OPEN for FEAT-001,
 * OPTION_CLOSE for FEAT-004); adding a value is backward compatible. Persisted by name (max 40 chars).
 */
public enum CashSourceType {
    DEPOSIT,
    WITHDRAWAL
}
