package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** FEAT-041 figures of a short-call assignment (Q8 provisional, Q99). Fictional tickers. */
class CallAssignmentTermsTest {

    private static CallAssignmentTerms terms(String strike, int contracts, int multiplier, String fee, String premium) {
        return new CallAssignmentTerms(new BigDecimal(strike), contracts, multiplier, new BigDecimal(fee),
                new BigDecimal(premium));
    }

    @Test
    void wholeAssignmentAddsThePremiumToTheProceeds() {
        CallAssignmentTerms t = terms("9", 3, 100, "0.00", "85.00");
        assertThat(t.shares()).isEqualTo(300);
        assertThat(t.saleAmount()).isEqualByComparingTo("2700.00");
        assertThat(t.saleAmount().scale()).isEqualTo(2);
        assertThat(t.proceeds()).isEqualByComparingTo("2785.00");
        assertThat(t.cashChange()).isEqualByComparingTo("2700.00");
        assertThat(t.frozenChange()).isEqualByComparingTo("0.00");
    }

    @Test
    void feeIsTakenFromProceedsAndCash() {
        CallAssignmentTerms t = terms("9", 2, 100, "5.00", "60.00");
        assertThat(t.proceeds()).isEqualByComparingTo("1855.00");
        assertThat(t.cashChange()).isEqualByComparingTo("1795.00");
        assertThat(t.availableDecrease()).isEqualByComparingTo("-1795.00");
    }

    @Test
    void q99_strikeAmountIsRoundedDownToTheCent() {
        CallAssignmentTerms t = terms("12.345", 1, 1, "0.00", "0.50");
        assertThat(t.saleAmount()).isEqualByComparingTo("12.34");
        assertThat(t.proceeds()).isEqualByComparingTo("12.84");
        assertThat(t.cashChange()).isEqualByComparingTo("12.34");
        assertThat(terms("12.345", 1, 2, "0.00", "0.50").saleAmount()).isEqualByComparingTo("24.69");
        assertThat(terms("12.349", 1, 1, "0.00", "0.50").saleAmount()).isEqualByComparingTo("12.34");
    }

    @Test
    void largeFeeMakesProceedsNotPositive() {
        CallAssignmentTerms t = terms("9", 1, 100, "925.00", "20.00");
        assertThat(t.proceeds()).isEqualByComparingTo("-5.00");
        assertThat(t.cashChange()).isEqualByComparingTo("-25.00");
        assertThat(t.availableDecrease()).isEqualByComparingTo("25.00");
    }

    @Test
    void ledgerDescription() {
        ContractKey ixqz = new ContractKey("IXQZ", LocalDate.of(2026, 11, 20), new BigDecimal("9"), OptionType.CALL);
        assertThat(CallAssignmentTerms.describe(ixqz, 3, 100)).isEqualTo("被指派 3 张 IXQZ 2026-11-20 9 C，卖出 300 股 IXQZ");
        ContractKey qfz = new ContractKey("QFZ", LocalDate.of(2026, 11, 20), new BigDecimal("12.345"), OptionType.CALL);
        assertThat(CallAssignmentTerms.describe(qfz, 1, 1))
                .isEqualTo("被指派 1 张 QFZ 2026-11-20 12.345 C（multiplier 1），卖出 1 股 QFZ");
    }
}
