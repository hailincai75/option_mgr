package com.optionmgr.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FEAT-052 rule 5 (Q262-Q264): Black-Scholes Greeks; numbers of docs/api/FEAT-052.md「Greeks 数值复核」. */
class BlackScholesTest {

    static final Instant Q1000 = ny("2026-11-20T10:00:00");
    static final LocalDate FEB19 = LocalDate.parse("2027-02-19");
    static final LocalDate DEC18 = LocalDate.parse("2026-12-18");

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static BlackScholes.Greeks g(String s, String k, String sigma, String r, Instant t, LocalDate exp, boolean call) {
        return BlackScholes.greeks(d(s), d(k), d(sigma), d(r), t, exp, call);
    }

    static void assertGreeks(BlackScholes.Greeks g, String delta, String gamma, String theta, String vega) {
        assertThat(g.delta()).isEqualByComparingTo(delta);
        assertThat(g.gamma()).isEqualByComparingTo(gamma);
        assertThat(g.theta()).isEqualByComparingTo(theta);
        assertThat(g.vega()).isEqualByComparingTo(vega);
        assertThat(g.delta().scale()).isEqualTo(8);
    }

    @Nested
    class AcceptanceNumbers {

        @Test
        void yearsToExpiry_isRealTimeTo1600NewYork() {
            assertThat(BlackScholes.yearsToExpiry(Q1000, FEB19)).isEqualTo(91.25 / 365);
            assertThat(BlackScholes.yearsToExpiry(Q1000, DEC18)).isEqualTo(28.25 / 365);
        }

        @Test
        void ac3_p1ShortPut_rateZero() {
            assertGreeks(g("50.00", "50", "0.3", "0", Q1000, FEB19, false),
                    "-0.47010736", "0.05304291", "-0.01634884", "0.09945546");
        }

        @Test
        void ac4_p3Call_rateZero() {
            assertGreeks(g("50.00", "50", "0.30000000", "0.00", Q1000, FEB19, true),
                    "0.52989264", "0.05304291", "-0.01634884", "0.09945546");
        }

        @Test
        void ac5_rateFivePercent_and_defaultFour() {
            assertGreeks(g("50.00", "50", "0.3", "0.05", Q1000, FEB19, false),
                    "-0.43709707", "0.05252971", "-0.01283104", "0.09849321"); // contract table had 0.09849322 (corrected)
            assertGreeks(g("50.00", "50", "0.3", "0.04", Q1000, FEB19, false),
                    "-0.44367165", "0.05266120", "-0.01350069", "0.09873976");
        }

        @Test
        void p2_dec18Put() {
            assertGreeks(g("50.00", "45", "0.35", "0", Q1000, DEC18, false),
                    "-0.12908334", "0.04323875", "-0.01813954", "0.02928241");
        }
    }

    @Nested
    class NoGreeks {

        @Test
        void missingOrNonPositiveInputs_allNull() {
            assertThat(g("50", "50", "0", "0", Q1000, FEB19, false)).isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(g("50", "50", "-0.1", "0", Q1000, FEB19, false)).isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(g("0", "50", "0.3", "0", Q1000, FEB19, false)).isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(g("50", "0", "0.3", "0", Q1000, FEB19, false)).isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(BlackScholes.greeks(null, d("50"), d("0.3"), d("0"), Q1000, FEB19, true))
                    .isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(BlackScholes.greeks(d("50"), d("50"), null, d("0"), Q1000, FEB19, true))
                    .isEqualTo(BlackScholes.Greeks.NONE);
        }

        @Test
        void expirationDay_before1600Computed_from1600None() {
            LocalDate exp = LocalDate.parse("2026-11-20");
            BlackScholes.Greeks before = g("50", "50", "0.3", "0", ny("2026-11-20T15:00:00"), exp, true);
            assertThat(before.delta()).isNotNull();
            assertThat(before.gamma()).isPositive();
            assertThat(g("50", "50", "0.3", "0", ny("2026-11-20T16:00:00"), exp, true))
                    .isEqualTo(BlackScholes.Greeks.NONE);
            assertThat(g("50", "50", "0.3", "0", ny("2026-11-20T16:20:00"), exp, true))
                    .isEqualTo(BlackScholes.Greeks.NONE);
        }
    }

    @Test
    void daylightSaving_realTimeDifference() {
        // 2027-03-12 10:00 EST -> 2027-03-19 16:00 EDT (DST starts 2027-03-14): 7 days + 5 hours
        assertThat(BlackScholes.yearsToExpiry(ny("2027-03-12T10:00:00"), LocalDate.parse("2027-03-19")))
                .isEqualTo((7 * 86400 + 5 * 3600) / (365.0 * 86400));
    }

    @Test
    void extremeIv_noCap() {
        BlackScholes.Greeks e = g("50", "50", "6.5", "0.04", Q1000, FEB19, false);
        assertThat(e.delta()).isNotNull();
        assertThat(e.gamma()).isPositive();
        assertThat(e.theta()).isNegative();
        assertThat(e.vega()).isPositive();
    }

    @Test
    void normalCdf_matchesHighPrecisionSeriesWithin1e14() {
        MathContext mc = new MathContext(80);
        BigDecimal invSqrt2Pi = BigDecimal.ONE.divide(
                new BigDecimal("2.5066282746310005024157652848110452530069867406099383166299235763422936546"), mc);
        for (int i = -40; i <= 40; i++) {
            double x = i * 0.2;
            BigDecimal bx = BigDecimal.valueOf(x);
            BigDecimal x2 = bx.multiply(bx, mc);
            BigDecimal term = bx;   // (-1)^n x^(2n+1) / (2^n n!)
            BigDecimal sum = BigDecimal.ZERO;
            for (int n = 0; n < 400; n++) {
                sum = sum.add(term.divide(BigDecimal.valueOf(2L * n + 1), mc), mc);
                term = term.multiply(x2, mc).divide(BigDecimal.valueOf(-2L * (n + 1)), mc);
            }
            double exact = BigDecimal.valueOf(0.5).add(invSqrt2Pi.multiply(sum, mc), mc).doubleValue();
            assertThat(Math.abs(BlackScholes.cdf(x) - exact)).as("N(%s)", x).isLessThan(1e-14);
        }
        assertThat(BlackScholes.cdf(0)).isEqualTo(0.5);
        assertThat(BlackScholes.cdf(40)).isEqualTo(1.0);
        assertThat(BlackScholes.cdf(-40)).isEqualTo(0.0);
    }
}
