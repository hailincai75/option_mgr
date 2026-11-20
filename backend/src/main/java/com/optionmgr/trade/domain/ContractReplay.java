package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Replays all trades of one contract in replay order (time, entry order). Pure Java, no Spring.
 *
 * <ul>
 *   <li>Every opening trade (BTO / STO) becomes a lot. It must not go against an open position (Q7) and must use
 *       the open position's multiplier (Q23).</li>
 *   <li>A closing trade must find a position of the opposite side (BTC / ASG close short, STC / EXR long).
 *       <ul>
 *         <li>FEAT-051 (Q255): a BTC / STC with a lot selection ({@link LotTrade#lotTradeIds()}) closes exactly
 *             those whole lots: each must be open at its point (opened before it, not closed by an earlier record)
 *             and their contracts must add up to the close's ({@link ViolationKind#CLOSE_LOTS_INVALID} otherwise,
 *             details in {@link ReplayedTrade#lotProblems()}).</li>
 *         <li>Without a selection (a new close in FIFO mode, Q257, and every EXP / ASG / EXR, Q260) it must not
 *             exceed the position and must cover exactly the earliest one or more whole remaining lots (Q21).</li>
 *       </ul>
 *       It removes those lots; its {@link CloseMatch} gives the released frozen cash and the realized P&amp;L of the
 *       close as a whole.</li>
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
        Map<Long, LotTrade> opens = new HashMap<>();
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            LotTrade t = ordered.get(i);
            if (t.action().isOpening()) {
                opens.putIfAbsent(t.tradeId(), t);
                order.putIfAbsent(t.tradeId(), i);
            }
        }
        Map<Long, LotTrade> closedBy = new HashMap<>();
        List<PositionLot> lots = new ArrayList<>();
        int multiplier = 0;
        List<ReplayedTrade> steps = new ArrayList<>(ordered.size());
        for (LotTrade t : ordered) {
            int position = signedPosition(lots);
            List<Integer> allowed = cumulativeContracts(lots);
            List<PositionLot> lotsBefore = List.copyOf(lots);
            int earliest = lots.isEmpty() ? 0 : lots.get(0).remainingContracts();
            int multiplierBefore = multiplier;
            int effectiveMultiplier = t.multiplier() == LotTrade.INHERIT_MULTIPLIER ? multiplier : t.multiplier();
            Set<ViolationKind> violations = EnumSet.noneOf(ViolationKind.class);
            Optional<CloseMatch> match = Optional.empty();
            List<LotProblem> problems = List.of();

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
                ViolationKind closeProblem = t.hasSelection()
                        ? positionProblem(t, position)
                        : closeProblem(t, position, allowed);
                if (closeProblem == null && t.hasSelection()) {
                    problems = selectionProblems(t, lots, opens, order, closedBy);
                    if (!problems.isEmpty()) {
                        closeProblem = ViolationKind.CLOSE_LOTS_INVALID;
                    }
                }
                if (closeProblem != null) {
                    violations.add(closeProblem);
                } else {
                    List<PositionLot> closed;
                    if (t.hasSelection()) {
                        Set<Long> selected = Set.copyOf(t.lotTradeIds());
                        closed = lots.stream().filter(l -> selected.contains(l.tradeId())).toList();
                        lots.removeIf(l -> selected.contains(l.tradeId()));
                    } else {
                        int covered = allowed.indexOf(t.contracts()) + 1;
                        closed = new ArrayList<>(lots.subList(0, covered));
                        lots.subList(0, covered).clear();
                    }
                    closed.forEach(l -> closedBy.put(l.tradeId(), t));
                    BigDecimal released = closed.stream().map(PositionLot::remainingFrozen)
                            .reduce(Money.ZERO, BigDecimal::add);
                    // FEAT-013 Q8 / FEAT-014 Q111: an assignment's / exercise's option P&L is 0 (the premium goes
                    // into the stock leg)
                    BigDecimal pnl = t.action().hasStockLeg()
                            ? Money.ZERO
                            : closed.stream().map(PositionLot::remainingNetAmount)
                                    .reduce(t.cashChange(), BigDecimal::add);
                    match = Optional.of(new CloseMatch(closed, released, pnl));
                    if (lots.isEmpty()) {
                        multiplier = 0;
                    }
                }
            }
            steps.add(new ReplayedTrade(t, position, multiplierBefore, allowed, earliest, effectiveMultiplier,
                    violations, match, lotsBefore, problems));
        }
        return new ContractHistory(steps, lots);
    }

    private static ViolationKind positionProblem(LotTrade t, int position) {
        if (position == 0) {
            return ViolationKind.NO_POSITION;
        }
        // BTC (+) closes a short (-) position, STC (-) a long (+) one.
        if (Integer.signum(position) == t.action().sign()) {
            return ViolationKind.CLOSE_WRONG_DIRECTION;
        }
        return null;
    }

    private static ViolationKind closeProblem(LotTrade t, int position, List<Integer> allowed) {
        ViolationKind p = positionProblem(t, position);
        if (p != null) {
            return p;
        }
        if (t.contracts() > Math.abs(position)) {
            return ViolationKind.CLOSE_EXCEEDS_POSITION;
        }
        if (!allowed.contains(t.contracts())) {
            return ViolationKind.CLOSE_NOT_WHOLE_LOTS;
        }
        return null;
    }

    /**
     * FEAT-051: the selected lots (distinct, in replay order of the opens; unknown ids last) that are not open at
     * the close's point; if all are open but their contracts differ from the close's, one CONTRACTS_MISMATCH.
     */
    private static List<LotProblem> selectionProblems(LotTrade t, List<PositionLot> lots, Map<Long, LotTrade> opens,
                                                      Map<Long, Integer> order, Map<Long, LotTrade> closedBy) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(t.lotTradeIds()));
        ids.sort(Comparator.comparingInt(id -> order.getOrDefault(id, Integer.MAX_VALUE)));
        Map<Long, PositionLot> open = new HashMap<>();
        lots.forEach(l -> open.put(l.tradeId(), l));
        List<LotProblem> out = new ArrayList<>();
        int sum = 0;
        for (long id : ids) {
            PositionLot l = open.get(id);
            if (l != null) {
                sum += l.remainingContracts();
            } else if (closedBy.containsKey(id)) {
                out.add(new LotProblem(id, opens.get(id), LotProblem.Reason.ALREADY_CLOSED, closedBy.get(id), 0));
            } else {
                out.add(new LotProblem(id, opens.get(id), LotProblem.Reason.NOT_YET_OPEN, null, 0));
            }
        }
        if (out.isEmpty() && (ids.isEmpty() || sum != t.contracts())) {
            long first = ids.isEmpty() ? 0 : ids.get(0);
            out.add(new LotProblem(first, opens.get(first), LotProblem.Reason.CONTRACTS_MISMATCH, null, sum));
        }
        return out;
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
