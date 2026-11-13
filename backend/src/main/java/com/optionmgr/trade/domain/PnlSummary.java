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
import java.util.Objects;
import java.util.TreeMap;

/**
 * FEAT-008: realized P&amp;L of a set of closed records, in total, by symbol (ascending, character order as in Q35) and
 * by New York calendar month of the close / sale (newest first; months without records do not appear, Q49). FEAT-040:
 * records are option closes / expiries and stock sales; options and stocks of one symbol share a row, and every
 * total and row also gives the option / stock split (Q91). Pure Java.
 */
public record PnlSummary(Breakdown total, List<SymbolGroup> bySymbol, List<MonthGroup> byMonth) {

    /** One closed record: its type, symbol, close / sale time and realized P&amp;L. */
    public record Entry(ClosedRecordType type, String symbol, Instant closedAt, BigDecimal realizedPnl) {
        public Entry {
            Objects.requireNonNull(type, "type");
        }
    }

    public record Group(int closedCount, BigDecimal realizedPnl) {
        public static final Group EMPTY = new Group(0, Money.ZERO);

        Group plus(BigDecimal pnl) {
            return new Group(closedCount + 1, realizedPnl.add(pnl));
        }
    }

    /** A total and its option / stock parts (all = option + stock). */
    public record Breakdown(Group all, Group option, Group stock) {
        public static final Breakdown EMPTY = new Breakdown(Group.EMPTY, Group.EMPTY, Group.EMPTY);

        Breakdown plus(Entry e) {
            return new Breakdown(all.plus(e.realizedPnl()),
                    e.type() == ClosedRecordType.OPTION ? option.plus(e.realizedPnl()) : option,
                    e.type() == ClosedRecordType.STOCK ? stock.plus(e.realizedPnl()) : stock);
        }
    }

    public record SymbolGroup(String symbol, Breakdown breakdown) {
    }

    public record MonthGroup(YearMonth month, Breakdown breakdown) {
    }

    public static PnlSummary of(Collection<Entry> records) {
        Breakdown total = Breakdown.EMPTY;
        Map<String, Breakdown> symbols = new TreeMap<>();
        Map<YearMonth, Breakdown> months = new TreeMap<>(Comparator.reverseOrder());
        for (Entry e : records) {
            total = total.plus(e);
            symbols.put(e.symbol(), symbols.getOrDefault(e.symbol(), Breakdown.EMPTY).plus(e));
            YearMonth month = monthOf(e.closedAt());
            months.put(month, months.getOrDefault(month, Breakdown.EMPTY).plus(e));
        }
        return new PnlSummary(total,
                symbols.entrySet().stream().map(s -> new SymbolGroup(s.getKey(), s.getValue())).toList(),
                months.entrySet().stream().map(m -> new MonthGroup(m.getKey(), m.getValue())).toList());
    }

    /** The New York calendar month a close / sale belongs to. */
    public static YearMonth monthOf(Instant closedAt) {
        return YearMonth.from(MarketTime.toLocal(closedAt));
    }
}
