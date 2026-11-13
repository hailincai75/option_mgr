package com.optionmgr.stock.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * FEAT-011 FIFO replay of the stock records of one account and one symbol, in (time, entry order). BUY and
 * TRANSFER_IN add a lot; SELL takes shares from the earliest lots with shares left:
 * <ul>
 *   <li>a lot taken completely contributes its remaining cost;</li>
 *   <li>a lot taken partly contributes remaining cost x shares taken / remaining shares, HALF_UP to cents
 *       (Q69, Q87), and keeps the rest of its cost.</li>
 * </ul>
 * A sale of more shares than held at its time (Q15) is a violation: it is recorded and not applied, so the replay
 * can continue. Pure Java, no Spring.
 */
public final class StockReplay {

    /** Rounding of a partial lot's cost (Q69 / Q87). */
    public static final RoundingMode PARTIAL_COST_ROUNDING = RoundingMode.HALF_UP;

    private StockReplay() {
    }

    /**
     * One replayed record.
     *
     * @param record        the record
     * @param holdingBefore shares held right before it
     * @param sale          FIFO match of a valid sale; empty for lots and violating sales
     * @param violated      true for a sale of more shares than {@code holdingBefore}
     */
    public record Step(StockRecord record, long holdingBefore, Optional<SaleMatch> sale, boolean violated) {
    }

    /** All steps in replay order and the lots left at the end (remaining shares &gt; 0, FIFO order). */
    public record History(List<Step> steps, List<StockLot> openLots) {

        public History {
            steps = List.copyOf(steps);
            openLots = List.copyOf(openLots);
        }

        /** First violating sale in replay order. */
        public Optional<Step> firstViolation() {
            return steps.stream().filter(Step::violated).findFirst();
        }

        public Optional<Step> step(long recordId) {
            return steps.stream().filter(s -> s.record().id() == recordId).findFirst();
        }

        /** Current shares = sum of the open lots' remaining shares. */
        public long shares() {
            return openLots.stream().mapToLong(StockLot::remainingShares).sum();
        }
    }

    public static History replay(Collection<StockRecord> records) {
        List<StockRecord> ordered = new ArrayList<>(records);
        ordered.sort(StockRecord.REPLAY_ORDER);
        List<StockLot> lots = new ArrayList<>();
        List<Step> steps = new ArrayList<>(ordered.size());
        for (StockRecord r : ordered) {
            long holding = lots.stream().mapToLong(StockLot::remainingShares).sum();
            if (r.type().createsLot()) {
                lots.add(new StockLot(r.id(), r.type(), r.tradedAt(), r.shares(), r.amount(), r.shares(), r.amount(),
                        r.note()));
                steps.add(new Step(r, holding, Optional.empty(), false));
            } else if (r.shares() > holding) {
                steps.add(new Step(r, holding, Optional.empty(), true));
            } else {
                steps.add(new Step(r, holding, Optional.of(sell(lots, r)), false));
            }
        }
        return new History(steps, lots);
    }

    /** Takes {@code sale.shares()} from the earliest lots (enough shares are known to be held). */
    private static SaleMatch sell(List<StockLot> lots, StockRecord sale) {
        int toSell = sale.shares();
        List<LotSale> parts = new ArrayList<>();
        BigDecimal soldCost = Money.ZERO;
        for (int i = 0; i < lots.size() && toSell > 0; ) {
            StockLot lot = lots.get(i);
            int take = Math.min(toSell, lot.remainingShares());
            BigDecimal cost = take == lot.remainingShares()
                    ? lot.remainingCost()
                    : partialCost(lot.remainingCost(), take, lot.remainingShares());
            parts.add(new LotSale(lot.sourceId(), lot.type(), lot.tradedAt(), take, cost));
            soldCost = soldCost.add(cost);
            toSell -= take;
            if (take == lot.remainingShares()) {
                lots.remove(i);
            } else {
                lots.set(i, new StockLot(lot.sourceId(), lot.type(), lot.tradedAt(), lot.originalShares(),
                        lot.originalCost(), lot.remainingShares() - take, lot.remainingCost().subtract(cost),
                        lot.note()));
                i++;
            }
        }
        return new SaleMatch(parts, soldCost, sale.amount().subtract(soldCost));
    }

    /** Remaining cost x taken / remaining shares, HALF_UP to cents (Q69, Q87). */
    static BigDecimal partialCost(BigDecimal remainingCost, int take, int remainingShares) {
        return remainingCost.multiply(BigDecimal.valueOf(take))
                .divide(BigDecimal.valueOf(remainingShares), Money.SCALE, PARTIAL_COST_ROUNDING);
    }
}
