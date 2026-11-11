package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract replay: Q7 / Q23 for opens, Q21 whole-lot FIFO closes, Q39 re-matching. Times are New York times. */
class ContractReplayTest {

    private static final long NEW = LotTrade.NOT_YET_ENTERED;

    private final List<LotTrade> trades = new ArrayList<>();
    private long nextId = 1;

    private static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    private static LotTrade trade(long id, String nyTime, TradeAction action, int contracts, int multiplier,
                                  String cash, String frozen) {
        return new LotTrade(id, ny(nyTime), action, contracts, multiplier, new BigDecimal(cash),
                new BigDecimal(frozen), null);
    }

    private long record(String nyTime, TradeAction action, int contracts, int multiplier, String cash,
                        String frozen) {
        long id = nextId++;
        trades.add(trade(id, nyTime, action, contracts, multiplier, cash, frozen));
        return id;
    }

    private ContractHistory with(LotTrade candidate) {
        List<LotTrade> all = new ArrayList<>(trades);
        all.add(candidate);
        return ContractReplay.replay(all);
    }

    private ContractHistory open(String nyTime, TradeAction action, int contracts, int multiplier) {
        return with(trade(NEW, nyTime, action, contracts, multiplier, "1.00", "0.00"));
    }

    private ContractHistory close(String nyTime, TradeAction action, int contracts, String cash) {
        return with(trade(NEW, nyTime, action, contracts, LotTrade.INHERIT_MULTIPLIER, cash, "0.00"));
    }

    // ---------------------------------------------------------------- opens (FEAT-001)

    @Test
    void sameDirectionOpensAddUp() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100, "1.00", "100.00");
        record("2026-11-10T11:00", TradeAction.STO, 2, 100, "1.00", "200.00");
        ReplayedTrade s = open("2026-11-11T11:00", TradeAction.STO, 1, 100).step(NEW);
        assertThat(s.positionBefore()).isEqualTo(-3);
        assertThat(s.multiplierBefore()).isEqualTo(100);
        assertThat(s.isValid()).isTrue();
    }

    @Test
    void openAgainstPositionAtItsTimePoint() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 100, "1.00", "100.00");
        ContractHistory h = open("2026-11-11T10:00", TradeAction.BTO, 1, 100);
        assertThat(h.firstViolationFrom(NEW, k -> true)).containsSame(h.step(NEW));
        assertThat(h.step(NEW).violations()).containsExactly(ViolationKind.OPEN_AGAINST_POSITION);
        // same instant: the existing trade comes first
        assertThat(open("2026-11-10T10:30", TradeAction.BTO, 1, 100).step(NEW).violations())
                .containsExactly(ViolationKind.OPEN_AGAINST_POSITION);
        // earlier time point: no position yet, but the later STO then opens against the new long (Q27)
        ContractHistory backfill = open("2026-11-09T10:00", TradeAction.BTO, 1, 100);
        assertThat(backfill.step(NEW).isValid()).isTrue();
        ReplayedTrade later = backfill.firstViolationFrom(NEW, k -> true).orElseThrow();
        assertThat(later.trade().tradeId()).isEqualTo(1);
        assertThat(later.violations()).containsExactly(ViolationKind.OPEN_AGAINST_POSITION);
    }

    @Test
    void multiplierMustMatchOpenPosition() {
        record("2026-11-10T10:30", TradeAction.STO, 1, 10, "1.00", "10.00");
        assertThat(open("2026-11-11T10:00", TradeAction.STO, 1, 100).step(NEW).violations())
                .containsExactly(ViolationKind.MULTIPLIER_MISMATCH);
        ReplayedTrade later = open("2026-11-09T10:00", TradeAction.STO, 1, 100)
                .firstViolationFrom(NEW, k -> true).orElseThrow();
        assertThat(later.trade().tradeId()).isEqualTo(1);
        assertThat(later.effectiveMultiplier()).isEqualTo(10);
        assertThat(later.multiplierBefore()).isEqualTo(100);
    }

    // ---------------------------------------------------------------- closes (FEAT-004)

    private void scenarioA() {
        record("2026-11-03T10:30", TradeAction.STO, 2, 100, "698.70", "40000.00");   // L1
        record("2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00");   // L2
    }

    @Test
    void closeEarliestWholeLot() {
        scenarioA();
        ContractHistory h = close("2026-11-05T10:00", TradeAction.BTC, 2, "-241.30");
        ReplayedTrade s = h.step(NEW);
        assertThat(s.isValid()).isTrue();
        assertThat(s.allowedCloseContracts()).containsExactly(2, 3);
        assertThat(s.effectiveMultiplier()).isEqualTo(100);
        CloseMatch m = s.match().orElseThrow();
        assertThat(m.coveredLots()).extracting(PositionLot::tradeId).containsExactly(1L);
        assertThat(m.releasedFrozen()).hasToString("40000.00");
        assertThat(m.realizedPnl()).hasToString("457.40");
        assertThat(h.openLots()).extracting(PositionLot::tradeId).containsExactly(2L);
    }

    @Test
    void closeSeveralLotsPnlAsAWhole() {
        scenarioA();
        ContractHistory h = close("2026-11-05T10:00", TradeAction.BTC, 3, "-400.00");
        CloseMatch m = h.step(NEW).match().orElseThrow();
        assertThat(m.coveredLots()).extracting(PositionLot::tradeId).containsExactly(1L, 2L);
        assertThat(m.releasedFrozen()).hasToString("60000.00");
        assertThat(m.realizedPnl()).hasToString("658.70");
        assertThat(h.openLots()).isEmpty();
    }

    @Test
    void closeViolationsAtItsTimePoint() {
        scenarioA();
        ReplayedTrade partial = close("2026-11-05T10:00", TradeAction.BTC, 1, "-1.00").step(NEW);
        assertThat(partial.violations()).containsExactly(ViolationKind.CLOSE_NOT_WHOLE_LOTS);
        assertThat(partial.earliestLotContracts()).isEqualTo(2);
        assertThat(close("2026-11-05T10:00", TradeAction.BTC, 4, "-1.00").step(NEW).violations())
                .containsExactly(ViolationKind.CLOSE_EXCEEDS_POSITION);
        assertThat(close("2026-11-05T10:00", TradeAction.STC, 2, "1.00").step(NEW).violations())
                .containsExactly(ViolationKind.CLOSE_WRONG_DIRECTION);
        assertThat(close("2026-11-03T10:00", TradeAction.BTC, 2, "-1.00").step(NEW).violations())
                .containsExactly(ViolationKind.NO_POSITION);
    }

    @Test
    void longCallSellToClose() {
        record("2026-11-03T10:30", TradeAction.BTO, 1, 100, "-512.65", "0.00");
        CloseMatch m = close("2026-11-05T10:00", TradeAction.STC, 1, "830.00").step(NEW).match().orElseThrow();
        assertThat(m.realizedPnl()).hasToString("317.35");
        assertThat(m.releasedFrozen()).hasToString("0.00");
        assertThat(close("2026-11-05T10:00", TradeAction.BTC, 1, "-1.00").step(NEW).violations())
                .containsExactly(ViolationKind.CLOSE_WRONG_DIRECTION);
    }

    @Test
    void flatAfterCloseAllowsAnyNewPosition() {
        record("2026-11-03T10:30", TradeAction.STO, 1, 10, "1.00", "10.00");
        record("2026-11-04T10:00", TradeAction.BTC, 1, 10, "-1.00", "0.00");
        ReplayedTrade s = open("2026-11-05T10:00", TradeAction.BTO, 1, 100).step(NEW);
        assertThat(s.positionBefore()).isZero();
        assertThat(s.isValid()).isTrue();
    }

    @Test
    void sameInstantOpenEnteredFirstCanBeClosed() {
        record("2026-11-03T10:30", TradeAction.STO, 1, 100, "1.00", "100.00");
        assertThat(close("2026-11-03T10:30", TradeAction.BTC, 1, "-1.00").step(NEW).isValid()).isTrue();
    }

    // ---------------------------------------------------------------- Q39 back-filled closes

    @Test
    void backfilledCloseMakesLaterCloseInvalid() {
        record("2026-11-03T10:30", TradeAction.STO, 1, 100, "349.35", "20000.00");   // L1
        record("2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00");   // L2
        record("2026-11-06T10:00", TradeAction.BTC, 1, 100, "-100.00", "0.00");      // closes L1
        ContractHistory h = close("2026-11-05T10:00", TradeAction.BTC, 2, "-200.00");
        assertThat(h.step(NEW).isValid()).isTrue();
        ReplayedTrade later = h.firstViolationFrom(NEW, k -> true).orElseThrow();
        assertThat(later.trade().tradeId()).isEqualTo(3);
        assertThat(later.violations()).containsExactly(ViolationKind.NO_POSITION);
    }

    @Test
    void backfilledCloseRematchesLaterClose() {
        record("2026-11-03T10:30", TradeAction.STO, 1, 100, "349.35", "20000.00");   // L1
        record("2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00");   // L2
        record("2026-11-06T10:00", TradeAction.BTC, 1, 100, "-100.00", "0.00");
        ContractHistory h = close("2026-11-05T10:00", TradeAction.BTC, 1, "-120.00");
        assertThat(h.firstViolationFrom(NEW, k -> true)).isEmpty();
        assertThat(h.step(NEW).match().orElseThrow().realizedPnl()).hasToString("229.35");
        CloseMatch later = h.step(3).match().orElseThrow();
        assertThat(later.coveredLots()).extracting(PositionLot::tradeId).containsExactly(2L);
        assertThat(later.realizedPnl()).hasToString("260.00");
        assertThat(h.openLots()).isEmpty();
    }

    @Test
    void rematchCanChangeReleasedFrozenThroughRounding() {
        // strike 12.345, multiplier 1: a 2-contract lot freezes 24.69, a 1-contract lot 12.35 (Q28)
        record("2026-11-02T10:00", TradeAction.STO, 2, 1, "1.00", "24.69");   // A
        record("2026-11-03T10:00", TradeAction.STO, 1, 1, "0.50", "12.35");   // B
        record("2026-11-04T10:00", TradeAction.STO, 1, 1, "0.50", "12.35");   // C
        record("2026-11-06T10:00", TradeAction.BTC, 2, 1, "-0.20", "0.00");   // covers A today
        assertThat(ContractReplay.replay(trades).step(4).match().orElseThrow().releasedFrozen()).hasToString("24.69");

        ContractHistory h = close("2026-11-05T10:00", TradeAction.BTC, 2, "-0.20");
        assertThat(h.step(NEW).match().orElseThrow().releasedFrozen()).hasToString("24.69");
        assertThat(h.step(4).match().orElseThrow().releasedFrozen()).hasToString("24.70");
    }

    @Test
    void allowedTextFormats() {
        assertThat(TradeRejectionMessages.allowedText(List.of(1))).isEqualTo("可平 1 张");
        assertThat(TradeRejectionMessages.allowedText(List.of(2, 3))).isEqualTo("可平 2 张或 3 张");
        assertThat(TradeRejectionMessages.allowedText(List.of(1, 3, 4))).isEqualTo("可平 1 张、3 张或 4 张");
    }
}
