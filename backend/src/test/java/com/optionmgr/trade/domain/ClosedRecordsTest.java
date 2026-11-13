package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.stock.domain.LotSale;
import com.optionmgr.stock.domain.SaleMatch;
import com.optionmgr.stock.domain.StockTradeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** FEAT-040 domain: closed-record order (Q92), option-only filters (Q90), stock sale figures (Q88), split (Q91). */
class ClosedRecordsTest {

    private static final Instant T = Instant.parse("2026-11-11T15:00:00Z");

    // ---------------------------------------------------------------- order (Q92)

    @Test
    void newestFirst_sameInstantStockAboveOption_thenLaterEntryFirst() {
        List<ClosedRecordKey> keys = new ArrayList<>(List.of(
                new ClosedRecordKey(ClosedRecordType.OPTION, 9, T),
                new ClosedRecordKey(ClosedRecordType.STOCK, 2, T.minusSeconds(60)),
                new ClosedRecordKey(ClosedRecordType.STOCK, 3, T),
                new ClosedRecordKey(ClosedRecordType.OPTION, 10, T),
                new ClosedRecordKey(ClosedRecordType.STOCK, 4, T),
                new ClosedRecordKey(ClosedRecordType.OPTION, 1, T.plusSeconds(60))));
        keys.sort(ClosedRecordKey.NEWEST_FIRST);
        assertThat(keys).extracting(k -> k.type().name().charAt(0) + "" + k.id())
                .containsExactly("O1", "S4", "S3", "O10", "O9", "S2");
    }

    // ---------------------------------------------------------------- filter (Q90)

    private static TradeFilter options(LocalDate exp, BigDecimal strike, OptionType type, Set<TradeAction> actions) {
        return new TradeFilter("IXQZ", exp, strike, type, actions, LocalDate.of(2026, 11, 1), null);
    }

    @Test
    void typeSelectsWhichKindsAreIncluded() {
        TradeFilter none = options(null, null, null, Set.of());
        assertThat(new ClosedRecordFilter(none, null).includesOptions()).isTrue();
        assertThat(new ClosedRecordFilter(none, null).includesStocks()).isTrue();
        assertThat(new ClosedRecordFilter(none, ClosedRecordType.OPTION).includesStocks()).isFalse();
        assertThat(new ClosedRecordFilter(none, ClosedRecordType.OPTION).includesOptions()).isTrue();
        assertThat(new ClosedRecordFilter(none, ClosedRecordType.STOCK).includesOptions()).isFalse();
        assertThat(new ClosedRecordFilter(none, ClosedRecordType.STOCK).includesStocks()).isTrue();
    }

    @Test
    void anyOptionOnlyCriterionExcludesStockSales() {
        List<TradeFilter> optionOnly = List.of(
                options(LocalDate.of(2026, 11, 20), null, null, Set.of()),
                options(null, new BigDecimal("3"), null, Set.of()),
                options(null, null, OptionType.CALL, Set.of()),
                options(null, null, null, Set.of(TradeAction.BTC)));
        for (TradeFilter f : optionOnly) {
            assertThat(new ClosedRecordFilter(f, null).includesStocks()).as(f.toString()).isFalse();
            assertThat(new ClosedRecordFilter(f, null).includesOptions()).as(f.toString()).isTrue();
            // type STOCK + option-only criterion: AND = nothing (no error)
            ClosedRecordFilter stockOnly = new ClosedRecordFilter(f, ClosedRecordType.STOCK);
            assertThat(stockOnly.includesStocks() || stockOnly.includesOptions()).isFalse();
        }
    }

    @Test
    void stockSalesFilterKeepsSymbolAndDatesAndSelectsSells() {
        ClosedRecordFilter f = new ClosedRecordFilter(options(null, null, null, Set.of()), null);
        assertThat(f.stockSales().symbol()).isEqualTo("IXQZ");
        assertThat(f.stockSales().types()).containsExactly(StockTradeType.SELL);
        assertThat(f.stockSales().startDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(f.stockSales().endDate()).isNull();
    }

    // ---------------------------------------------------------------- stock sale figures

    @Test
    void stockSaleFigures_scenarioM_S6() {
        // IXQZ 150 shares for 1,200.00: 100 from the 750.00 transfer + 50 of the 1,500.65 buy (375.16)
        SaleMatch match = new SaleMatch(List.of(
                new LotSale(1, StockTradeType.TRANSFER_IN, Instant.parse("2026-10-01T04:00:00Z"), 100,
                        new BigDecimal("750.00")),
                new LotSale(4, StockTradeType.BUY, Instant.parse("2026-11-03T15:00:00Z"), 50,
                        new BigDecimal("375.16"))),
                new BigDecimal("1125.16"), new BigDecimal("74.84"));
        ClosedStockSale s = new ClosedStockSale(150, new BigDecimal("1200.00"), match);
        assertThat(s.openingNetTotal()).hasToString("-1125.16");
        assertThat(s.costPerShare()).hasToString("7.5011");
        assertThat(s.closeNetAmount()).hasToString("1200.00");
        assertThat(s.pricePerShare()).hasToString("8.0000");
        assertThat(s.realizedPnl()).hasToString("74.84");
        assertThat(s.openingNetTotal().add(s.closeNetAmount())).isEqualTo(s.realizedPnl());
        assertThat(match.lots()).extracting(ClosedStockSale::lotNetAmount)
                .extracting(BigDecimal::toString).containsExactly("-750.00", "-375.16");
    }

    @Test
    void stockSaleFigures_perShareRoundsHalfUpToFourDecimals() {
        SaleMatch match = new SaleMatch(List.of(), new BigDecimal("1175.16"), new BigDecimal("24.84"));
        ClosedStockSale s = new ClosedStockSale(150, new BigDecimal("1200.00"), match);
        assertThat(s.costPerShare()).hasToString("7.8344"); // 7.83440 (Q88)
        SaleMatch third = new SaleMatch(List.of(), new BigDecimal("333.33"), new BigDecimal("66.67"));
        assertThat(new ClosedStockSale(100, new BigDecimal("400.00"), third).costPerShare()).hasToString("3.3333");
    }

    // ---------------------------------------------------------------- summary split (Q91)

    private static PnlSummary.Entry e(ClosedRecordType type, String symbol, String closedAt, String pnl) {
        return new PnlSummary.Entry(type, symbol, Instant.parse(closedAt), new BigDecimal(pnl));
    }

    private static String g(PnlSummary.Breakdown b) {
        return b.all().closedCount() + " " + b.all().realizedPnl() + " | " + b.option().closedCount() + " "
                + b.option().realizedPnl() + " | " + b.stock().closedCount() + " " + b.stock().realizedPnl();
    }

    @Test
    void summarySplitsOptionAndStock_scenarioM() {
        PnlSummary s = PnlSummary.of(List.of(
                e(ClosedRecordType.OPTION, "IXQZ", "2026-10-30T14:00:00Z", "35.00"),
                e(ClosedRecordType.STOCK, "QRTV", "2026-11-01T03:30:00Z", "-20.00"), // NY 2026-10-31 23:30
                e(ClosedRecordType.STOCK, "IXQZ", "2026-11-05T15:00:00Z", "74.84"),
                e(ClosedRecordType.STOCK, "XYZ", "2026-11-06T15:00:00Z", "66.67"),
                e(ClosedRecordType.OPTION, "XYZ", "2026-11-09T15:00:00Z", "-15.00")));
        assertThat(g(s.total())).isEqualTo("5 141.51 | 2 20.00 | 3 121.51");
        assertThat(s.bySymbol()).extracting(r -> r.symbol() + ": " + g(r.breakdown())).containsExactly(
                "IXQZ: 2 109.84 | 1 35.00 | 1 74.84",
                "QRTV: 1 -20.00 | 0 0.00 | 1 -20.00",
                "XYZ: 2 51.67 | 1 -15.00 | 1 66.67");
        assertThat(s.byMonth()).extracting(r -> r.month() + ": " + g(r.breakdown())).containsExactly(
                "2026-11: 3 126.51 | 1 -15.00 | 2 141.51",
                "2026-10: 2 15.00 | 1 35.00 | 1 -20.00");
        assertThat(s.byMonth().get(1).month()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void emptySummaryHasZeroSplit() {
        PnlSummary s = PnlSummary.of(List.of());
        assertThat(g(s.total())).isEqualTo("0 0.00 | 0 0.00 | 0 0.00");
        assertThat(s.bySymbol()).isEmpty();
        assertThat(s.byMonth()).isEmpty();
    }
}
