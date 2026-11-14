package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-014 domain rules of an exercise (EXR): replay, Q111 figures, Q96 / Q99 rounding, Q81 dates. Pure Java. */
class ExerciseTermsTest {

    private static final ContractKey QRTV_C = new ContractKey("QRTV", LocalDate.of(2026, 11, 20), new BigDecimal("30"),
            OptionType.CALL);
    private static final Instant AT = Instant.parse("2026-11-20T21:00:00Z");

    private static LotTrade trade(long id, String at, TradeAction action, int contracts, String cash) {
        return new LotTrade(id, Instant.parse(at), action, contracts, action.isOpening() ? 100 : LotTrade.INHERIT_MULTIPLIER,
                new BigDecimal(cash), BigDecimal.ZERO, null);
    }

    @Test
    void exerciseClosesEarliestWholeLongLotsWithZeroOptionPnl() {
        ContractHistory h = ContractReplay.replay(List.of(
                trade(1, "2026-11-04T15:00:00Z", TradeAction.BTO, 2, "-300.00"),
                trade(2, "2026-11-05T15:00:00Z", TradeAction.BTO, 1, "-160.00"),
                trade(3, "2026-11-20T21:00:00Z", TradeAction.EXR, 3, "0.00")));
        ReplayedTrade step = h.step(3);
        assertThat(step.isValid()).isTrue();
        CloseMatch m = step.match().orElseThrow();
        assertThat(m.coveredLots()).hasSize(2);
        assertThat(m.realizedPnl()).isEqualByComparingTo("0.00");
        assertThat(h.openLots()).isEmpty();
    }

    @Test
    void exerciseOfShortPositionAndPartialLotsAreViolations() {
        ContractHistory shortH = ContractReplay.replay(List.of(
                trade(1, "2026-11-06T15:00:00Z", TradeAction.STO, 1, "90.00"),
                trade(2, "2026-11-20T21:00:00Z", TradeAction.EXR, 1, "0.00")));
        assertThat(shortH.step(2).violations()).containsExactly(ViolationKind.CLOSE_WRONG_DIRECTION);
        assertThat(TradeRejectionMessages.of(QRTV_C, shortH.step(2), ViolationKind.CLOSE_WRONG_DIRECTION, true))
                .isEqualTo("空头持仓不能行权（被行权请用\"被指派\"）");

        ContractHistory partial = ContractReplay.replay(List.of(
                trade(1, "2026-11-04T15:00:00Z", TradeAction.BTO, 2, "-300.00"),
                trade(2, "2026-11-05T15:00:00Z", TradeAction.BTO, 1, "-160.00"),
                trade(3, "2026-11-20T21:00:00Z", TradeAction.EXR, 1, "0.00")));
        assertThat(TradeRejectionMessages.of(QRTV_C, partial.step(3), ViolationKind.CLOSE_NOT_WHOLE_LOTS, true))
                .isEqualTo("必须一次行权整笔开仓：最早一笔是 2 张，可为 2 张或 3 张");
    }

    @Test
    void callExerciseFiguresUseTheNegativePremiumOfLongLots() {
        // Q111: stock cost = 30 x 300 + 460 + 0
        AssignmentTerms t = new AssignmentTerms(new BigDecimal("30"), 3, 100, new BigDecimal("0.00"),
                new BigDecimal("-460.00"), BigDecimal.ZERO);
        assertThat(t.purchaseAmount()).isEqualByComparingTo("9000.00");
        assertThat(t.stockCost()).isEqualByComparingTo("9460.00");
        assertThat(t.stockCostPerShare()).hasToString("31.5333");
        assertThat(t.cashChange()).isEqualByComparingTo("-9000.00");
        assertThat(t.frozenChange()).isEqualByComparingTo("0.00");
        // Q96: rounded up, multiplier 1
        AssignmentTerms small = new AssignmentTerms(new BigDecimal("12.345"), 1, 1, BigDecimal.ZERO,
                new BigDecimal("-0.50"), BigDecimal.ZERO);
        assertThat(small.purchaseAmount()).hasToString("12.35");
        assertThat(small.stockCost()).hasToString("12.85");
    }

    @Test
    void putExerciseFiguresUseTheNegativePremiumOfLongLots() {
        // Q111: proceeds = 45 x 100 - 120 - 0
        CallAssignmentTerms t = new CallAssignmentTerms(new BigDecimal("45"), 1, 100, BigDecimal.ZERO,
                new BigDecimal("-120.00"));
        assertThat(t.saleAmount()).isEqualByComparingTo("4500.00");
        assertThat(t.proceeds()).isEqualByComparingTo("4380.00");
        assertThat(t.cashChange()).isEqualByComparingTo("4500.00");
        // Q99: rounded down, multiplier 1
        CallAssignmentTerms small = new CallAssignmentTerms(new BigDecimal("12.345"), 1, 1, BigDecimal.ZERO,
                new BigDecimal("-0.40"));
        assertThat(small.saleAmount()).hasToString("12.34");
        assertThat(small.proceeds()).hasToString("11.94");
    }

    @Test
    void stockLegDirectionAndTexts() {
        assertThat(TradeAction.EXR.buysStock(OptionType.CALL)).isTrue();
        assertThat(TradeAction.EXR.buysStock(OptionType.PUT)).isFalse();
        assertThat(TradeAction.ASG.buysStock(OptionType.PUT)).isTrue();
        assertThat(TradeAction.EXR.isDeleteOnly()).isTrue();
        assertThat(TradeAction.EXR.hasStockLeg()).isTrue();
        assertThat(AssignmentTerms.describe(TradeAction.EXR, QRTV_C, 3, 100))
                .isEqualTo("行权 3 张 QRTV 2026-11-20 30 C，买入 300 股 QRTV");
        assertThat(TradeRejectionMessages.noPositionToExercise(QRTV_C, AT))
                .isEqualTo("2026-11-20 16:00 时该合约（QRTV 2026-11-20 30 C）没有持仓可行权");
    }

    @Test
    void exerciseDateRules() {
        LocalDate exp = LocalDate.of(2026, 11, 20);
        Instant thu10 = Instant.parse("2026-11-19T15:00:00Z");
        assertThat(AssignmentTerms.dateProblem("行权日期", LocalDate.of(2026, 11, 21), exp, thu10))
                .isEqualTo("行权日期不能晚于到期日（2026-11-20）");
        assertThat(AssignmentTerms.dateProblem("行权日期", exp, exp, thu10)).isEqualTo("行权日期不能晚于今天");
        assertThat(AssignmentTerms.dateProblem("行权日期", LocalDate.of(2026, 11, 19), exp, thu10))
                .isEqualTo("行权日期为今天时，美东 16:00 之后才能记录");
        assertThat(AssignmentTerms.dateProblem("行权日期", LocalDate.of(2026, 11, 18), exp, thu10)).isNull();
    }
}
