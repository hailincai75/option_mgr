package com.optionmgr.cash.domain;

/** One movement in the replay together with the cash state just before and just after it. */
public record ReplayStep(CashMovement movement, CashBalance before, CashBalance after) {
}
