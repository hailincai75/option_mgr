package com.optionmgr.stock.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * FEAT-011 current stock position of one symbol: the lots with shares left, FIFO order. Shares and total cost are
 * sums of the lots; the average cost is for display only (Q88).
 */
public record StockPosition(String symbol, List<StockLot> lots) {

    /** Symbol ascending by character code (same as FEAT-003). */
    public static final Comparator<StockPosition> DISPLAY_ORDER = Comparator.comparing(StockPosition::symbol);

    /** Q88: per-share display values have 4 decimals, HALF_UP. */
    public static final int PER_SHARE_SCALE = 4;
    public static final RoundingMode PER_SHARE_ROUNDING = RoundingMode.HALF_UP;

    public StockPosition {
        lots = List.copyOf(lots);
        if (lots.isEmpty()) {
            throw new IllegalArgumentException("a position has at least one lot");
        }
    }

    public long shares() {
        return lots.stream().mapToLong(StockLot::remainingShares).sum();
    }

    public BigDecimal totalCost() {
        return lots.stream().map(StockLot::remainingCost).reduce(Money.ZERO, BigDecimal::add);
    }

    public BigDecimal averageCost() {
        return perShare(totalCost(), shares());
    }

    /** {@code amount / shares}, HALF_UP to 4 decimals (Q88), display only. */
    public static BigDecimal perShare(BigDecimal amount, long shares) {
        return amount.divide(BigDecimal.valueOf(shares), PER_SHARE_SCALE, PER_SHARE_ROUNDING);
    }
}
