package com.optionmgr.stock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-011 FIFO replay: partial sales (Q69, Q87), lots across a sale, transfer-in at 00:00 NY (Q86), Q15. */
class StockReplayTest {

    private static StockRecord rec(long id, StockTradeType type, String instant, int shares, String amount) {
        return new StockRecord(id, type, Instant.parse(instant), shares, new BigDecimal(amount), null);
    }

    @Test
    void q69_partialSaleTakesProRataCostRoundedHalfUpAndKeepsTheRest() {
        StockReplay.History h = StockReplay.replay(List.of(
                rec(1, StockTradeType.BUY, "2026-11-04T15:00:00Z", 300, "1000.00"),
                rec(2, StockTradeType.SELL, "2026-11-06T15:00:00Z", 100, "400.00")));
        SaleMatch sale = h.step(2).orElseThrow().sale().orElseThrow();
        assertThat(sale.soldCost()).isEqualByComparingTo("333.33");
        assertThat(sale.realizedPnl()).isEqualByComparingTo("66.67");
        assertThat(h.openLots()).singleElement().satisfies(l -> {
            assertThat(l.remainingShares()).isEqualTo(200);
            assertThat(l.remainingCost()).isEqualByComparingTo("666.67");
            assertThat(l.originalShares()).isEqualTo(300);
            assertThat(l.originalCost()).isEqualByComparingTo("1000.00");
        });
    }

    @Test
    void q87_repeatedPartialSalesUseTheRemainderAndTheLastTakesWhatIsLeft() {
        List<StockRecord> records = new ArrayList<>();
        records.add(rec(1, StockTradeType.BUY, "2026-11-04T15:00:00Z", 300, "1000.00"));
        records.add(rec(2, StockTradeType.SELL, "2026-11-06T15:00:00Z", 100, "400.00"));
        records.add(rec(3, StockTradeType.SELL, "2026-11-09T15:00:00Z", 100, "420.00"));
        records.add(rec(4, StockTradeType.SELL, "2026-11-10T15:00:00Z", 100, "300.00"));
        StockReplay.History h = StockReplay.replay(records);
        assertThat(h.step(2).orElseThrow().sale().orElseThrow().soldCost()).isEqualByComparingTo("333.33");
        SaleMatch second = h.step(3).orElseThrow().sale().orElseThrow();
        assertThat(second.soldCost()).isEqualByComparingTo("333.34");
        assertThat(second.realizedPnl()).isEqualByComparingTo("86.66");
        SaleMatch third = h.step(4).orElseThrow().sale().orElseThrow();
        assertThat(third.soldCost()).isEqualByComparingTo("333.33");
        assertThat(third.realizedPnl()).isEqualByComparingTo("-33.33");
        assertThat(h.openLots()).isEmpty();
        assertThat(h.shares()).isZero();
    }

    @Test
    void saleAcrossLotsTakesWholeEarlierLotThenPartOfTheNext() {
        Instant transfer = StockTimes.transferInstant(LocalDate.of(2026, 10, 1));
        StockReplay.History h = StockReplay.replay(List.of(
                new StockRecord(1, StockTradeType.TRANSFER_IN, transfer, 100, new BigDecimal("750.00"), null),
                rec(2, StockTradeType.BUY, "2026-11-03T15:00:00Z", 200, "1500.65"),
                rec(4, StockTradeType.SELL, "2026-11-05T15:00:00Z", 150, "1200.00")));
        SaleMatch sale = h.step(4).orElseThrow().sale().orElseThrow();
        assertThat(sale.lots()).extracting(LotSale::sourceId).containsExactly(1L, 2L);
        assertThat(sale.lots()).extracting(LotSale::shares).containsExactly(100, 50);
        assertThat(sale.lots().get(0).cost()).isEqualByComparingTo("750.00");
        assertThat(sale.lots().get(1).cost()).isEqualByComparingTo("375.16");
        assertThat(sale.soldCost()).isEqualByComparingTo("1125.16");
        assertThat(sale.realizedPnl()).isEqualByComparingTo("74.84");
        assertThat(h.openLots()).singleElement().satisfies(l -> {
            assertThat(l.sourceId()).isEqualTo(2);
            assertThat(l.remainingShares()).isEqualTo(150);
            assertThat(l.remainingCost()).isEqualByComparingTo("1125.49");
        });
    }

    @Test
    void q86_transferInIsAtMidnightNewYorkWithDst() {
        assertThat(StockTimes.transferInstant(LocalDate.of(2026, 10, 1))).isEqualTo("2026-10-01T04:00:00Z");
        assertThat(StockTimes.transferInstant(LocalDate.of(2026, 12, 1))).isEqualTo("2026-12-01T05:00:00Z");
        assertThat(StockTimes.marketDate(Instant.parse("2026-10-01T04:00:00Z"))).isEqualTo("2026-10-01");
    }

    @Test
    void q86_transferInOnTheSameDayIsAvailableToAnEarlierEnteredMorningSale() {
        // the sale was entered first (lower id) but the transfer is at 00:00, before 09:30
        StockReplay.History h = StockReplay.replay(List.of(
                rec(1, StockTradeType.SELL, "2026-11-10T14:30:00Z", 100, "550.00"),
                new StockRecord(2, StockTradeType.TRANSFER_IN, StockTimes.transferInstant(LocalDate.of(2026, 11, 10)),
                        100, new BigDecimal("500.00"), null)));
        assertThat(h.firstViolation()).isEmpty();
        assertThat(h.step(1).orElseThrow().sale().orElseThrow().realizedPnl()).isEqualByComparingTo("50.00");
    }

    @Test
    void q15_saleOverHoldingIsAViolationAndIsNotApplied() {
        StockReplay.History h = StockReplay.replay(List.of(
                rec(1, StockTradeType.BUY, "2026-11-03T15:00:00Z", 100, "500.00"),
                rec(2, StockTradeType.SELL, "2026-11-04T15:00:00Z", 101, "600.00"),
                rec(3, StockTradeType.SELL, "2026-11-05T15:00:00Z", 100, "600.00")));
        StockReplay.Step v = h.firstViolation().orElseThrow();
        assertThat(v.record().id()).isEqualTo(2);
        assertThat(v.holdingBefore()).isEqualTo(100);
        assertThat(h.step(3).orElseThrow().violated()).isFalse();
        assertThat(StockMessages.saleExceedsHolding("IXQZ", v)).isEqualTo("持股不足：2026-11-04 10:00 卖出 IXQZ 101 股，当时持有 100 股");
        assertThat(StockMessages.laterSaleExceedsHolding("补录", "IXQZ", v))
                .isEqualTo("补录后 2026-11-04 10:00 的卖出 IXQZ 101 股将超过当时持股 100 股");
    }

    @Test
    void sameInstantFollowsEntryOrderAndAPendingRecordSortsLast() {
        StockReplay.History h = StockReplay.replay(List.of(
                rec(StockRecord.NOT_YET_ENTERED, StockTradeType.SELL, "2026-11-10T15:00:00Z", 100, "520.00"),
                rec(5, StockTradeType.BUY, "2026-11-10T15:00:00Z", 100, "500.00")));
        assertThat(h.firstViolation()).isEmpty();
        StockReplay.History reversed = StockReplay.replay(List.of(
                rec(5, StockTradeType.SELL, "2026-11-10T15:00:00Z", 100, "520.00"),
                rec(6, StockTradeType.BUY, "2026-11-10T15:00:00Z", 100, "500.00")));
        assertThat(reversed.firstViolation().orElseThrow().record().id()).isEqualTo(5);
    }

    @Test
    void q88_perShareAndAverageHaveFourDecimalsHalfUp() {
        assertThat(StockPosition.perShare(new BigDecimal("1500.65"), 200)).isEqualTo(new BigDecimal("7.5033"));
        assertThat(StockPosition.perShare(new BigDecimal("666.67"), 200)).isEqualTo(new BigDecimal("3.3334"));
        assertThat(StockPosition.perShare(new BigDecimal("1000.00"), 300)).isEqualTo(new BigDecimal("3.3333"));
        StockPosition p = new StockPosition("IXQZ", List.of(
                new StockLot(2, StockTradeType.BUY, Instant.parse("2026-11-03T15:00:00Z"), 200,
                        new BigDecimal("1500.65"), 50, new BigDecimal("375.16"), null),
                new StockLot(1, StockTradeType.TRANSFER_IN, Instant.parse("2026-11-04T05:00:00Z"), 100,
                        new BigDecimal("750.00"), 100, new BigDecimal("750.00"), null)));
        assertThat(p.shares()).isEqualTo(150);
        assertThat(p.totalCost()).isEqualTo(new BigDecimal("1125.16"));
        assertThat(p.averageCost()).isEqualTo(new BigDecimal("7.5011"));
    }
}
