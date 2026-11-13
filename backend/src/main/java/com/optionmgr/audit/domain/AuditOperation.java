package com.optionmgr.audit.domain;

/** Q12: an audited operation. Persisted by name. */
public enum AuditOperation {
    /** FEAT-033: account creation (no "before"). */
    CREATE,
    UPDATE,
    DELETE
}
