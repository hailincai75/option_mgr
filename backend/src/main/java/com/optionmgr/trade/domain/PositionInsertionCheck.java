package com.optionmgr.trade.domain;

import java.util.Optional;

/**
 * Result of replaying a contract's trades with a new trade inserted.
 *
 * @param inserted           the new trade's step ({@code positionBefore} = position at its time point)
 * @param directionConflict  first step, from the new trade on, that opens against the position (Q7)
 * @param multiplierMismatch first step, from the new trade on, whose multiplier differs from the position's (Q23)
 */
public record PositionInsertionCheck(PositionStep inserted, Optional<PositionStep> directionConflict,
                                     Optional<PositionStep> multiplierMismatch) {

    public boolean isInserted(PositionStep step) {
        return step == inserted;
    }
}
