package com.optionmgr.valuation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.valuation.domain.LotValuation.OptionLotSize;
import com.optionmgr.valuation.domain.LotValuation.StockLotSize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-048 per-lot valuation: the numbers of acceptance criteria 1–9, Q238 remainder rule, null cases. */
class LotValuationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    static final LocalDate EXP = LocalDate.of(2026, 12, 18);

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static OptionLotSize opt(int contracts, String net) {
        return new OptionLotSize(contracts, d(net));
    }

    static StockLotSize stk(long shares, String cost) {
        return new StockLotSize(shares, d(cost));
    }

    /** Values the row like FEAT-017 and then its lots, as the controller does. */
    static List<RowValuation> options(boolean longPosition, String mark, OptionLotSize... lots) {
        int contracts = 0;
        BigDecimal net = d("0.00");
        for (OptionLotSize l : lots) {
            contracts += l.remainingContracts();
            net = net.add(l.remainingNetAmount());
        }
        BigDecimal m = mark == null ? null : d(mark);
        RowValuation row = UnrealizedValuation.option(longPosition, contracts, 100, net, EXP, TODAY, m);
        List<RowValuation> out = LotValuation.optionLots(row, longPosition, 100, m, List.of(lots));
        assertSumsToRow(row, out);
        return out;
    }

    static List<RowValuation> stocks(String price, StockLotSize... lots) {
        long shares = 0;
        BigDecimal cost = d("0.00");
        for (StockLotSize l : lots) {
            shares += l.remainingShares();
            cost = cost.add(l.remainingCost());
        }
        BigDecimal p = price == null ? null : d(price);
        RowValuation row = UnrealizedValuation.stock(shares, cost, p);
        List<RowValuation> out = LotValuation.stockLots(row, p, List.of(lots));
        assertSumsToRow(row, out);
        return out;
    }

    static void assertSumsToRow(RowValuation row, List<RowValuation> lots) {
        if (row.status() != ValuationStatus.OK) {
            return;
        }
        BigDecimal mv = lots.stream().map(RowValuation::marketValue).reduce(d("0.00"), BigDecimal::add);
        BigDecimal pnl = lots.stream().map(RowValuation::unrealizedPnl).reduce(d("0.00"), BigDecimal::add);
        assertThat(mv).isEqualTo(row.marketValue());
        assertThat(pnl).isEqualTo(row.unrealizedPnl());
    }

    static void assertLot(RowValuation v, String mv, String pnl, String pct) {
        assertThat(v.status()).isEqualTo(ValuationStatus.OK);
        assertThat(v.label()).isNull();
        assertThat(v.marketValue()).hasToString(mv);
        assertThat(v.unrealizedPnl()).hasToString(pnl);
        if (pct == null) {
            assertThat(v.percent()).isNull();
        } else {
            assertThat(v.percent()).hasToString(pct);
        }
    }

    @Test
    void ac1_shortLots() {
        List<RowValuation> lots = options(false, "0.20", opt(1, "18.34"), opt(1, "37.34"));
        assertLot(lots.get(0), "-20.00", "-1.66", "-9.05");
        assertLot(lots.get(1), "-20.00", "17.34", "46.44");
    }

    @Test
    void ac2_longLotsOfDifferentSizes() {
        List<RowValuation> lots = options(true, "0.17", opt(1, "-25.65"), opt(2, "-41.30"));
        assertLot(lots.get(0), "17.00", "-8.65", "-33.72");
        assertLot(lots.get(1), "34.00", "-7.30", "-17.68");
    }

    @Test
    void ac3_stockLots() {
        List<RowValuation> lots = stocks("8.65", stk(100, "950.00"), stk(100, "780.65"));
        assertLot(lots.get(0), "865.00", "-85.00", "-8.95");
        assertLot(lots.get(1), "865.00", "84.35", "10.81");
    }

    @Test
    void ac4_singleLotEqualsRow() {
        BigDecimal mark = d("0.20");
        RowValuation row = UnrealizedValuation.option(false, 1, 100, d("37.34"), EXP, TODAY, mark);
        List<RowValuation> lots = LotValuation.optionLots(row, false, 100, mark, List.of(opt(1, "37.34")));
        assertThat(lots).containsExactly(row);
        assertLot(lots.get(0), "-20.00", "17.34", "46.44");
    }

    @Test
    void ac5_manualMark() {
        List<RowValuation> lots = options(false, "0.35", opt(1, "18.34"), opt(1, "37.34"));
        assertLot(lots.get(0), "-35.00", "-16.66", "-90.84");
        assertLot(lots.get(1), "-35.00", "2.34", "6.27");
    }

    @Test
    void ac6_noQuoteAndExpiredPendingFollowTheRow() {
        RowValuation noQuote = UnrealizedValuation.option(false, 1, 100, d("12.00"), EXP, TODAY, null);
        List<RowValuation> lots = LotValuation.optionLots(noQuote, false, 100, null, List.of(opt(1, "12.00")));
        assertThat(lots).hasSize(1);
        assertUnavailable(lots.get(0), ValuationStatus.NO_QUOTE, "无报价");

        // expired with a stale mark still present: not valued (Q156 / Q239)
        BigDecimal stale = d("0.05");
        RowValuation expired = UnrealizedValuation.option(false, 1, 100, d("15.00"), LocalDate.of(2026, 11, 13),
                TODAY, stale);
        lots = LotValuation.optionLots(expired, false, 100, stale, List.of(opt(1, "15.00"), opt(1, "3.00")));
        assertThat(lots).hasSize(2);
        lots.forEach(l -> assertUnavailable(l, ValuationStatus.EXPIRED_PENDING, "已到期，待处理"));

        RowValuation stockNoQuote = UnrealizedValuation.stock(200, d("1730.65"), null);
        List<RowValuation> s = LotValuation.stockLots(stockNoQuote, null,
                List.of(stk(100, "950.00"), stk(100, "780.65")));
        assertThat(s).hasSize(2);
        s.forEach(l -> assertUnavailable(l, ValuationStatus.NO_QUOTE, "无报价"));
    }

    static void assertUnavailable(RowValuation v, ValuationStatus status, String label) {
        assertThat(v.status()).isEqualTo(status);
        assertThat(v.label()).isEqualTo(label);
        assertThat(v.marketValue()).isNull();
        assertThat(v.unrealizedPnl()).isNull();
        assertThat(v.percent()).isNull();
    }

    @Test
    void ac7_lastLotTakesTheRemainder() {
        List<RowValuation> lots = stocks("8.655", stk(101, "880.00"), stk(99, "860.00"));
        assertLot(lots.get(0), "874.16", "-5.84", "-0.66");
        // remainder 1731.00 - 874.16, not 8.655 x 99 = 856.845 -> 856.85
        assertLot(lots.get(1), "856.84", "-3.16", "-0.37");
    }

    @Test
    void ac8_partiallySoldStockLot() {
        List<RowValuation> lots = stocks("8.65", stk(50, "475.00"), stk(100, "780.65"));
        assertLot(lots.get(0), "432.50", "-42.50", "-8.95");
        assertLot(lots.get(1), "865.00", "84.35", "10.81");
    }

    @Test
    void ac9_accountBAt865() {
        List<RowValuation> lots = stocks("8.65", stk(101, "880.00"), stk(99, "860.00"));
        assertLot(lots.get(0), "873.65", "-6.35", "-0.72");
        assertLot(lots.get(1), "856.35", "-3.65", "-0.42");
    }

    @Test
    void zeroCostLot_percentNull() {
        List<RowValuation> lots = stocks("8.65", stk(100, "0.00"), stk(100, "780.65"));
        assertLot(lots.get(0), "865.00", "865.00", null);
        assertLot(lots.get(1), "865.00", "84.35", "10.81");
        // zero opening net on an option lot as well
        List<RowValuation> o = options(false, "0.20", opt(1, "0.00"), opt(1, "37.34"));
        assertLot(o.get(0), "-20.00", "-20.00", null);
    }

    @Test
    void remainderWithThreeOptionLotsAndOddMark() {
        // mark 0.12345 x 100 = 12.345 per contract: lots 1 and 2 round on their own, lot 3 takes the remainder
        List<RowValuation> lots = options(true, "0.12345", opt(1, "-10.00"), opt(1, "-10.00"), opt(1, "-10.00"));
        assertLot(lots.get(0), "12.35", "2.35", "23.50");
        assertLot(lots.get(1), "12.35", "2.35", "23.50");
        // row 37.035 -> 37.04; 37.04 - 24.70 = 12.34
        assertLot(lots.get(2), "12.34", "2.34", "23.40");
        List<RowValuation> s = options(false, "0.12345", opt(1, "10.00"), opt(1, "10.00"), opt(1, "10.00"));
        assertLot(s.get(0), "-12.35", "-2.35", "-23.50");
        assertLot(s.get(2), "-12.34", "-2.34", "-23.40");
    }

    @Test
    void noLots_emptyList() {
        RowValuation row = UnrealizedValuation.stock(0, d("0.00"), d("1.00"));
        assertThat(LotValuation.stockLots(row, d("1.00"), List.of())).isEmpty();
    }
}
