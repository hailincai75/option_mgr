package com.optionmgr.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalizesToScaleTwo() {
        assertThat(Money.of("30000").toPlainString()).isEqualTo("30000.00");
        assertThat(Money.of("0.1").toPlainString()).isEqualTo("0.10");
        assertThat(Money.of("1.100").toPlainString()).isEqualTo("1.10");
    }

    @Test
    void refusesToRoundAwayCents() {
        assertThatThrownBy(() -> Money.of("100.001")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void decimalSumIsExact() {
        assertThat(Money.of("0.10").add(Money.of("0.20")).toPlainString()).isEqualTo("0.30");
    }

    @Test
    void displayUsesThousandsSeparatorsAndUnicodeMinus() {
        assertThat(Money.display(new BigDecimal("1234567.89"))).isEqualTo("1,234,567.89");
        assertThat(Money.display(new BigDecimal("1500"))).isEqualTo("1,500.00");
        assertThat(Money.display(new BigDecimal("-500.00"))).isEqualTo("−500.00");
        assertThat(Money.display(new BigDecimal("-0.01"))).isEqualTo("−0.01");
        assertThat(Money.display(Money.ZERO)).isEqualTo("0.00");
    }
}
