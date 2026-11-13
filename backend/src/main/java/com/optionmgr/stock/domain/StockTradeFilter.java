package com.optionmgr.stock.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * FEAT-011 stock trade list filter (same rules as FEAT-002). Every criterion is optional; criteria are combined with
 * AND, the selected types with OR.
 *
 * @param symbol    normalized symbol, exact match (Q31)
 * @param types     selected types; empty = all
 * @param startDate New York date of the record time &gt;= startDate (Q33; a transfer-in is at 00:00 of its date)
 * @param endDate   New York date of the record time &lt;= endDate
 */
public record StockTradeFilter(String symbol, Set<StockTradeType> types, LocalDate startDate, LocalDate endDate) {

    public static final StockTradeFilter NONE = new StockTradeFilter(null, Set.of(), null, null);

    public StockTradeFilter {
        types = types == null || types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate after endDate");
        }
    }

    /** Inclusive lower bound: 00:00 New York on startDate. */
    public Optional<Instant> tradedFrom() {
        return Optional.ofNullable(startDate).map(d -> d.atStartOfDay(MarketTime.ZONE).toInstant());
    }

    /** Exclusive upper bound: 00:00 New York on the day after endDate. */
    public Optional<Instant> tradedBefore() {
        return Optional.ofNullable(endDate).map(d -> d.plusDays(1).atStartOfDay(MarketTime.ZONE).toInstant());
    }
}
