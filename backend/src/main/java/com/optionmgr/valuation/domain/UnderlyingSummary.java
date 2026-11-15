package com.optionmgr.valuation.domain;

import com.optionmgr.common.money.Money;
import com.optionmgr.trade.domain.PnlSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * FEAT-023: one summary per underlying that has a current position (option or stock). Market value / unrealized P&amp;L
 * add up the FEAT-017 {@link RowValuation}s of the group exactly as {@link UnrealizedSummary} does (same
 * {@link UnrealizedSummary.Group} accumulator, same {@code excludedLabel} text); realized P&amp;L is the FEAT-040
 * {@link PnlSummary} by-symbol breakdown of all time. Everything is a sum of already rounded scale-2 values, so
 * nothing is rounded here. Pure Java.
 */
public record UnderlyingSummary(List<Group> groups) {

    /** One option position row: its symbol, opening net total (short +, long −) and FEAT-017 valuation. */
    public record OptionRow(String symbol, BigDecimal openingNetTotal, RowValuation valuation) {
        public OptionRow {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(openingNetTotal, "openingNetTotal");
            Objects.requireNonNull(valuation, "valuation");
        }
    }

    /** One stock position row (shares &gt; 0): its symbol and FEAT-017 valuation. */
    public record StockRow(String symbol, RowValuation valuation) {
        public StockRow {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(valuation, "valuation");
        }
    }

    /**
     * One underlying's card summary.
     *
     * @param realized      all-time realized P&amp;L (never null; {@link PnlSummary.Breakdown#EMPTY} without records)
     * @param marketValue   sum of the OK rows' market values; null when no row is OK
     * @param unrealizedPnl sum of the OK rows' unrealized P&amp;L; null when no row is OK
     * @param totalPnl      realized + (unrealized or 0); never null
     * @param netPremium    sum of all option rows' opening net totals; null without option positions
     */
    public record Group(String symbol, PnlSummary.Breakdown realized, BigDecimal marketValue, BigDecimal unrealizedPnl,
                        BigDecimal totalPnl, BigDecimal netPremium, int optionPositionCount,
                        boolean hasStockPosition, int includedCount, int noQuoteCount, int expiredPendingCount,
                        String excludedLabel) {
    }

    /**
     * Groups the rows by symbol (character order, Q203). {@code realized} gives the all-time breakdown of a symbol,
     * or null when it has no closed records.
     */
    public static UnderlyingSummary of(List<OptionRow> options, List<StockRow> stocks,
                                       Function<String, PnlSummary.Breakdown> realized) {
        Map<String, Acc> bySymbol = new TreeMap<>();
        for (OptionRow r : options) {
            Acc a = bySymbol.computeIfAbsent(r.symbol(), s -> new Acc());
            a.valuation = a.valuation.add(r.valuation());
            a.netPremium = a.netPremium.add(r.openingNetTotal());
            a.optionCount++;
        }
        for (StockRow r : stocks) {
            Acc a = bySymbol.computeIfAbsent(r.symbol(), s -> new Acc());
            a.valuation = a.valuation.add(r.valuation());
            a.hasStock = true;
        }
        List<Group> groups = new ArrayList<>(bySymbol.size());
        bySymbol.forEach((symbol, a) -> groups.add(a.toGroup(symbol, realized.apply(symbol))));
        return new UnderlyingSummary(List.copyOf(groups));
    }

    private static final class Acc {
        UnrealizedSummary.Group valuation = UnrealizedSummary.Group.empty();
        BigDecimal netPremium = Money.ZERO;
        int optionCount;
        boolean hasStock;

        Group toGroup(String symbol, PnlSummary.Breakdown realized) {
            PnlSummary.Breakdown r = realized == null ? PnlSummary.Breakdown.EMPTY : realized;
            boolean anyIncluded = valuation.includedCount() > 0;
            BigDecimal mv = anyIncluded ? valuation.marketValue() : null;
            BigDecimal pnl = anyIncluded ? valuation.unrealizedPnl() : null;
            BigDecimal total = pnl == null ? r.all().realizedPnl() : r.all().realizedPnl().add(pnl);
            return new Group(symbol, r, mv, pnl, total, optionCount == 0 ? null : netPremium, optionCount, hasStock,
                    valuation.includedCount(), valuation.noQuoteCount(), valuation.expiredPendingCount(),
                    UnrealizedSummary.excludedLabel(valuation.noQuoteCount(), valuation.expiredPendingCount()));
        }
    }
}
