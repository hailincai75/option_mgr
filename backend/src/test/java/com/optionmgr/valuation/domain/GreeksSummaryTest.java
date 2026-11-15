package com.optionmgr.valuation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.valuation.domain.GreeksSummary.ExposureStatus;
import com.optionmgr.valuation.domain.GreeksSummary.Group;
import com.optionmgr.valuation.domain.GreeksSummary.OptionRow;
import com.optionmgr.valuation.domain.GreeksSummary.Underlying;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FEAT-019 rules with the requirement's numbers (fictional tickers QRTV / IXQZ). */
class GreeksSummaryTest {

    static final OptionRow P1 = ok("QRTV", "30.21", "-5.12", "4.56", "-7.11");
    static final OptionRow P2 = ok("QRTV", "57.00", "9.60", "-7.80", "13.00");
    static final OptionRow P3 = ok("IXQZ", "-18.35", "-3.13", "2.47", "-5.03");
    static final OptionRow P4 = other("QRTV", GreeksStatus.NO_QUOTE);
    static final OptionRow P5 = ok("QRTV", "90.63", "-15.36", "13.68", "-21.33");
    static final OptionRow P6 = other("QRTV", GreeksStatus.EXPIRED_PENDING);
    static final OptionRow P7 = ok("QRTV", "45.00", "-12.00", "8.00", "-1.50");

    final Map<String, Underlying> prices = new HashMap<>(Map.of(
            "QRTV", new Underlying(new BigDecimal("52.40"), false),
            "IXQZ", new Underlying(new BigDecimal("61.10"), false)));

    GreeksSummary run(List<OptionRow> rows, Map<String, Long> shares) {
        return GreeksSummary.of(rows, shares, prices::get);
    }

    GreeksSummary accountA(OptionRow... extra) {
        List<OptionRow> rows = new ArrayList<>(List.of(P1, P2, P3, P4));
        rows.addAll(List.of(extra));
        return run(rows, Map.of("IXQZ", 100L));
    }

    @Test
    void ac1_ac2_ac3_accountA() {
        GreeksSummary s = accountA();
        assertThat(s.groups()).extracting(Group::symbol).containsExactly("IXQZ", "QRTV");
        Group ixqz = s.groups().get(0);
        assertNumbers(ixqz, "-18.35", "100.00", "81.65", "-3.13", "2.47", "-5.03");
        assertExposure(ixqz, "61.10", "4988.82", ExposureStatus.OK);
        assertThat(ixqz.excludedLabel()).isNull();
        assertThat(ixqz.missingLabel()).isNull();

        Group qrtv = s.groups().get(1);
        assertNumbers(qrtv, "87.21", "0.00", "87.21", "4.48", "-3.24", "5.89");
        assertExposure(qrtv, "52.40", "4569.80", ExposureStatus.OK);
        assertThat(qrtv.manualPriceNote()).isNull();
        assertThat(qrtv.optionRowCount()).isEqualTo(3);
        assertThat(qrtv.includedCount()).isEqualTo(2);
        assertThat(qrtv.noQuoteCount()).isEqualTo(1);
        assertThat(qrtv.excludedLabel()).isEqualTo("1 个持仓无报价，未计入");

        GreeksSummary.Portfolio p = s.portfolio();
        assertPortfolio(p, "9558.62", "-0.77", "0.86");
        assertThat(p.excludedLabel()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(p.noUnderlyingPriceLabel()).isNull();
        assertThat(p.missingLabel()).isNull();
    }

    @Test
    void ac4_accountB() {
        GreeksSummary s = run(List.of(P5), Map.of("QRTV", 100L));
        Group q = single(s);
        assertNumbers(q, "90.63", "100.00", "190.63", "-15.36", "13.68", "-21.33");
        assertExposure(q, "52.40", "9989.01", ExposureStatus.OK);
        assertPortfolio(s.portfolio(), "9989.01", "13.68", "-21.33");
        assertThat(s.portfolio().excludedLabel()).isNull();
    }

    @Test
    void ac5_manualUnderlyingPrice() {
        prices.put("QRTV", new Underlying(new BigDecimal("52.00"), true));
        GreeksSummary a = accountA();
        Group q = a.groups().get(1);
        assertExposure(q, "52.00", "4534.92", ExposureStatus.OK);
        assertThat(q.underlyingPriceManual()).isTrue();
        assertThat(q.manualPriceNote()).isEqualTo("按手动标的价");
        assertNumbers(q, "87.21", "0.00", "87.21", "4.48", "-3.24", "5.89");
        assertThat(a.portfolio().dollarExposure()).hasToString("9523.74");
        assertThat(a.groups().get(0).manualPriceNote()).isNull();
        GreeksSummary b = run(List.of(P5), Map.of("QRTV", 100L));
        assertThat(single(b).dollarExposure()).hasToString("9912.76");
    }

    @Test
    void ac6_singleMissingGreeks() {
        OptionRow p2 = new OptionRow("QRTV", new RowGreeks(GreeksStatus.OK, new BigDecimal("33.20"),
                greek("57.00"), greek(null), greek("-7.80"), greek(null), null, null));
        GreeksSummary s = run(List.of(P1, p2, P3, P4), Map.of("IXQZ", 100L));
        Group q = s.groups().get(1);
        assertNumbers(q, "87.21", "0.00", "87.21", "-5.12", "-3.24", "-7.11");
        assertExposure(q, "52.40", "4569.80", ExposureStatus.OK);
        assertThat(q.includedCount()).isEqualTo(2);
        assertThat(q.missing()).isEqualTo(new GreeksSummary.Missing(0, 1, 0, 1));
        assertThat(q.excludedLabel()).isEqualTo("1 个持仓无报价，未计入");
        assertThat(q.missingLabel()).isEqualTo("Gamma、Vega：1 个持仓缺值，未计入");
        assertPortfolio(s.portfolio(), "9558.62", "-0.77", "-12.14");
        assertThat(s.portfolio().missing()).isEqualTo(new GreeksSummary.Missing(0, 0, 0, 1));
        assertThat(s.portfolio().missingLabel()).isEqualTo("Vega：1 个持仓缺值，未计入");
    }

    @Test
    void ac7_expiredPendingAndExpirationDay() {
        GreeksSummary s = accountA(P6, P7);
        Group q = s.groups().get(1);
        assertNumbers(q, "132.21", "0.00", "132.21", "-7.52", "4.76", "4.39");
        assertExposure(q, "52.40", "6927.80", ExposureStatus.OK);
        assertThat(q.excludedLabel()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
        assertPortfolio(s.portfolio(), "11916.62", "7.23", "-0.64");
        assertThat(s.portfolio().excludedLabel()).isEqualTo("1 个持仓无报价、1 个持仓已到期待处理，未计入");
    }

    @Test
    void ac8_noUnderlyingPrice_thenManual() {
        prices.clear();
        GreeksSummary s = run(List.of(), Map.of("IXQZ", 100L));
        Group g = single(s);
        assertThat(g.optionDelta()).isNull();
        assertThat(g.gamma()).isNull();
        assertThat(g.theta()).isNull();
        assertThat(g.vega()).isNull();
        assertThat(g.stockDelta()).hasToString("100.00");
        assertThat(g.netDelta()).hasToString("100.00");
        assertThat(g.underlyingPrice()).isNull();
        assertThat(g.underlyingPriceLabel()).isEqualTo("无标的价");
        assertThat(g.dollarExposure()).isNull();
        assertThat(g.exposureStatus()).isEqualTo(ExposureStatus.NO_UNDERLYING_PRICE);
        assertThat(g.exposureLabel()).isEqualTo("无标的价");
        assertPortfolio(s.portfolio(), "0.00", "0.00", "0.00");
        assertThat(s.portfolio().noUnderlyingPriceCount()).isEqualTo(1);
        assertThat(s.portfolio().noUnderlyingPriceLabel()).isEqualTo("1 个标的无标的价，美元敞口未计入");

        prices.put("IXQZ", new Underlying(new BigDecimal("60.00"), true));
        s = run(List.of(), Map.of("IXQZ", 100L));
        assertExposure(single(s), "60.00", "6000.00", ExposureStatus.OK);
        assertThat(single(s).manualPriceNote()).isEqualTo("按手动标的价");
        assertThat(s.portfolio().dollarExposure()).hasToString("6000.00");
        assertThat(s.portfolio().noUnderlyingPriceLabel()).isNull();
    }

    @Test
    void ac9_afterClosingP2_andIxqzGone() {
        GreeksSummary s = run(List.of(P1, P3, P4), Map.of("IXQZ", 100L));
        Group q = s.groups().get(1);
        assertNumbers(q, "30.21", "0.00", "30.21", "-5.12", "4.56", "-7.11");
        assertExposure(q, "52.40", "1583.00", ExposureStatus.OK);
        assertPortfolio(s.portfolio(), "6571.82", "7.03", "-12.14");
        s = run(List.of(P1, P4), Map.of());
        assertThat(s.groups()).extracting(Group::symbol).containsExactly("QRTV");
    }

    @Test
    void onlyExpiredPendingWithoutStock_isNoDelta() {
        GreeksSummary s = run(List.of(P6), Map.of());
        Group g = single(s);
        assertThat(g.optionDelta()).isNull();
        assertThat(g.netDelta()).isNull();
        assertThat(g.dollarExposure()).isNull();
        assertThat(g.exposureStatus()).isEqualTo(ExposureStatus.NO_DELTA);
        assertThat(g.exposureLabel()).isNull();
        assertThat(g.excludedLabel()).isEqualTo("1 个持仓已到期待处理，未计入");
        assertThat(s.portfolio().noUnderlyingPriceCount()).isZero();
        assertPortfolio(s.portfolio(), "0.00", "0.00", "0.00");
    }

    @Test
    void allOptionsExcludedWithStock_usesStockDelta() {
        GreeksSummary s = run(List.of(P4), Map.of("QRTV", 100L));
        Group g = single(s);
        assertThat(g.optionDelta()).isNull();
        assertThat(g.netDelta()).hasToString("100.00");
        assertExposure(g, "52.40", "5240.00", ExposureStatus.OK);
    }

    @Test
    void zeroNetDelta_andNegativeHalfUp() {
        GreeksSummary s = run(List.of(ok("QRTV", "-100.00", "0.00", "0.00", "0.00")), Map.of("QRTV", 100L));
        assertThat(single(s).netDelta()).hasToString("0.00");
        assertThat(single(s).dollarExposure()).hasToString("0.00");

        prices.put("QRTV", new Underlying(new BigDecimal("0.125"), false));
        s = run(List.of(ok("QRTV", "-0.02", "0.00", "0.00", "0.00")), Map.of());
        assertThat(single(s).dollarExposure()).hasToString("0.00"); // -0.0025 -> 0.00, no negative zero
        s = run(List.of(ok("QRTV", "-0.20", "0.00", "0.00", "0.00")), Map.of());
        assertThat(single(s).dollarExposure()).hasToString("-0.03"); // -0.025 -> -0.03
    }

    @Test
    void missingLabel_differentCounts() {
        assertThat(GreeksSummary.missingLabel(new GreeksSummary.Missing(0, 1, 2, 1)))
                .isEqualTo("Gamma、Vega：1 个持仓缺值；Theta：2 个持仓缺值，未计入");
        assertThat(GreeksSummary.missingLabel(GreeksSummary.Missing.NONE)).isNull();
    }

    @Test
    void emptyAccount() {
        GreeksSummary s = run(List.of(), Map.of());
        assertThat(s.groups()).isEmpty();
        assertPortfolio(s.portfolio(), "0.00", "0.00", "0.00");
        assertThat(s.portfolio().excludedLabel()).isNull();
        assertThat(s.portfolio().noUnderlyingPriceLabel()).isNull();
        assertThat(s.portfolio().missingLabel()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    static OptionRow ok(String symbol, String d, String g, String t, String v) {
        return new OptionRow(symbol, new RowGreeks(GreeksStatus.OK, new BigDecimal("30.00"), greek(d), greek(g),
                greek(t), greek(v), null, null));
    }

    static OptionRow other(String symbol, GreeksStatus status) {
        return new OptionRow(symbol, new RowGreeks(status, null, RowGreeks.Greek.NONE, RowGreeks.Greek.NONE,
                RowGreeks.Greek.NONE, RowGreeks.Greek.NONE, null, null));
    }

    static RowGreeks.Greek greek(String position) {
        return position == null ? RowGreeks.Greek.NONE : new RowGreeks.Greek(BigDecimal.ONE, new BigDecimal(position));
    }

    static Group single(GreeksSummary s) {
        assertThat(s.groups()).hasSize(1);
        return s.groups().get(0);
    }

    static void assertNumbers(Group g, String optionDelta, String stockDelta, String net, String gamma, String theta,
                              String vega) {
        assertThat(g.optionDelta()).hasToString(optionDelta);
        assertThat(g.stockDelta()).hasToString(stockDelta);
        assertThat(g.netDelta()).hasToString(net);
        assertThat(g.gamma()).hasToString(gamma);
        assertThat(g.theta()).hasToString(theta);
        assertThat(g.vega()).hasToString(vega);
    }

    static void assertExposure(Group g, String price, String exposure, ExposureStatus status) {
        assertThat(g.underlyingPrice()).hasToString(price);
        assertThat(g.dollarExposure()).hasToString(exposure);
        assertThat(g.exposureStatus()).isEqualTo(status);
        assertThat(g.underlyingPriceLabel()).isNull();
        assertThat(g.exposureLabel()).isNull();
    }

    static void assertPortfolio(GreeksSummary.Portfolio p, String exposure, String theta, String vega) {
        assertThat(p.dollarExposure()).hasToString(exposure);
        assertThat(p.theta()).hasToString(theta);
        assertThat(p.vega()).hasToString(vega);
    }
}
