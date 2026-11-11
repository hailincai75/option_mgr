package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * FEAT-002 trade list filter (Q30-Q33). Every criterion is optional (null / empty = not filtered); criteria are
 * combined with AND, the selected actions with OR.
 *
 * @param symbol         normalized symbol, exact match (Q31)
 * @param expirationDate exact expiration date
 * @param strike         strike, compared by value (normalized without trailing zeros)
 * @param optionType     call or put
 * @param actions        selected actions; empty = all (Q32)
 * @param startDate      New York trade date &gt;= startDate (Q33)
 * @param endDate        New York trade date &lt;= endDate (Q33, whole day included)
 */
public record TradeFilter(String symbol, LocalDate expirationDate, BigDecimal strike, OptionType optionType,
                          Set<TradeAction> actions, LocalDate startDate, LocalDate endDate) {

    public static final TradeFilter NONE = new TradeFilter(null, null, null, null, Set.of(), null, null);

    public TradeFilter {
        strike = strike == null ? null : ContractKey.normalizeStrike(strike);
        actions = actions == null || actions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(actions));
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate after endDate");
        }
    }

    /** Inclusive lower bound of tradedAt: 00:00 New York on startDate. */
    public Optional<Instant> tradedFrom() {
        return Optional.ofNullable(startDate).map(d -> d.atStartOfDay(MarketTime.ZONE).toInstant());
    }

    /** Exclusive upper bound of tradedAt: 00:00 New York on the day after endDate (handles 23/25-hour days). */
    public Optional<Instant> tradedBefore() {
        return Optional.ofNullable(endDate).map(d -> d.plusDays(1).atStartOfDay(MarketTime.ZONE).toInstant());
    }
}
