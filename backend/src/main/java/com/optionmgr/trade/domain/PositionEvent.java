package com.optionmgr.trade.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One trade of a single contract as seen by the position replay.
 *
 * @param occurredAt trade time (UTC)
 * @param sequence   entry order (trade id); breaks ties at the same instant
 * @param action     BTO / STO (FEAT-001), BTC / STC (FEAT-004)
 * @param contracts  positive number of contracts
 * @param multiplier contract multiplier of this trade
 */
public record PositionEvent(Instant occurredAt, long sequence, TradeAction action, int contracts, int multiplier) {

    /** Replay order: by time, then entry order (same rule as the cash ledger, Q19). */
    public static final Comparator<PositionEvent> REPLAY_ORDER =
            Comparator.comparing(PositionEvent::occurredAt).thenComparingLong(PositionEvent::sequence);

    /** Sequence of a trade not stored yet: it is entered last, so it sorts after existing ones at the same instant. */
    public static final long NOT_YET_ENTERED = Long.MAX_VALUE;

    public PositionEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(action, "action");
        if (contracts <= 0 || multiplier <= 0) {
            throw new IllegalArgumentException("contracts and multiplier must be positive");
        }
    }

    public static PositionEvent pending(Instant occurredAt, TradeAction action, int contracts, int multiplier) {
        return new PositionEvent(occurredAt, NOT_YET_ENTERED, action, contracts, multiplier);
    }

    /** Signed contract change: + for buys, - for sells. */
    public int signedContracts() {
        return action.signedContracts(contracts);
    }
}
