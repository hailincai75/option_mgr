package com.optionmgr.stock.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One stock record of a symbol as seen by the FIFO replay.
 *
 * @param id       entry order (stock_trade.id); {@link #NOT_YET_ENTERED} for a record about to be stored
 * @param type     buy / sell / transfer-in
 * @param tradedAt when it happened (UTC); a transfer-in is at 00:00 New York of its date (Q86)
 * @param shares   positive whole shares
 * @param amount   positive amount, scale 2 (paid / received / total cost)
 * @param note     user note (carried for lot display), may be null
 */
public record StockRecord(long id, StockTradeType type, Instant tradedAt, int shares, BigDecimal amount,
                          String note) {

    /** Replay order (Q19): by time, then by entry order. */
    public static final Comparator<StockRecord> REPLAY_ORDER =
            Comparator.comparing(StockRecord::tradedAt).thenComparingLong(StockRecord::id);

    /** Id of a record that is not stored yet: it is entered last, so it sorts after existing ones at its time. */
    public static final long NOT_YET_ENTERED = Long.MAX_VALUE;

    public StockRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tradedAt, "tradedAt");
        if (shares <= 0) {
            throw new IllegalArgumentException("shares must be positive");
        }
        amount = Money.of(amount);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
