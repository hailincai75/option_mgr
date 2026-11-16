package com.optionmgr.valuation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * FEAT-048 market value / unrealized P&amp;L of each lot of a position row (pure Java). Rules: Q239 (a lot follows its
 * row: same mark / price, same status and label; nothing is computed unless the row is OK), Q238 A (in FIFO order every
 * lot but the last is rounded HALF_UP to cents on its own, the last one takes the row market value minus the others,
 * so the lots always add up to the row to the cent), Q240 (percent = P&amp;L x 100 / |opening net| for options, / cost
 * for stocks, HALF_UP 2 decimals, {@code null} for a 0.00 denominator, as FEAT-017 Q155).
 */
public final class LotValuation {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(UnrealizedValuation.SCALE, RoundingMode.UNNECESSARY);

    private LotValuation() {
    }

    /** One option lot: remaining contracts and remaining opening net amount (short +, long −, scale 2). */
    public record OptionLotSize(int remainingContracts, BigDecimal remainingNetAmount) {
        public OptionLotSize {
            Objects.requireNonNull(remainingNetAmount, "remainingNetAmount");
        }
    }

    /** One stock lot: remaining shares and remaining cost (scale 2). */
    public record StockLotSize(long remainingShares, BigDecimal remainingCost) {
        public StockLotSize {
            Objects.requireNonNull(remainingCost, "remainingCost");
        }
    }

    /**
     * @param row          the FEAT-017 valuation of the row (computed with the same {@code mark})
     * @param longPosition {@code true} for a long row (market value positive), {@code false} for short
     * @param mark         the row's displayed mark (ignored unless {@code row} is OK)
     * @param lots         the row's lots in FIFO order
     * @return one valuation per lot, same order
     */
    public static List<RowValuation> optionLots(RowValuation row, boolean longPosition, int multiplier,
                                                BigDecimal mark, List<OptionLotSize> lots) {
        if (row.status() != ValuationStatus.OK) {
            return unavailable(row.status(), lots.size());
        }
        List<RowValuation> out = new ArrayList<>(lots.size());
        BigDecimal others = ZERO;
        for (int i = 0; i < lots.size(); i++) {
            OptionLotSize l = lots.get(i);
            BigDecimal mv;
            if (i < lots.size() - 1) {
                BigDecimal size = mark.multiply(BigDecimal.valueOf((long) l.remainingContracts() * multiplier))
                        .setScale(UnrealizedValuation.SCALE, UnrealizedValuation.ROUNDING);
                mv = longPosition ? size : size.negate();
                others = others.add(mv);
            } else {
                mv = row.marketValue().subtract(others);
            }
            BigDecimal net = l.remainingNetAmount().setScale(UnrealizedValuation.SCALE, RoundingMode.UNNECESSARY);
            BigDecimal pnl = net.add(mv);
            out.add(new RowValuation(ValuationStatus.OK, mv, pnl, UnrealizedValuation.percent(pnl, net.abs())));
        }
        return List.copyOf(out);
    }

    /**
     * @param row   the FEAT-017 valuation of the row (computed with the same {@code price})
     * @param price the row's displayed price (ignored unless {@code row} is OK)
     * @param lots  the row's lots in FIFO order
     * @return one valuation per lot, same order
     */
    public static List<RowValuation> stockLots(RowValuation row, BigDecimal price, List<StockLotSize> lots) {
        if (row.status() != ValuationStatus.OK) {
            return unavailable(row.status(), lots.size());
        }
        List<RowValuation> out = new ArrayList<>(lots.size());
        BigDecimal others = ZERO;
        for (int i = 0; i < lots.size(); i++) {
            StockLotSize l = lots.get(i);
            BigDecimal mv;
            if (i < lots.size() - 1) {
                mv = price.multiply(BigDecimal.valueOf(l.remainingShares()))
                        .setScale(UnrealizedValuation.SCALE, UnrealizedValuation.ROUNDING);
                others = others.add(mv);
            } else {
                mv = row.marketValue().subtract(others);
            }
            BigDecimal cost = l.remainingCost().setScale(UnrealizedValuation.SCALE, RoundingMode.UNNECESSARY);
            BigDecimal pnl = mv.subtract(cost);
            out.add(new RowValuation(ValuationStatus.OK, mv, pnl, UnrealizedValuation.percent(pnl, cost)));
        }
        return List.copyOf(out);
    }

    private static List<RowValuation> unavailable(ValuationStatus status, int n) {
        List<RowValuation> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(RowValuation.unavailable(status));
        }
        return List.copyOf(out);
    }
}
