package com.optionmgr.trade.domain;

/** Q34: where a position stands relative to its expiration date (New York calendar days). */
public enum ExpirationStatus {
    /** dte &gt; 0. */
    ACTIVE,
    /** dte = 0: expires today. */
    EXPIRES_TODAY,
    /** dte &lt; 0: past expiration, not processed yet (M2). */
    EXPIRED;

    public static ExpirationStatus of(long dte) {
        if (dte > 0) {
            return ACTIVE;
        }
        return dte == 0 ? EXPIRES_TODAY : EXPIRED;
    }
}
