package com.optionmgr.cash.domain;

import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;

/** User-facing (Chinese) texts for a rejected insertion, see docs/api/api-contract.md CASH_INSUFFICIENT. */
public final class CashShortfallMessages {

    /** Prefix for withdrawals and purchases (FEAT-007, FEAT-001 BTO). */
    public static final String INSUFFICIENT = "可用现金不足";
    /** Prefix for the collateral of a cash-secured put (FEAT-001 STO put, Q18). */
    public static final String INSUFFICIENT_FOR_PUT_COLLATERAL = "可用现金不足以担保卖出 put";

    private CashShortfallMessages() {
    }

    /**
     * "可用现金不足：需要 X，可用 Y" when the inserted point itself goes negative (Y = available right before it);
     * otherwise "可用现金不足：需要 X；T 时可用现金将变为 Z" for the first later point that goes negative.
     */
    public static String insufficient(BigDecimal required, InsertionCheck check) {
        return insufficient(INSUFFICIENT, required, check);
    }

    /** As {@link #insufficient(BigDecimal, InsertionCheck)} with another prefix. */
    public static String insufficient(String prefix, BigDecimal required, InsertionCheck check) {
        ReplayStep violation = check.firstViolation()
                .orElseThrow(() -> new IllegalArgumentException("insertion is allowed"));
        if (check.violatedAtInsertedPoint()) {
            return notEnoughNow(prefix, required, check.inserted().before().available());
        }
        return prefix + "：需要 " + Money.display(required) + "；"
                + MarketTime.displayMinute(violation.movement().occurredAt())
                + " 时可用现金将变为 " + Money.display(violation.after().available());
    }

    /** "<prefix>：需要 X，可用 Y". */
    public static String notEnoughNow(String prefix, BigDecimal required, BigDecimal available) {
        return prefix + "：需要 " + Money.display(required) + "，可用 " + Money.display(available);
    }
}
