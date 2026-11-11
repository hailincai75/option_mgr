package com.optionmgr.cash.domain;

import java.math.BigDecimal;

/** Deposit (cash in) or withdrawal (cash out). The user always enters a positive amount. */
public enum TransferType {
    DEPOSIT(CashSourceType.DEPOSIT),
    WITHDRAWAL(CashSourceType.WITHDRAWAL);

    private final CashSourceType sourceType;

    TransferType(CashSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public CashSourceType sourceType() {
        return sourceType;
    }

    /** Signed ledger amount for a positive transfer amount: deposits positive, withdrawals negative. */
    public BigDecimal signedAmount(BigDecimal positiveAmount) {
        if (positiveAmount.signum() <= 0) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        return this == DEPOSIT ? positiveAmount : positiveAmount.negate();
    }
}
