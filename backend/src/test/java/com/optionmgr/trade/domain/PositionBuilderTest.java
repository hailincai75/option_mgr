package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-003 position building from one contract's trades, independent of Spring. */
class PositionBuilderTest {

    private static final LocalDate EXP = LocalDate.parse("2026-12-18");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-11");

    private static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    private static ContractKey contract(String symbol, LocalDate exp, String strike, OptionType type) {
        return new ContractKey(symbol, exp, new BigDecimal(strike), type);
    }

    private static LotTrade lot(long id, String nyTime, TradeAction action, int contracts, int multiplier,
                                String cash, String frozen) {
        return new LotTrade(id, ny(nyTime), action, contracts, multiplier, new BigDecimal(cash),
                new BigDecimal(frozen), null);
    }

    private static OptionPosition build(ContractKey c, LotTrade... trades) {
        return PositionBuilder.build(c, List.of(trades)).orElseThrow();
    }

    @Test
    void twoShortPutLotsInFifoOrder() {
        // entered T4 first in the list, but FIFO is by trade time then id
        OptionPosition p = build(contract("AAPL", EXP, "200", OptionType.PUT),
                lot(4, "2026-11-04T11:00", TradeAction.STO, 1, 100, "360.00", "20000.00"),
                lot(1, "2026-11-03T10:30", TradeAction.STO, 1, 100, "349.35", "20000.00"));

        assertThat(p.direction()).isEqualTo(PositionDirection.SHORT);
        assertThat(p.contracts()).isEqualTo(2);
        assertThat(p.multiplier()).isEqualTo(100);
        assertThat(p.openingNetTotal()).hasToString("709.35");
        assertThat(p.averageNetPerContract()).hasToString("354.68");
        assertThat(p.frozenCash()).hasToString("40000.00");
        assertThat(p.lots()).extracting(PositionLot::tradeId).containsExactly(1L, 4L);
        assertThat(p.lots().get(0).remainingContracts()).isEqualTo(p.lots().get(0).originalContracts());
        assertThat(p.dte(TODAY)).isEqualTo(37);
        assertThat(p.expirationStatus(TODAY)).isEqualTo(ExpirationStatus.ACTIVE);
    }

    @Test
    void sameInstantLotsKeepEntryOrder() {
        OptionPosition p = build(contract("AAPL", EXP, "200", OptionType.PUT),
                lot(7, "2026-11-03T10:30", TradeAction.STO, 1, 100, "1.00", "20000.00"),
                lot(3, "2026-11-03T10:30", TradeAction.STO, 1, 100, "1.00", "20000.00"));
        assertThat(p.lots()).extracting(PositionLot::tradeId).containsExactly(3L, 7L);
    }

    @Test
    void longPositionHasNegativeNetAndNoFrozen() {
        OptionPosition p = build(contract("KO", EXP, "60", OptionType.CALL),
                lot(1, "2026-11-02T10:00", TradeAction.BTO, 3, 100, "-200.00", "0.00"));
        assertThat(p.direction()).isEqualTo(PositionDirection.LONG);
        assertThat(p.openingNetTotal()).hasToString("-200.00");
        assertThat(p.averageNetPerContract()).hasToString("-66.67");
        assertThat(p.frozenCash()).hasToString("0.00");
        assertThat(build(contract("PEP", EXP, "150", OptionType.CALL),
                lot(2, "2026-11-02T10:00", TradeAction.BTO, 3, 100, "-100.00", "0.00"))
                .averageNetPerContract()).hasToString("-33.33");
    }

    @Test
    void frozenIsTheSumOfLotsNotRecomputed() {
        OptionPosition p = build(contract("F", EXP, "12.345", OptionType.PUT),
                lot(1, "2026-11-02T10:00", TradeAction.STO, 1, 1, "0.50", "12.35"),
                lot(2, "2026-11-03T10:00", TradeAction.STO, 1, 1, "0.50", "12.35"));
        assertThat(p.frozenCash()).hasToString("24.70");
        assertThat(p.openingNetTotal()).hasToString("1.00");
        assertThat(p.averageNetPerContract()).hasToString("0.50");
    }

    @Test
    void dteAndExpirationStatus() {
        OptionPosition expired = build(contract("TSLA", LocalDate.parse("2026-11-06"), "250", OptionType.PUT),
                lot(1, "2026-11-02T10:00", TradeAction.STO, 1, 100, "300.00", "25000.00"));
        assertThat(expired.dte(TODAY)).isEqualTo(-5);
        assertThat(expired.expirationStatus(TODAY)).isEqualTo(ExpirationStatus.EXPIRED);
        assertThat(expired.frozenCash()).hasToString("25000.00");

        OptionPosition today = build(contract("SPY", TODAY, "600", OptionType.CALL),
                lot(2, "2026-11-02T10:00", TradeAction.BTO, 1, 100, "-50.00", "0.00"));
        assertThat(today.dte(TODAY)).isZero();
        assertThat(today.expirationStatus(TODAY)).isEqualTo(ExpirationStatus.EXPIRES_TODAY);
    }

    @Test
    void displayOrder() {
        List<OptionPosition> ps = new ArrayList<>(List.of(
                build(contract("MSFT", LocalDate.parse("2027-01-15"), "450", OptionType.CALL),
                        lot(5, "2026-11-03T23:30", TradeAction.BTO, 1, 100, "-900.00", "0.00")),
                build(contract("XYZ", EXP, "200", OptionType.PUT),
                        lot(6, "2026-10-30T10:00", TradeAction.STO, 1, 10, "34.35", "2000.00")),
                build(contract("AAPL", EXP, "210", OptionType.CALL),
                        lot(2, "2026-11-03T10:30", TradeAction.BTO, 1, 100, "-512.65", "0.00")),
                build(contract("AAPL", EXP, "200", OptionType.PUT),
                        lot(1, "2026-11-03T10:30", TradeAction.STO, 1, 100, "349.35", "20000.00")),
                build(contract("AAPL", EXP, "200", OptionType.CALL),
                        lot(9, "2026-11-03T10:30", TradeAction.BTO, 1, 100, "-1.00", "0.00")),
                build(contract("F", EXP, "12.5", OptionType.PUT),
                        lot(3, "2026-11-02T15:59", TradeAction.STO, 2, 100, "48.70", "2500.00"))));
        ps.sort(OptionPosition.DISPLAY_ORDER);
        assertThat(ps).extracting(p -> p.contract().label()).containsExactly(
                "AAPL 2026-12-18 200 C", "AAPL 2026-12-18 200 P", "AAPL 2026-12-18 210 C",
                "F 2026-12-18 12.5 P", "XYZ 2026-12-18 200 P", "MSFT 2027-01-15 450 C");
    }

    @Test
    void strikeOrderIsNumeric() {
        List<OptionPosition> ps = new ArrayList<>(List.of(
                build(contract("A", EXP, "100", OptionType.PUT),
                        lot(1, "2026-11-02T10:00", TradeAction.BTO, 1, 100, "-1.00", "0.00")),
                build(contract("A", EXP, "12.5", OptionType.PUT),
                        lot(2, "2026-11-02T10:00", TradeAction.BTO, 1, 100, "-1.00", "0.00"))));
        ps.sort(OptionPosition.DISPLAY_ORDER);
        assertThat(ps).extracting(p -> p.contract().label()).containsExactly("A 2026-12-18 12.5 P", "A 2026-12-18 100 P");
    }

    @Test
    void noTradesNoPositionAndClosesAreNotYetSupported() {
        assertThat(PositionBuilder.build(contract("A", EXP, "1", OptionType.PUT), List.of())).isEmpty();
        assertThatThrownBy(() -> build(contract("A", EXP, "1", OptionType.PUT),
                lot(1, "2026-11-02T10:00", TradeAction.STO, 1, 100, "1.00", "100.00"),
                lot(2, "2026-11-03T10:00", TradeAction.BTC, 1, 100, "-1.00", "-100.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
