package com.optionmgr.valuation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-017 rules 1–7 with the requirement's numbers (fictional tickers QRTV / IXQZ, today 2026-11-23). */
class UnrealizedValuationTest {

    static final LocalDate TODAY = LocalDate.parse("2026-11-23");
    static final LocalDate EXP = LocalDate.parse("2026-12-18");

    static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static RowValuation shortOpt(String net, String mark) {
        return UnrealizedValuation.option(false, 1, 100, d(net), EXP, TODAY, d(mark));
    }

    static RowValuation longOpt(int contracts, String net, String mark) {
        return UnrealizedValuation.option(true, contracts, 100, d(net).negate(), EXP, TODAY, d(mark));
    }

    static void assertRow(RowValuation r, String mv, String pnl, String pct) {
        assertThat(r.status()).isEqualTo(ValuationStatus.OK);
        assertThat(r.label()).isNull();
        assertThat(r.marketValue().toPlainString()).isEqualTo(mv);
        assertThat(r.unrealizedPnl().toPlainString()).isEqualTo(pnl);
        assertThat(r.percent() == null ? null : r.percent().toPlainString()).isEqualTo(pct);
    }

    @Test
    void ac1_optionRows() {
        assertRow(shortOpt("118.70", "1.25"), "-125.00", "-6.30", "-5.31");
        assertRow(longOpt(2, "301.30", "1.85"), "370.00", "68.70", "22.80");
        assertRow(shortOpt("84.35", "0.85"), "-85.00", "-0.65", "-0.77");
    }

    @Test
    void ac2_stockRow() {
        assertRow(UnrealizedValuation.stock(100, d("5880.00"), d("61.10")), "6110.00", "230.00", "3.91");
    }

    @Test
    void ac3_noQuote() {
        RowValuation r = shortOpt("45.00", null);
        assertThat(r.status()).isEqualTo(ValuationStatus.NO_QUOTE);
        assertThat(r.label()).isEqualTo("无报价");
        assertThat(r.marketValue()).isNull();
        assertThat(r.unrealizedPnl()).isNull();
        assertThat(r.percent()).isNull();
        RowValuation s = UnrealizedValuation.stock(100, d("5880.00"), null);
        assertThat(s.status()).isEqualTo(ValuationStatus.NO_QUOTE);
        assertThat(s.label()).isEqualTo("无报价");
    }

    @Test
    void ac3_summaryWithOneNoQuote() {
        UnrealizedSummary s = UnrealizedSummary.of(
                List.of(shortOpt("118.70", "1.25"), longOpt(2, "301.30", "1.85"), shortOpt("84.35", "0.85"),
                        shortOpt("45.00", null)),
                List.of(UnrealizedValuation.stock(100, d("5880.00"), d("61.10"))));
        assertGroup(s.option(), "160.00", "61.75", 3, 1, 0);
        assertGroup(s.stock(), "6110.00", "230.00", 1, 0, 0);
        assertGroup(s.total(), "6270.00", "291.75", 4, 1, 0);
        assertThat(s.excludedLabel()).isEqualTo("1 个持仓无报价，未计入");
    }

    @Test
    void ac4_accountB() {
        assertRow(shortOpt("120.00", "1.25"), "-125.00", "-5.00", "-4.17");
        UnrealizedSummary s = UnrealizedSummary.of(List.of(shortOpt("120.00", "1.25")), List.of());
        assertGroup(s.option(), "-125.00", "-5.00", 1, 0, 0);
        assertGroup(s.stock(), "0.00", "0.00", 0, 0, 0);
        assertGroup(s.total(), "-125.00", "-5.00", 1, 0, 0);
        assertThat(s.excludedLabel()).isNull();
    }

    @Test
    void ac5_manualMarks() {
        assertRow(shortOpt("118.70", "1.40"), "-140.00", "-21.30", "-17.94");
        assertRow(shortOpt("120.00", "1.40"), "-140.00", "-20.00", "-16.67");
        assertRow(shortOpt("45.00", "0.30"), "-30.00", "15.00", "33.33");
    }

    @Test
    void ac6_thirdDecimalMarkAndTheo() {
        assertRow(longOpt(1, "15.65", "0.125"), "12.50", "-3.15", "-20.13");
        assertRow(longOpt(1, "15.65", "0.03"), "3.00", "-12.65", "-80.83");
    }

    @Test
    void rule4_marketValueRoundedHalfUpThenPnl() {
        // 0.0125 x 100 = 1.25 exactly; 0.01255 x 100 = 1.255 -> 1.26 (short: -1.26)
        assertRow(shortOpt("10.00", "0.01255"), "-1.26", "8.74", "87.40");
        assertRow(longOpt(1, "10.00", "0.01255"), "1.26", "-8.74", "-87.40");
    }

    @Test
    void ac8_expiredPendingEvenWithLastMark_expirationDayStillValued() {
        RowValuation r = UnrealizedValuation.option(false, 1, 100, d("60.00"), LocalDate.parse("2026-11-20"), TODAY,
                d("0.05"));
        assertThat(r.status()).isEqualTo(ValuationStatus.EXPIRED_PENDING);
        assertThat(r.label()).isEqualTo("已到期，待处理");
        assertThat(r.marketValue()).isNull();
        RowValuation expiredNoQuote = UnrealizedValuation.option(false, 1, 100, d("60.00"),
                LocalDate.parse("2026-11-20"), TODAY, null);
        assertThat(expiredNoQuote.status()).isEqualTo(ValuationStatus.EXPIRED_PENDING);
        RowValuation today = UnrealizedValuation.option(false, 1, 100, d("60.00"), TODAY, TODAY, d("0.05"));
        assertRow(today, "-5.00", "55.00", "91.67");

        UnrealizedSummary s = UnrealizedSummary.of(List.of(shortOpt("45.00", null), r), List.of());
        assertThat(s.excludedLabel()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
        assertGroup(s.option(), "0.00", "0.00", 0, 1, 1);
        assertThat(UnrealizedSummary.of(List.of(r), List.of()).excludedLabel())
                .isEqualTo("1 个持仓已到期待处理，未计入");
    }

    @Test
    void ac9_afterClosingP2() {
        UnrealizedSummary s = UnrealizedSummary.of(
                List.of(shortOpt("118.70", "1.25"), shortOpt("84.35", "0.85"), shortOpt("45.00", null)),
                List.of(UnrealizedValuation.stock(100, d("5880.00"), d("61.10"))));
        assertGroup(s.option(), "-210.00", "-6.95", 2, 1, 0);
        assertGroup(s.total(), "5900.00", "223.05", 3, 1, 0);
    }

    @Test
    void ac10_noPositions() {
        UnrealizedSummary s = UnrealizedSummary.of(List.of(), List.of());
        assertGroup(s.option(), "0.00", "0.00", 0, 0, 0);
        assertGroup(s.stock(), "0.00", "0.00", 0, 0, 0);
        assertGroup(s.total(), "0.00", "0.00", 0, 0, 0);
        assertThat(s.excludedLabel()).isNull();
    }

    @Test
    void zeroDenominator_percentNull() {
        assertRow(UnrealizedValuation.stock(100, d("0.00"), d("7.50")), "750.00", "750.00", null);
        assertRow(shortOpt("0.00", "0.10"), "-10.00", "-10.00", null);
    }

    @Test
    void multiplierFieldIsUsed() {
        RowValuation r = UnrealizedValuation.option(true, 1, 10, d("-5.00"), EXP, TODAY, d("1.25"));
        assertRow(r, "12.50", "7.50", "150.00");
    }

    static void assertGroup(UnrealizedSummary.Group g, String mv, String pnl, int included, int noQuote,
                            int expired) {
        assertThat(g.marketValue().toPlainString()).isEqualTo(mv);
        assertThat(g.unrealizedPnl().toPlainString()).isEqualTo(pnl);
        assertThat(g.includedCount()).isEqualTo(included);
        assertThat(g.noQuoteCount()).isEqualTo(noQuote);
        assertThat(g.expiredPendingCount()).isEqualTo(expired);
    }
}
