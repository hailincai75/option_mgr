package com.optionmgr.cash.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One cash ledger movement as seen by the replay: when it happened, its entry order, the signed cash
 * change and the signed change of frozen cash.
 *
 * @param occurredAt   when it happened (UTC)
 * @param sequence     entry order (ledger id); breaks ties between movements at the same instant
 * @param cashChange   signed change of total cash (inflow positive)
 * @param frozenChange signed change of frozen cash (positive freezes, negative releases); zero for
 *                     deposits/withdrawals
 */
public record CashMovement(Instant occurredAt, long sequence, BigDecimal cashChange, BigDecimal frozenChange) {

    /** Replay order (Q19): by time, then by entry order. */
    public static final Comparator<CashMovement> REPLAY_ORDER =
            Comparator.comparing(CashMovement::occurredAt).thenComparingLong(CashMovement::sequence);

    /** Sequence for a movement that is not stored yet: it is entered last, so it sorts after existing ones. */
    public static final long NOT_YET_ENTERED = Long.MAX_VALUE;

    public CashMovement {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(cashChange, "cashChange");
        Objects.requireNonNull(frozenChange, "frozenChange");
    }

    /** A new movement about to be recorded (sorts after every existing movement at the same instant). */
    public static CashMovement pending(Instant occurredAt, BigDecimal cashChange, BigDecimal frozenChange) {
        return new CashMovement(occurredAt, NOT_YET_ENTERED, cashChange, frozenChange);
    }
}
