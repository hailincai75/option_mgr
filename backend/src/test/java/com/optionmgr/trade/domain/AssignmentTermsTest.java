package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-013 assignment figures (Q8, Q74, Q96) and time rules (Q76, Q94). Fictional tickers only. */
class AssignmentTermsTest {

    private static final LocalDate EXP = LocalDate.of(2026, 11, 20);
    private static final ContractKey XYZ = new ContractKey("XYZ", EXP, new BigDecimal("45"), OptionType.PUT);

    private static AssignmentTerms terms(String strike, int contracts, int multiplier, String fee, String premium,
                                         String released) {
        return new AssignmentTerms(new BigDecimal(strike), contracts, multiplier, new BigDecimal(fee),
                new BigDecimal(premium), new BigDecimal(released));
    }

    @Test
    void stockCostIsPurchaseMinusPremiumPlusFee() {
        AssignmentTerms t = terms("45", 3, 100, "0.00", "370.00", "13500.00");
        assertThat(t.shares()).isEqualTo(300);
        assertThat(t.purchaseAmount()).isEqualByComparingTo("13500.00");
        assertThat(t.stockCost()).isEqualTo(new BigDecimal("13130.00"));
        assertThat(t.stockCostPerShare()).isEqualTo(new BigDecimal("43.7667"));
        assertThat(t.cashChange()).isEqualTo(new BigDecimal("-13500.00"));
        assertThat(t.frozenChange()).isEqualTo(new BigDecimal("-13500.00"));
        assertThat(t.availableDecrease()).isEqualByComparingTo("0.00");

        AssignmentTerms withFee = terms("45", 2, 100, "5.00", "240.00", "9000.00");
        assertThat(withFee.stockCost()).isEqualTo(new BigDecimal("8765.00"));
        assertThat(withFee.stockCostPerShare()).isEqualTo(new BigDecimal("43.8250"));
        assertThat(withFee.cashChange()).isEqualTo(new BigDecimal("-9005.00"));
        assertThat(withFee.availableDecrease()).isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void q96PurchaseAmountIsRoundedUpToTheCent() {
        assertThat(terms("12.345", 2, 1, "0.00", "1.00", "24.70").purchaseAmount()).isEqualTo(new BigDecimal("24.69"));
        AssignmentTerms one = terms("12.345", 1, 1, "0.00", "0.50", "12.35");
        assertThat(one.purchaseAmount()).isEqualTo(new BigDecimal("12.35"));
        assertThat(one.stockCost()).isEqualTo(new BigDecimal("11.85"));
        assertThat(terms("12.345", 2, 1, "0.00", "1.00", "24.70").stockCost()).isEqualTo(new BigDecimal("23.69"));
        assertThat(terms("12.345", 2, 1, "0.00", "1.00", "24.70").stockCostPerShare())
                .isEqualTo(new BigDecimal("11.8450"));
    }

    @Test
    void premiumIsTheSumOfTheCoveredLotsAndTheOptionPnlIsZero() {
        LotTrade p1 = new LotTrade(1, Instant.parse("2026-11-03T15:00:00Z"), TradeAction.STO, 2, 100,
                new BigDecimal("240.00"), new BigDecimal("9000.00"), null);
        LotTrade p2 = new LotTrade(2, Instant.parse("2026-11-05T15:00:00Z"), TradeAction.STO, 1, 100,
                new BigDecimal("130.00"), new BigDecimal("4500.00"), null);
        LotTrade asg = new LotTrade(3, Instant.parse("2026-11-20T21:00:00Z"), TradeAction.ASG, 3, 100,
                Money.ZERO, Money.ZERO, null);
        ReplayedTrade step = ContractReplay.replay(List.of(p1, p2, asg)).step(3);
        assertThat(step.isValid()).isTrue();
        CloseMatch m = step.match().orElseThrow();
        assertThat(m.realizedPnl()).isEqualTo(Money.ZERO);
        assertThat(m.releasedFrozen()).isEqualTo(new BigDecimal("13500.00"));
        AssignmentTerms t = AssignmentTerms.of(XYZ, 3, 100, Money.ZERO, m);
        assertThat(t.premium()).isEqualTo(new BigDecimal("370.00"));
        assertThat(t.stockCost()).isEqualTo(new BigDecimal("13130.00"));
    }

    @Test
    void assignmentOnALongPositionIsTheWrongSide() {
        LotTrade bto = new LotTrade(1, Instant.parse("2026-11-06T16:00:00Z"), TradeAction.BTO, 1, 100,
                new BigDecimal("-40.00"), Money.ZERO, null);
        LotTrade asg = new LotTrade(2, Instant.parse("2026-11-20T21:00:00Z"), TradeAction.ASG, 1, 100,
                Money.ZERO, Money.ZERO, null);
        assertThat(ContractReplay.replay(List.of(bto, asg)).step(2).violations())
                .containsExactly(ViolationKind.CLOSE_WRONG_DIRECTION);
    }

    @Test
    void q76RecordedAt1600NewYorkDstCorrect() {
        assertThat(AssignmentTerms.recordedAt(EXP)).isEqualTo(Instant.parse("2026-11-20T21:00:00Z"));
        assertThat(AssignmentTerms.recordedAt(LocalDate.of(2027, 3, 19))).isEqualTo(Instant.parse("2027-03-19T20:00:00Z"));
        assertThat(AssignmentTerms.recordedAt(LocalDate.of(2026, 11, 18))).isEqualTo(Instant.parse("2026-11-18T21:00:00Z"));
    }

    @Test
    void q94LatestDateIsTheExpirationOrTodayFrom1600OrYesterday() {
        assertThat(AssignmentTerms.latestDate(EXP, Instant.parse("2026-11-23T15:00:00Z"))).isEqualTo(EXP);
        assertThat(AssignmentTerms.latestDate(EXP, Instant.parse("2026-11-19T15:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(AssignmentTerms.latestDate(EXP, Instant.parse("2026-11-19T21:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(AssignmentTerms.latestDate(EXP, Instant.parse("2026-11-20T21:30:00Z"))).isEqualTo(EXP);
        assertThat(AssignmentTerms.latestDate(EXP, Instant.parse("2026-11-20T20:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void dateRules() {
        Instant thu10am = Instant.parse("2026-11-19T15:00:00Z");
        assertThat(AssignmentTerms.dateProblem(LocalDate.of(2026, 11, 21), EXP, thu10am))
                .isEqualTo("指派日期不能晚于到期日（2026-11-20）");
        assertThat(AssignmentTerms.dateProblem(EXP, EXP, thu10am)).isEqualTo("指派日期不能晚于今天");
        assertThat(AssignmentTerms.dateProblem(LocalDate.of(2026, 11, 19), EXP, thu10am))
                .isEqualTo("指派日期为今天时，美东 16:00 之后才能记录");
        assertThat(AssignmentTerms.dateProblem(LocalDate.of(2026, 11, 19), EXP, Instant.parse("2026-11-19T21:00:00Z")))
                .isNull();
        assertThat(AssignmentTerms.dateProblem(LocalDate.of(2026, 11, 18), EXP, thu10am)).isNull();
    }

    @Test
    void ledgerDescription() {
        assertThat(AssignmentTerms.describe(XYZ, 3, 100)).isEqualTo("被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ");
        ContractKey qfz = new ContractKey("QFZ", EXP, new BigDecimal("12.345"), OptionType.PUT);
        assertThat(AssignmentTerms.describe(qfz, 2, 1))
                .isEqualTo("被指派 2 张 QFZ 2026-11-20 12.345 P（multiplier 1），买入 2 股 QFZ");
    }
}
