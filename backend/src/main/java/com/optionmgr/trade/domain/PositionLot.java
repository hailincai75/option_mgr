package com.optionmgr.trade.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One FIFO lot = one opening trade, with its original and remaining values. FEAT-004 closes reduce the remaining
 * values of the earliest lots first; until then remaining = original. Amounts scale 2; net amounts carry the cash
 * sign (short +, long -), frozen amounts are non-negative.
 */
public record PositionLot(long tradeId, TradeAction action, Instant tradedAt, int multiplier,
                          int originalContracts, int remainingContracts,
                          BigDecimal originalNetAmount, BigDecimal remainingNetAmount,
                          BigDecimal originalFrozen, BigDecimal remainingFrozen, String note) {

    /** A freshly opened lot: nothing closed yet. */
    public static PositionLot opened(LotTrade t) {
        return new PositionLot(t.tradeId(), t.action(), t.tradedAt(), t.multiplier(), t.contracts(), t.contracts(),
                t.cashChange(), t.cashChange(), t.frozenChange(), t.frozenChange(), t.note());
    }
}
