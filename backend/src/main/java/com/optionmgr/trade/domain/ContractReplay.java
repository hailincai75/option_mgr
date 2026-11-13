package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Replays all trades of one contract in FIFO order (time, entry order). Pure Java, no Spring.
 *
 * <ul>
 *   <li>Every opening trade (BTO / STO) becomes a lot. It must not go against an open position (Q7) and must use
 *       the open position's multiplier (Q23).</li>
 *   <li>Every closing trade (BTC / STC) must find a position of the opposite side (BTC closes short, STC closes
 *       long), not exceed it, and cover exactly the earliest one or more whole lots (Q21). It removes those lots;
 *       its {@link CloseMatch} gives the released frozen cash and the realized P&amp;L of the close as a whole.</li>
 *   <li>When the position returns to 0 its multiplier is forgotten: a new position may use any side/multiplier.</li>
 * </ul>
 *
 * Invalid trades are reported (see {@link ReplayedTrade#violations()}); an invalid close or an open against the
 * position is skipped so the replay can continue (only the first violation is ever reported to users).
 */
public final class ContractReplay {

    private ContractReplay() {
    }

    public static ContractHistory replay(Collection<LotTrade> trades) {
        List<LotTrade> ordered = new ArrayList<>(trades);
        ordered.sort(LotTrade.FIFO_ORDER);
        List<PositionLot> lots = new ArrayList<>();
        int multiplier = 0;
        List<ReplayedTrade> steps = new ArrayList<>(ordered.size());
        for (LotTrade t : ordered) {
            int position = signedPosition(lots);
            List<Integer> allowed = cumulativeContracts(lots);
            int earliest = lots.isEmpty() ? 0 : lots.get(0).remainingContracts();
            int multiplierBefore = multiplier;
            int effectiveMultiplier = t.multiplier() == LotTrade.INHERIT_MULTIPLIER ? multiplier : t.multiplier();
            Set<ViolationKind> violations = EnumSet.noneOf(ViolationKind.class);
            Optional<CloseMatch> match = Optional.empty();

            if (position != 0 && effectiveMultiplier != multiplier) {
                violations.add(ViolationKind.MULTIPLIER_MISMATCH);
            }
            if (t.action().isOpening()) {
                if (position != 0 && Integer.signum(position) != t.action().sign()) {
                    violations.add(ViolationKind.OPEN_AGAINST_POSITION);
                } else {
                    if (position == 0) {
                        multiplier = t.multiplier();
                    }
                    lots.add(PositionLot.opened(t));
                }
            } else {
                ViolationKind closeProblem = closeProblem(t, position, allowed);
                if (closeProblem != null) {
                    violations.add(closeProblem);
                } else {
                    int covered = allowed.indexOf(t.contracts()) + 1;
                    List<PositionLot> closed = new ArrayList<>(lots.subList(0, covered));
                    lots.subList(0, covered).clear();
                    BigDecimal released = closed.stream().map(PositionLot::remainingFrozen)
                            .reduce(Money.ZERO, BigDecimal::add);
                    // FEAT-013 Q8: an assignment's option P&L is 0 (the premium goes into the stock cost)
                    BigDecimal pnl = t.action() == TradeAction.ASG
                            ? Money.ZERO
                            : closed.stream().map(PositionLot::remainingNetAmount)
                                    .reduce(t.cashChange(), BigDecimal::add);
                    match = Optional.of(new CloseMatch(closed, released, pnl));
                    if (lots.isEmpty()) {
                        multiplier = 0;
                    }
                }
            }
            steps.add(new ReplayedTrade(t, position, multiplierBefore, allowed, earliest, effectiveMultiplier, violations, match));
        }
        return new ContractHistory(steps, lots);
    }

    private static ViolationKind closeProblem(LotTrade t, int position, List<Integer> allowed) {
        if (position == 0) {
            return ViolationKind.NO_POSITION;
        }
        // BTC (+) closes a short (-) position, STC (-) a long (+) one.
        if (Integer.signum(position) == t.action().sign()) {
            return ViolationKind.CLOSE_WRONG_DIRECTION;
        }
        if (t.contracts() > Math.abs(position)) {
            return ViolationKind.CLOSE_EXCEEDS_POSITION;
        }
        if (!allowed.contains(t.contracts())) {
            return ViolationKind.CLOSE_NOT_WHOLE_LOTS;
        }
        return null;
    }

    static int signedPosition(List<PositionLot> lots) {
        int sum = 0;
        for (PositionLot l : lots) {
            sum += l.action().signedContracts(l.remainingContracts());
        }
        return sum;
    }

    /** Cumulative remaining contracts of the earliest 1, 2, ... lots. */
    public static List<Integer> cumulativeContracts(List<PositionLot> lots) {
        List<Integer> out = new ArrayList<>(lots.size());
        int sum = 0;
        for (PositionLot l : lots) {
            sum += l.remainingContracts();
            out.add(sum);
        }
        return out;
    }
}
