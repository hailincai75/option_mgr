package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One trade of a contract as input for the contract replay (positions, FIFO lots, close matching).
 *
 * @param tradeId      trade id (entry order); {@link #NOT_YET_ENTERED} for a trade about to be recorded
 * @param tradedAt     trade time (UTC)
 * @param action       BTO / STO / BTC / STC
 * @param contracts    positive number of contracts
 * @param multiplier   contract multiplier; {@link #INHERIT_MULTIPLIER} for a new close (takes the position's)
 * @param cashChange   signed cash effect (sells +, buys -), scale 2
 * @param frozenChange for opens: frozen cash of the lot (STO put collateral), scale 2; ignored for closes
 *                     (their release is derived from the matched lots)
 * @param note         user note, may be null
 */
public record LotTrade(long tradeId, Instant tradedAt, TradeAction action, int contracts, int multiplier,
                       BigDecimal cashChange, BigDecimal frozenChange, String note) {

    /** Sequence of a trade not stored yet: entered last, so it sorts after existing trades at the same instant. */
    public static final long NOT_YET_ENTERED = Long.MAX_VALUE;

    /** Multiplier of a new close: the position's multiplier at its time point. */
    public static final int INHERIT_MULTIPLIER = 0;

    /** FIFO / replay order: by time, then entry order. */
    public static final Comparator<LotTrade> FIFO_ORDER =
            Comparator.comparing(LotTrade::tradedAt).thenComparingLong(LotTrade::tradeId);

    public LotTrade {
        Objects.requireNonNull(tradedAt, "tradedAt");
        Objects.requireNonNull(action, "action");
        cashChange = Money.of(Objects.requireNonNull(cashChange, "cashChange"));
        frozenChange = Money.of(Objects.requireNonNull(frozenChange, "frozenChange"));
        if (contracts <= 0 || multiplier < 0) {
            throw new IllegalArgumentException("contracts must be positive, multiplier not negative");
        }
    }
}
