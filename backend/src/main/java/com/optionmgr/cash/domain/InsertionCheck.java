package com.optionmgr.cash.domain;

import java.util.Optional;

/**
 * Result of replaying the ledger with a new movement inserted (Q19).
 *
 * @param inserted       the new movement's step (its {@code before} is the cash state right before it)
 * @param firstViolation the first step, from the inserted one onwards, after which total or available cash is
 *                       negative; empty if the insertion is allowed
 */
public record InsertionCheck(ReplayStep inserted, Optional<ReplayStep> firstViolation) {

    public boolean allowed() {
        return firstViolation.isEmpty();
    }

    /** True if the inserted movement itself is the first point that goes negative. */
    public boolean violatedAtInsertedPoint() {
        return firstViolation.map(v -> v == inserted).orElse(false);
    }
}
