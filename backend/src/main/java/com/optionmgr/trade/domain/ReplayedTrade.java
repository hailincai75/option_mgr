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
 * @param lotsBefore             FEAT-051: the open lots just before it (FIFO order)
 * @param lotProblems            FEAT-051: for a close with a lot selection whose position / direction is fine: one
 *                               entry per selected lot that cannot be closed (in the lots' replay order), or a
 *                               single {@link LotProblem.Reason#CONTRACTS_MISMATCH}; empty otherwise
 */
public record ReplayedTrade(LotTrade trade, int positionBefore, int multiplierBefore,
                            List<Integer> allowedCloseContracts, int earliestLotContracts, int effectiveMultiplier,
                            Set<ViolationKind> violations, Optional<CloseMatch> match, List<PositionLot> lotsBefore,
                            List<LotProblem> lotProblems) {

    public ReplayedTrade {
        allowedCloseContracts = List.copyOf(allowedCloseContracts);
        violations = Set.copyOf(violations);
        lotsBefore = List.copyOf(lotsBefore);
        lotProblems = List.copyOf(lotProblems);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }
}
