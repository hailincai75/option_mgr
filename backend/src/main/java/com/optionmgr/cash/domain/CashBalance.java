package com.optionmgr.cash.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;

/** Cash state at one point: total, frozen and (derived) available = total - frozen. Scale 2. */
public record CashBalance(BigDecimal total, BigDecimal frozen) {

    public static final CashBalance EMPTY = new CashBalance(Money.ZERO, Money.ZERO);

    public CashBalance {
        total = Money.of(total);
        frozen = Money.of(frozen);
    }

    public BigDecimal available() {
        return total.subtract(frozen);
    }

    public CashBalance apply(CashMovement movement) {
        return new CashBalance(total.add(movement.cashChange()), frozen.add(movement.frozenChange()));
    }

    /** Q13/Q19: total cash and available cash must never be negative. */
    public boolean isValid() {
        return total.signum() >= 0 && available().signum() >= 0;
    }
}
