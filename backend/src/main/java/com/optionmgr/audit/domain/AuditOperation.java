package com.optionmgr.audit.domain;

/** Q12: an audited operation. Persisted by name. */
public enum AuditOperation {
    /** FEAT-033: account creation (no "before"). */
    CREATE,
    UPDATE,
    DELETE,
    /** FEAT-043: a contract's expiration date moved off an ad-hoc closure (several trades, one record). */
    ADJUST_EXPIRATION
}
