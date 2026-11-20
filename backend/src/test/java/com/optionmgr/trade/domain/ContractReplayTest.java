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

    // ---------------------------------------------------------------- FEAT-051 stored selections (Q255)

    private long recordClose(String nyTime, int contracts, String cash, Long... lots) {
        long id = nextId++;
        trades.add(trade(id, nyTime, TradeAction.BTC, contracts, 100, cash, "0.00").withLots(List.of(lots)));
        return id;
    }

    private ContractHistory closeLots(String nyTime, int contracts, String cash, Long... lots) {
        return with(trade(NEW, nyTime, TradeAction.BTC, contracts, LotTrade.INHERIT_MULTIPLIER, cash, "0.00")
                .withLots(List.of(lots)));
    }

    @Test
    void selectedLaterLotIsClosedAndTheEarlierStaysOpen() {
        long a = record("2026-11-10T10:00", TradeAction.STO, 1, 100, "18.34", "0.00");
        long b = record("2026-11-11T10:00", TradeAction.STO, 1, 100, "37.34", "0.00");
        ContractHistory h = closeLots("2026-11-19T10:00", 1, "-15.10", b);
        ReplayedTrade s = h.step(NEW);
        assertThat(s.isValid()).isTrue();
        assertThat(s.match().orElseThrow().coveredLots()).extracting(PositionLot::tradeId).containsExactly(b);
        assertThat(s.match().orElseThrow().realizedPnl()).hasToString("22.24");
        assertThat(h.openLots()).extracting(PositionLot::tradeId).containsExactly(a);
        assertThat(s.lotsBefore()).extracting(PositionLot::tradeId).containsExactly(a, b);
    }

    @Test
    void nonContiguousSelectionReleasesItsLotsFrozen() {
        long p1 = record("2026-11-10T10:00", TradeAction.STO, 2, 100, "30.00", "1600.00");
        long p2 = record("2026-11-11T10:00", TradeAction.STO, 1, 100, "21.00", "800.00");
        long p3 = record("2026-11-12T10:00", TradeAction.STO, 1, 100, "10.00", "800.00");
        CloseMatch m = closeLots("2026-11-19T10:00", 3, "-20.00", p3, p1).step(NEW).match().orElseThrow();
        assertThat(m.coveredLots()).extracting(PositionLot::tradeId).containsExactly(p1, p3);
        assertThat(m.releasedFrozen()).hasToString("2400.00");
        assertThat(m.realizedPnl()).hasToString("20.00");
        assertThat(closeLots("2026-11-19T10:00", 3, "-20.00", p3, p1).openLots()).extracting(PositionLot::tradeId)
                .containsExactly(p2);
    }

    @Test
    void selectionProblems_notYetOpen_alreadyClosed_contractsMismatch() {
        long a = record("2026-11-10T10:00", TradeAction.STO, 1, 100, "18.34", "0.00");
        long b = record("2026-11-11T10:00", TradeAction.STO, 1, 100, "37.34", "0.00");
        long c1 = recordClose("2026-11-19T10:00", 1, "-15.10", b);

        ReplayedTrade early = closeLots("2026-11-10T15:00", 1, "-1.00", b).step(NEW);
        assertThat(early.violations()).containsExactly(ViolationKind.CLOSE_LOTS_INVALID);
        assertThat(early.lotProblems()).extracting(LotProblem::reason).containsExactly(LotProblem.Reason.NOT_YET_OPEN);
        assertThat(TradeRejectionMessages.selectedLotProblem(early.lotProblems().get(0), early.trade()))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓");

        ReplayedTrade again = closeLots("2026-11-20T09:00", 1, "-1.00", b).step(NEW);
        assertThat(again.lotProblems().get(0).closedBy().tradeId()).isEqualTo(c1);
        assertThat(TradeRejectionMessages.selectedLotProblem(again.lotProblems().get(0), again.trade()))
                .isEqualTo("所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉");

        ReplayedTrade size = closeLots("2026-11-20T09:00", 2, "-1.00", a).step(NEW);
        assertThat(size.lotProblems()).extracting(LotProblem::reason)
                .containsExactly(LotProblem.Reason.CONTRACTS_MISMATCH);
        assertThat(TradeRejectionMessages.selectedLotProblem(size.lotProblems().get(0), size.trade()))
                .isEqualTo("平仓张数 2 张与所选批次张数之和 1 张不一致");
    }

    @Test
    void backfilledCloseNoLongerRematchesAStoredSelection() {
        long l1 = record("2026-11-03T10:30", TradeAction.STO, 1, 100, "349.35", "20000.00");
        long l2 = record("2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00");
        long later = recordClose("2026-11-06T10:00", 1, "-100.00", l1);

        // FIFO back-fill takes L1: the later close's stored lot is then already closed
        ContractHistory fifo = close("2026-11-05T10:00", TradeAction.BTC, 1, "-120.00");
        ReplayedTrade v = fifo.firstViolationFrom(NEW, k -> true).orElseThrow();
        assertThat(v.trade().tradeId()).isEqualTo(later);
        assertThat(v.violations()).containsExactly(ViolationKind.CLOSE_LOTS_INVALID);
        ContractKey zzq = new ContractKey("ZZQ", java.time.LocalDate.parse("2026-12-18"), new BigDecimal("200"),
                OptionType.PUT);
        assertThat(TradeRejectionMessages.of(zzq, v, ViolationKind.CLOSE_LOTS_INVALID, false))
                .isEqualTo("补录后，2026-11-06 10:00 BTC 1 张 ZZQ 2026-12-18 200 P 选中的批次 2026-11-03 10:30 STO 1 张 "
                        + "已被更早的平仓平掉");

        // selecting L2 keeps the later close on L1
        ContractHistory ok = closeLots("2026-11-05T10:00", 1, "-120.00", l2);
        assertThat(ok.firstViolationFrom(NEW, k -> true)).isEmpty();
        assertThat(ok.step(later).match().orElseThrow().coveredLots()).extracting(PositionLot::tradeId)
                .containsExactly(l1);
        assertThat(ok.step(later).match().orElseThrow().realizedPnl()).hasToString("249.35");
    }

    @Test
    void backfilledEarlierOpenStaysARemainingLot() {
        long l1 = record("2026-11-03T10:30", TradeAction.STO, 2, 100, "698.70", "40000.00");
        long l2 = record("2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00");
        long c = recordClose("2026-11-05T10:00", 2, "-241.30", l1);
        ContractHistory h = with(trade(NEW, "2026-11-02T10:00", TradeAction.STO, 1, 100, "100.00", "20000.00"));
        assertThat(h.firstViolationFrom(NEW, k -> true)).isEmpty();
        assertThat(h.step(c).match().orElseThrow().realizedPnl()).hasToString("457.40");
        assertThat(h.openLots()).extracting(PositionLot::tradeId).containsExactly(NEW, l2);
    }

    @Test
    void expiryAfterASelectedCloseTakesTheRemainingLots() {
        long a = record("2026-11-10T10:00", TradeAction.STO, 1, 100, "18.34", "0.00");
        long b = record("2026-11-11T10:00", TradeAction.STO, 1, 100, "37.34", "0.00");
        recordClose("2026-11-19T10:00", 1, "-15.10", b);
        ContractHistory h = close("2026-11-20T16:00", TradeAction.ASG, 1, "0.00");
        assertThat(h.step(NEW).match().orElseThrow().coveredLots()).extracting(PositionLot::tradeId)
                .containsExactly(a);
    }

    // ---------------------------------------------------------------- FIFO replay without a stored selection
    // (a FIFO-mode new close, Q257, and EXP / ASG / EXR, Q260; before FEAT-051 every close - Q39 re-matching)

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
