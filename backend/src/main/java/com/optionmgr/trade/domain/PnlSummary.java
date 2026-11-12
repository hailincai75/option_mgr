package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FEAT-008: realized P&amp;L of a set of closes, in total, by symbol (ascending, character order as in Q35) and by
 * New York calendar month of the close (newest first; months without closes do not appear, Q49). Pure Java.
 */
public record PnlSummary(Group total, List<SymbolGroup> bySymbol, List<MonthGroup> byMonth) {

    /** One close: its symbol, close time and realized P&amp;L. */
    public record Entry(String symbol, Instant closedAt, BigDecimal realizedPnl) {
    }

    public record Group(int closedCount, BigDecimal realizedPnl) {
        public static final Group EMPTY = new Group(0, Money.ZERO);

        Group plus(BigDecimal pnl) {
            return new Group(closedCount + 1, realizedPnl.add(pnl));
        }
    }

    public record SymbolGroup(String symbol, int closedCount, BigDecimal realizedPnl) {
    }

    public record MonthGroup(YearMonth month, int closedCount, BigDecimal realizedPnl) {
    }

    public static PnlSummary of(Collection<Entry> closes) {
        Group total = Group.EMPTY;
        Map<String, Group> symbols = new TreeMap<>();
        Map<YearMonth, Group> months = new TreeMap<>(Comparator.reverseOrder());
        for (Entry e : closes) {
            total = total.plus(e.realizedPnl());
            symbols.merge(e.symbol(), Group.EMPTY.plus(e.realizedPnl()), PnlSummary::merge);
            months.merge(monthOf(e.closedAt()), Group.EMPTY.plus(e.realizedPnl()), PnlSummary::merge);
        }
        return new PnlSummary(total,
                symbols.entrySet().stream()
                        .map(s -> new SymbolGroup(s.getKey(), s.getValue().closedCount(), s.getValue().realizedPnl()))
                        .toList(),
                months.entrySet().stream()
                        .map(m -> new MonthGroup(m.getKey(), m.getValue().closedCount(), m.getValue().realizedPnl()))
                        .toList());
    }

    /** The New York calendar month a close belongs to. */
    public static YearMonth monthOf(Instant closedAt) {
        return YearMonth.from(MarketTime.toLocal(closedAt));
    }

    private static Group merge(Group a, Group b) {
        return new Group(a.closedCount() + b.closedCount(), a.realizedPnl().add(b.realizedPnl()));
    }
}
