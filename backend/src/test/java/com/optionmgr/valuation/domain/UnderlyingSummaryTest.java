package com.optionmgr.valuation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.trade.domain.PnlSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FEAT-023 domain: the numbers of acceptance criteria 1, 5–8 (requirement scenario, fictional tickers QRTV / IXQZ /
 * ZWLK / QFZ) built from FEAT-017 row valuations and FEAT-040 realized breakdowns.
 */
class UnderlyingSummaryTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static RowValuation ok(String mv, String pnl) {
        return new RowValuation(ValuationStatus.OK, d(mv), d(pnl), null);
    }

    static final RowValuation NO_QUOTE = new RowValuation(ValuationStatus.NO_QUOTE, null, null, null);
    static final RowValuation EXPIRED = new RowValuation(ValuationStatus.EXPIRED_PENDING, null, null, null);

    static PnlSummary.Breakdown realized(int optCount, String opt, int stkCount, String stk) {
        PnlSummary.Group o = new PnlSummary.Group(optCount, d(opt));
        PnlSummary.Group s = new PnlSummary.Group(stkCount, d(stk));
        return new PnlSummary.Breakdown(new PnlSummary.Group(optCount + stkCount, d(opt).add(d(stk))), o, s);
    }

    static UnderlyingSummary.OptionRow opt(String symbol, String net, RowValuation v) {
        return new UnderlyingSummary.OptionRow(symbol, d(net), v);
    }

    static UnderlyingSummary.StockRow stk(String symbol, RowValuation v) {
        return new UnderlyingSummary.StockRow(symbol, v);
    }

    /** Account A of the scenario; QFZ has realized P&amp;L only. */
    static final Map<String, PnlSummary.Breakdown> REALIZED_A = Map.of(
            "IXQZ", realized(1, "80.00", 0, "0.00"),
            "QRTV", realized(1, "39.70", 0, "0.00"),
            "ZWLK", realized(0, "0.00", 1, "100.00"),
            "QFZ", realized(1, "39.35", 0, "0.00"));

    static List<UnderlyingSummary.OptionRow> optionsA() {
        return List.of(
                opt("IXQZ", "90.00", EXPIRED),
                opt("QRTV", "85.35", ok("-60.00", "25.35")),
                opt("QRTV", "120.35", ok("-110.00", "10.35")),
                opt("QRTV", "-40.65", ok("30.00", "-10.65")),
                opt("IXQZ", "200.00", ok("-250.00", "-50.00")));
    }

    static void assertGroup(UnderlyingSummary.Group g, String symbol, String realizedPnl, String mv, String pnl,
                            String total, String premium, int options, boolean stock, int included, int noQuote,
                            int expired, String label) {
        assertThat(g.symbol()).isEqualTo(symbol);
        assertThat(g.realized().all().realizedPnl()).isEqualTo(d(realizedPnl));
        assertThat(g.marketValue()).isEqualTo(mv == null ? null : d(mv));
        assertThat(g.unrealizedPnl()).isEqualTo(pnl == null ? null : d(pnl));
        assertThat(g.totalPnl()).isEqualTo(d(total));
        assertThat(g.netPremium()).isEqualTo(premium == null ? null : d(premium));
        assertThat(g.optionPositionCount()).isEqualTo(options);
        assertThat(g.hasStockPosition()).isEqualTo(stock);
        assertThat(g.includedCount()).isEqualTo(included);
        assertThat(g.noQuoteCount()).isEqualTo(noQuote);
        assertThat(g.expiredPendingCount()).isEqualTo(expired);
        assertThat(g.excludedLabel()).isEqualTo(label);
    }

    @Test
    void ac1_accountA_groupsInSymbolOrder_withoutHistoryOnlySymbol() {
        UnderlyingSummary s = UnderlyingSummary.of(optionsA(),
                List.of(stk("QRTV", ok("5000.00", "200.00")), stk("ZWLK", NO_QUOTE)), REALIZED_A::get);
        assertThat(s.groups()).extracting(UnderlyingSummary.Group::symbol).containsExactly("IXQZ", "QRTV", "ZWLK");
        assertGroup(s.groups().get(0), "IXQZ", "80.00", "-250.00", "-50.00", "30.00", "290.00", 2, false, 1, 0, 1,
                "1 个持仓已到期待处理，未计入");
        assertGroup(s.groups().get(1), "QRTV", "39.70", "4860.00", "225.05", "264.75", "165.05", 3, true, 4, 0, 0,
                null);
        assertGroup(s.groups().get(2), "ZWLK", "100.00", null, null, "100.00", null, 0, true, 0, 1, 0,
                "1 个持仓无报价，未计入");
        assertThat(s.groups().get(2).realized().option().closedCount()).isZero();
        assertThat(s.groups().get(2).realized().stock().realizedPnl()).isEqualTo(d("100.00"));
        assertThat(s.groups().get(1).totalPnl().scale()).isEqualTo(2);
    }

    @Test
    void ac5_afterClosingThe45Put() {
        UnderlyingSummary s = UnderlyingSummary.of(List.of(
                        opt("QRTV", "85.35", ok("-60.00", "25.35")),
                        opt("QRTV", "-40.65", ok("30.00", "-10.65"))),
                List.of(stk("QRTV", ok("5000.00", "200.00"))),
                Map.of("QRTV", realized(2, "99.40", 0, "0.00"))::get);
        assertGroup(s.groups().get(0), "QRTV", "99.40", "4970.00", "214.70", "314.10", "44.70", 2, true, 3, 0, 0,
                null);
    }

    @Test
    void ac6_afterExpiringThe58Put() {
        UnderlyingSummary s = UnderlyingSummary.of(List.of(opt("IXQZ", "200.00", ok("-250.00", "-50.00"))),
                List.of(), Map.of("IXQZ", realized(2, "170.00", 0, "0.00"))::get);
        assertGroup(s.groups().get(0), "IXQZ", "170.00", "-250.00", "-50.00", "120.00", "200.00", 1, false, 1, 0, 0,
                null);
    }

    @Test
    void ac7_zwlkManualPriceThenEditedSaleThenCleared() {
        UnderlyingSummary s = UnderlyingSummary.of(List.of(), List.of(stk("ZWLK", ok("960.00", "60.00"))),
                Map.of("ZWLK", realized(0, "0.00", 1, "100.00"))::get);
        assertGroup(s.groups().get(0), "ZWLK", "100.00", "960.00", "60.00", "160.00", null, 0, true, 1, 0, 0, null);

        s = UnderlyingSummary.of(List.of(), List.of(stk("ZWLK", ok("960.00", "60.00"))),
                Map.of("ZWLK", realized(0, "0.00", 1, "50.00"))::get);
        assertGroup(s.groups().get(0), "ZWLK", "50.00", "960.00", "60.00", "110.00", null, 0, true, 1, 0, 0, null);

        s = UnderlyingSummary.of(List.of(), List.of(stk("ZWLK", NO_QUOTE)),
                Map.of("ZWLK", realized(0, "0.00", 1, "50.00"))::get);
        assertGroup(s.groups().get(0), "ZWLK", "50.00", null, null, "50.00", null, 0, true, 0, 1, 0,
                "1 个持仓无报价，未计入");
    }

    @Test
    void ac8_noRealizedRecords_isZeroNotNull() {
        UnderlyingSummary s = UnderlyingSummary.of(List.of(opt("QRTV", "115.00", ok("-110.00", "5.00"))), List.of(),
                symbol -> null);
        UnderlyingSummary.Group g = s.groups().get(0);
        assertGroup(g, "QRTV", "0.00", "-110.00", "5.00", "5.00", "115.00", 1, false, 1, 0, 0, null);
        assertThat(g.realized()).isEqualTo(PnlSummary.Breakdown.EMPTY);
        assertThat(g.realized().all().realizedPnl().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void emptyAccount_noGroups() {
        assertThat(UnderlyingSummary.of(List.of(), List.of(), symbol -> null).groups()).isEmpty();
    }

    @Test
    void bothExclusionKinds_labelAndZeroSums() {
        UnderlyingSummary s = UnderlyingSummary.of(List.of(opt("QRTV", "50.00", EXPIRED), opt("QRTV", "10.00",
                NO_QUOTE), opt("QRTV", "-10.00", ok("10.00", "0.00"))), List.of(), symbol -> null);
        UnderlyingSummary.Group g = s.groups().get(0);
        assertGroup(g, "QRTV", "0.00", "10.00", "0.00", "0.00", "50.00", 3, false, 1, 1, 1,
                "1 个持仓无报价、1 个持仓已到期待处理，未计入");
    }

    @Test
    void groupSumsEqualTheAccountTotals() {
        List<UnderlyingSummary.OptionRow> options = optionsA();
        List<UnderlyingSummary.StockRow> stocks = List.of(stk("QRTV", ok("5000.00", "200.00")), stk("ZWLK", NO_QUOTE));
        UnderlyingSummary s = UnderlyingSummary.of(options, stocks, REALIZED_A::get);
        UnrealizedSummary totals = UnrealizedSummary.of(options.stream().map(UnderlyingSummary.OptionRow::valuation)
                .toList(), stocks.stream().map(UnderlyingSummary.StockRow::valuation).toList());
        BigDecimal mv = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        int included = 0;
        int noQuote = 0;
        int expired = 0;
        for (UnderlyingSummary.Group g : s.groups()) {
            mv = g.marketValue() == null ? mv : mv.add(g.marketValue());
            pnl = g.unrealizedPnl() == null ? pnl : pnl.add(g.unrealizedPnl());
            included += g.includedCount();
            noQuote += g.noQuoteCount();
            expired += g.expiredPendingCount();
        }
        assertThat(mv).isEqualTo(totals.total().marketValue()).isEqualTo(d("4610.00"));
        assertThat(pnl).isEqualTo(totals.total().unrealizedPnl()).isEqualTo(d("175.05"));
        assertThat(included).isEqualTo(totals.total().includedCount());
        assertThat(noQuote).isEqualTo(totals.total().noQuoteCount());
        assertThat(expired).isEqualTo(totals.total().expiredPendingCount());
    }
}
