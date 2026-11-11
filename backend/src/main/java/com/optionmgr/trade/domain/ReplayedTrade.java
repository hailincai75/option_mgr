package com.optionmgr.trade.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One trade in the contract replay with the state just before it.
 *
 * @param trade                  the trade
 * @param positionBefore         signed open contracts just before it (+ long, - short, 0 flat)
 * @param multiplierBefore       multiplier of the open position just before it; 0 when flat
 * @param allowedCloseContracts  cumulative contracts of the earliest 1, 2, ... whole lots just before it
 * @param earliestLotContracts   contracts of the earliest open lot just before it; 0 when flat
 * @param effectiveMultiplier    the trade's multiplier (a new close inherits the position's)
 * @param violations             why the trade is invalid at this point; empty if valid
 * @param match                  for a valid close: the lots it covers
 */
public record ReplayedTrade(LotTrade trade, int positionBefore, int multiplierBefore,
                            List<Integer> allowedCloseContracts, int earliestLotContracts, int effectiveMultiplier,
                            Set<ViolationKind> violations, Optional<CloseMatch> match) {

    public ReplayedTrade {
        allowedCloseContracts = List.copyOf(allowedCloseContracts);
        violations = Set.copyOf(violations);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }
}
