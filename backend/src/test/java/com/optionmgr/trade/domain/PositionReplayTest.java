package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q7 / Q23 position replay of one contract, independent of Spring. Times are New York wall-clock times. */
class PositionReplayTest {

    private final List<PositionEvent> trades = new ArrayList<>();
    private long nextId = 1;

    private static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    private void record(String nyTime, TradeAction action, int contracts, int multiplier) {
        trades.add(new PositionEvent(ny(nyTime), nextId++, action, contracts, multiplier));
    }

    private PositionInsertionCheck insert(String nyTime, TradeAction action, int contracts, int multiplier) {
        return PositionReplay.checkInsertion(trades, PositionEvent.pending(ny(nyTime), action, contracts, multiplier));
    }

    @Test
    void emptyContractAcceptsAnyOpen() {
        PositionInsertionCheck c = insert("2026-11-10T10:30", TradeAction.STO, 1, 100);
        assertThat(c.inserted().positionBefore()).isZero();
        assertThat(c.directionConflict()).isEmpty();
        assertThat(c.multiplierMismatch()).isEmpty();
    }

    @Test
    void sameDirectionAddsUp() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100);
        record("2026-11-10T11:00", TradeAction.STO, 2, 100);
        PositionInsertionCheck c = insert("2026-11-11T11:00", TradeAction.STO, 1, 100);
        assertThat(c.inserted().positionBefore()).isEqualTo(-3);
        assertThat(c.inserted().positionMultiplier()).isEqualTo(100);
        assertThat(c.directionConflict()).isEmpty();
    }

    @Test
    void buyToOpenAgainstShortIsAConflictAtTheTradePoint() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100);
        PositionInsertionCheck c = insert("2026-11-11T10:00", TradeAction.BTO, 1, 100);
        assertThat(c.directionConflict()).containsSame(c.inserted());
        assertThat(c.inserted().positionBefore()).isEqualTo(-1);
    }

    @Test
    void sellToOpenAgainstLongIsAConflict() {
        record("2026-11-10T10:30", TradeAction.BTO, 2, 100);
        PositionInsertionCheck c = insert("2026-11-11T10:00", TradeAction.STO, 1, 100);
        assertThat(c.directionConflict()).containsSame(c.inserted());
        assertThat(c.inserted().positionBefore()).isEqualTo(2);
    }

    @Test
    void positionIsTakenAtTheTradeTimePoint() {
        // A short opened later does not exist yet at an earlier time point ...
        record("2026-11-10T10:30", TradeAction.STO, 1, 100);
        PositionInsertionCheck c = insert("2026-11-09T10:00", TradeAction.STO, 1, 100);
        assertThat(c.inserted().positionBefore()).isZero();
        assertThat(c.directionConflict()).isEmpty();
    }

    @Test
    void backfilledOpenMustNotMakeALaterOpenConflict() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100);
        // ... but a BTO before it would leave that later STO opening against a long position (Q7: any moment).
        PositionInsertionCheck c = insert("2026-11-09T10:00", TradeAction.BTO, 1, 100);
        assertThat(c.directionConflict()).isPresent();
        PositionStep conflict = c.directionConflict().orElseThrow();
        assertThat(c.isInserted(conflict)).isFalse();
        assertThat(conflict.event().sequence()).isEqualTo(1);
        assertThat(conflict.positionBefore()).isEqualTo(1);
    }

    @Test
    void sameInstantExistingTradesComeFirst() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100);
        PositionInsertionCheck c = insert("2026-11-10T10:30", TradeAction.BTO, 1, 100);
        assertThat(c.inserted().positionBefore()).isEqualTo(-1);
        assertThat(c.directionConflict()).containsSame(c.inserted());
    }

    @Test
    void multiplierMustMatchTheOpenPosition() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 10);
        PositionInsertionCheck c = insert("2026-11-11T10:00", TradeAction.STO, 1, 100);
        assertThat(c.directionConflict()).isEmpty();
        assertThat(c.multiplierMismatch()).containsSame(c.inserted());
        assertThat(c.inserted().positionMultiplier()).isEqualTo(10);
    }

    @Test
    void backfilledMultiplierMustNotMismatchALaterTrade() {
        record("2026-11-11T11:00", TradeAction.STO, 1, 10);
        PositionInsertionCheck c = insert("2026-11-10T10:30", TradeAction.STO, 1, 100);
        PositionStep mismatch = c.multiplierMismatch().orElseThrow();
        assertThat(c.isInserted(mismatch)).isFalse();
        assertThat(mismatch.event().multiplier()).isEqualTo(10);
        assertThat(mismatch.positionMultiplier()).isEqualTo(100);
    }

    @Test
    void closesReducePositionAndFlatResetsMultiplier() {
        // FEAT-004 readiness: a close brings the position back to 0, after which any direction/multiplier is fine.
        record("2026-11-10T10:30", TradeAction.STO, 1, 10);
        record("2026-11-10T11:00", TradeAction.BTC, 1, 10);
        PositionInsertionCheck c = insert("2026-11-11T10:00", TradeAction.BTO, 1, 100);
        assertThat(c.inserted().positionBefore()).isZero();
        assertThat(c.directionConflict()).isEmpty();
        assertThat(c.multiplierMismatch()).isEmpty();
    }
}
