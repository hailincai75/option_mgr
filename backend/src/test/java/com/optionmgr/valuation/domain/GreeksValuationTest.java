package com.optionmgr.valuation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** FEAT-018 rules 2–6 and the acceptance numbers (requirement scenario, today 2026-11-23, fictional tickers). */
class GreeksValuationTest {

    static final LocalDate TODAY = LocalDate.parse("2026-11-23");
    static final LocalDate DEC18 = LocalDate.parse("2026-12-18");
    static final Instant Q0945 = MarketTime.toInstant(LocalDateTime.parse("2026-11-23T09:45:00"));

    static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    static GreeksValuation.Quote quote(String iv, String delta, String gamma, String theta, String vega) {
        return new GreeksValuation.Quote(true, false, Q0945, d(iv), d(delta), d(gamma), d(theta), d(vega));
    }

    static final GreeksValuation.Quote QRTV_50P = quote("0.3512", "-0.3021", "0.0512", "-0.0456", "0.0711");
    static final GreeksValuation.Quote QRTV_55C = quote("0.3320", "0.2850", "0.0480", "-0.0390", "0.0650");
    static final GreeksValuation.Quote IXQZ_65C = quote("0.2900", "0.18345", "0.03125", "-0.0247", "0.0503");

    static void assertGreek(RowGreeks.Greek g, String perContract, String position) {
        assertThat(g.perContract() == null ? null : g.perContract().toPlainString()).isEqualTo(perContract);
        assertThat(g.position() == null ? null : g.position().toPlainString()).isEqualTo(position);
    }

    static RowGreeks row(boolean longPosition, int contracts, LocalDate exp, GreeksValuation.Quote q) {
        return GreeksValuation.option(longPosition, contracts, 100, exp, TODAY, q);
    }

    @Test
    void ac1_shortPut_positionValuesFlipped() {
        RowGreeks g = row(false, 1, DEC18, QRTV_50P);
        assertThat(g.status()).isEqualTo(GreeksStatus.OK);
        assertThat(g.label()).isNull();
        assertThat(g.ivPercent().toPlainString()).isEqualTo("35.12");
        assertGreek(g.delta(), "-0.3021", "30.21");
        assertGreek(g.gamma(), "0.0512", "-5.12");
        assertGreek(g.theta(), "-0.0456", "4.56");
        assertGreek(g.vega(), "0.0711", "-7.11");
        assertThat(g.quoteTime()).isEqualTo(Q0945);
        assertThat(g.manualMarkNote()).isNull();
    }

    @Test
    void ac2_longCall_twoContracts() {
        RowGreeks g = row(true, 2, DEC18, QRTV_55C);
        assertThat(g.ivPercent().toPlainString()).isEqualTo("33.20");
        assertGreek(g.delta(), "0.2850", "57.00");
        assertGreek(g.gamma(), "0.0480", "9.60");
        assertGreek(g.theta(), "-0.0390", "-7.80");
        assertGreek(g.vega(), "0.0650", "13.00");
    }

    @Test
    void ac3_halfUpAwayFromZero_positionFromUnroundedValue() {
        RowGreeks g = row(false, 1, DEC18, IXQZ_65C);
        assertThat(g.ivPercent().toPlainString()).isEqualTo("29.00");
        assertGreek(g.delta(), "0.1835", "-18.35");
        assertGreek(g.gamma(), "0.0313", "-3.13");
        assertGreek(g.theta(), "-0.0247", "2.47");
        assertGreek(g.vega(), "0.0503", "-5.03");
    }

    @Test
    void ac4_neverQuoted_noQuote_evenWithManualMark() {
        for (boolean manual : new boolean[] {false, true}) {
            RowGreeks g = row(false, 1, DEC18,
                    new GreeksValuation.Quote(false, manual, null, null, null, null, null, null));
            assertThat(g.status()).isEqualTo(GreeksStatus.NO_QUOTE);
            assertThat(g.label()).isEqualTo("无报价");
            assertThat(g.ivPercent()).isNull();
            assertGreek(g.delta(), null, null);
            assertGreek(g.vega(), null, null);
            assertThat(g.quoteTime()).isNull();
            assertThat(g.manualMarkNote()).isNull();
        }
    }

    @Test
    void ac5_threeShortContracts() {
        RowGreeks g = row(false, 3, DEC18, QRTV_50P);
        assertThat(g.ivPercent().toPlainString()).isEqualTo("35.12");
        assertGreek(g.delta(), "-0.3021", "90.63");
        assertGreek(g.gamma(), "0.0512", "-15.36");
        assertGreek(g.theta(), "-0.0456", "13.68");
        assertGreek(g.vega(), "0.0711", "-21.33");
    }

    @Test
    void ac6_newValues() {
        RowGreeks g = row(false, 1, DEC18, quote("0.3600", "-0.3300", "0.0530", "-0.0470", "0.0720"));
        assertThat(g.ivPercent().toPlainString()).isEqualTo("36.00");
        assertGreek(g.delta(), "-0.3300", "33.00");
        assertGreek(g.gamma(), "0.0530", "-5.30");
        assertGreek(g.theta(), "-0.0470", "4.70");
        assertGreek(g.vega(), "0.0720", "-7.20");
    }

    @Test
    void ac7_manualMark_noteWithCboeQuoteTime_valuesUnchanged() {
        GreeksValuation.Quote q = new GreeksValuation.Quote(true, true, Q0945, d("0.3512"), d("-0.3021"),
                d("0.0512"), d("-0.0456"), d("0.0711"));
        RowGreeks g = row(false, 1, DEC18, q);
        assertThat(g.status()).isEqualTo(GreeksStatus.OK);
        assertGreek(g.delta(), "-0.3021", "30.21");
        assertThat(g.manualMarkNote()).isEqualTo("Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整");
    }

    @Test
    void ac8_singleMissingFields() {
        RowGreeks g = row(true, 2, DEC18, quote("0.3320", "0.2850", null, "-0.0390", null));
        assertThat(g.status()).isEqualTo(GreeksStatus.OK);
        assertThat(g.ivPercent().toPlainString()).isEqualTo("33.20");
        assertGreek(g.delta(), "0.2850", "57.00");
        assertGreek(g.gamma(), null, null);
        assertGreek(g.theta(), "-0.0390", "-7.80");
        assertGreek(g.vega(), null, null);

        RowGreeks noIv = row(true, 2, DEC18, quote(null, "0.2850", "0.0480", "-0.0390", "0.0650"));
        assertThat(noIv.ivPercent()).isNull();
        assertGreek(noIv.delta(), "0.2850", "57.00");
    }

    @Test
    void ac9_expiredPending_butExpirationDayIsNormal() {
        RowGreeks expired = row(false, 1, LocalDate.parse("2026-11-20"),
                new GreeksValuation.Quote(true, true, Q0945, d("0.9"), d("-0.0100"), d("0.01"), d("-0.01"), d("0.01")));
        assertThat(expired.status()).isEqualTo(GreeksStatus.EXPIRED_PENDING);
        assertThat(expired.label()).isEqualTo("已到期，待处理");
        assertThat(expired.ivPercent()).isNull();
        assertGreek(expired.delta(), null, null);
        assertThat(expired.manualMarkNote()).isNull();
        assertThat(expired.quoteTime()).isEqualTo(Q0945);

        RowGreeks today = row(false, 1, TODAY, quote("0.5", "-0.4500", "0.1", "-0.2", "0.01"));
        assertThat(today.status()).isEqualTo(GreeksStatus.OK);
        assertGreek(today.delta(), "-0.4500", "45.00");
    }

    @Test
    void zeroAndBoundaryValues() {
        RowGreeks g = row(false, 1, DEC18, quote("0.0000", "1", "0", "-0.00001", "0.000049"));
        assertThat(g.ivPercent().toPlainString()).isEqualTo("0.00");
        assertGreek(g.delta(), "1.0000", "-100.00");
        assertGreek(g.gamma(), "0.0000", "0.00");
        assertGreek(g.theta(), "0.0000", "0.00");
        assertGreek(g.vega(), "0.0000", "0.00");
        assertThat(g.theta().position().signum()).isZero();
    }

    @Test
    void multiplierFieldIsUsed() {
        RowGreeks g = GreeksValuation.option(true, 1, 10, DEC18, TODAY, QRTV_55C);
        assertGreek(g.delta(), "0.2850", "2.85");
    }
}
