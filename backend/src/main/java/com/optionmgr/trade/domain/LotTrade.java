package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
 * @param lotTradeIds  FEAT-051: for a BTC / STC, the opening trades (lots) it closes (Q255, stored selection);
 *                     null = match the earliest whole lots (FIFO: a new close without a selection, Q257, and every
 *                     EXP / ASG / EXR, Q260). Always null for an open and for EXP / ASG / EXR.
 */
public record LotTrade(long tradeId, Instant tradedAt, TradeAction action, int contracts, int multiplier,
                       BigDecimal cashChange, BigDecimal frozenChange, String note, List<Long> lotTradeIds) {

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
        lotTradeIds = lotTradeIds == null || !action.isUserClose() ? null : List.copyOf(lotTradeIds);
    }

    /** A trade without a lot selection: an open, a close matched FIFO, an EXP / ASG / EXR. */
    public LotTrade(long tradeId, Instant tradedAt, TradeAction action, int contracts, int multiplier,
                    BigDecimal cashChange, BigDecimal frozenChange, String note) {
        this(tradeId, tradedAt, action, contracts, multiplier, cashChange, frozenChange, note, null);
    }

    /** FEAT-051: the same trade closing exactly the given lots (null = FIFO). */
    public LotTrade withLots(List<Long> lots) {
        return new LotTrade(tradeId, tradedAt, action, contracts, multiplier, cashChange, frozenChange, note, lots);
    }

    /** FEAT-051: a BTC / STC with a stored / requested lot selection. */
    public boolean hasSelection() {
        return lotTradeIds != null;
    }
}
