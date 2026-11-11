package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One trade of a contract as input for building its position.
 *
 * @param tradeId      trade id (entry order)
 * @param tradedAt     trade time (UTC)
 * @param action       BTO / STO (FEAT-001); BTC / STC (FEAT-004)
 * @param contracts    positive number of contracts
 * @param multiplier   contract multiplier
 * @param cashChange   signed cash effect as recorded in the ledger (sells +, buys -), scale 2
 * @param frozenChange frozen-cash effect as recorded in the ledger (STO put: + collateral), scale 2
 * @param note         user note, may be null
 */
public record LotTrade(long tradeId, Instant tradedAt, TradeAction action, int contracts, int multiplier,
                       BigDecimal cashChange, BigDecimal frozenChange, String note) {

    /** FIFO / replay order: by time, then entry order. */
    public static final Comparator<LotTrade> FIFO_ORDER =
            Comparator.comparing(LotTrade::tradedAt).thenComparingLong(LotTrade::tradeId);

    public LotTrade {
        Objects.requireNonNull(tradedAt, "tradedAt");
        Objects.requireNonNull(action, "action");
        cashChange = Money.of(Objects.requireNonNull(cashChange, "cashChange"));
        frozenChange = Money.of(Objects.requireNonNull(frozenChange, "frozenChange"));
    }
}
