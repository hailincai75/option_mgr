package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OpenTradeTermsTest {

    private static final LocalDate EXP = LocalDate.parse("2026-12-18");

    private static OpenTradeTerms terms(String symbol, String strike, OptionType type, TradeAction action,
                                        int contracts, int multiplier, String net) {
        return new OpenTradeTerms(new ContractKey(symbol, EXP, new BigDecimal(strike), type), action, contracts,
                multiplier, new BigDecimal(net));
    }

    @Test
    void stoPutReceivesPremiumAndFreezesCollateral() {
        OpenTradeTerms t = terms("AAPL", "200", OptionType.PUT, TradeAction.STO, 1, 100, "349.35");
        assertThat(t.cashChange()).isEqualByComparingTo("349.35");
        assertThat(t.frozenChange()).hasToString("20000.00");
        assertThat(t.description()).isEqualTo("STO 1 张 AAPL 2026-12-18 200 P");
    }

    @Test
    void decimalStrikeAndSeveralContracts() {
        OpenTradeTerms t = terms("F", "12.5", OptionType.PUT, TradeAction.STO, 2, 100, "48.70");
        assertThat(t.frozenChange()).hasToString("2500.00");
        assertThat(t.contract().label()).isEqualTo("F 2026-12-18 12.5 P");
    }

    @Test
    void btoPaysAndFreezesNothing() {
        OpenTradeTerms t = terms("AAPL", "210", OptionType.CALL, TradeAction.BTO, 1, 100, "512.65");
        assertThat(t.cashChange()).hasToString("-512.65");
        assertThat(t.frozenChange()).hasToString("0.00");
        assertThat(t.description()).isEqualTo("BTO 1 张 AAPL 2026-12-18 210 C");
    }

    @Test
    void btoPutFreezesNothing() {
        assertThat(terms("AAPL", "205", OptionType.PUT, TradeAction.BTO, 1, 100, "300").frozenChange())
                .hasToString("0.00");
    }

    @Test
    void nonStandardMultiplier() {
        OpenTradeTerms t = terms("XYZ", "200", OptionType.PUT, TradeAction.STO, 1, 10, "34.35");
        assertThat(t.frozenChange()).hasToString("2000.00");
        assertThat(t.description()).isEqualTo("STO 1 张 XYZ 2026-12-18 200 P（multiplier 10）");
    }

    @Test
    void collateralIsRoundedUpToTheCent() {
        assertThat(terms("X", "12.345", OptionType.PUT, TradeAction.STO, 1, 1, "1").collateral())
                .hasToString("12.35");
        assertThat(terms("X", "12.341", OptionType.PUT, TradeAction.STO, 1, 1, "1").collateral())
                .hasToString("12.35");
        assertThat(terms("X", "12.345", OptionType.PUT, TradeAction.STO, 1, 100, "1").collateral())
                .hasToString("1234.50");
    }

    @Test
    void strikeIsComparedByValue() {
        assertThat(new ContractKey("AAPL", EXP, new BigDecimal("200.000"), OptionType.PUT))
                .isEqualTo(new ContractKey("AAPL", EXP, new BigDecimal("200"), OptionType.PUT));
        assertThat(ContractKey.strikeText(new BigDecimal("200.000"))).isEqualTo("200");
        assertThat(ContractKey.strikeText(new BigDecimal("12.500"))).isEqualTo("12.5");
    }

    @Test
    void closingActionsAreNotOpens() {
        assertThatThrownBy(() -> terms("AAPL", "200", OptionType.PUT, TradeAction.BTC, 1, 100, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void symbolNormalization() {
        assertThat(Symbols.normalize(" aapl ")).isEqualTo("AAPL");
        assertThat(Symbols.normalize("brk.b")).isEqualTo("BRK.B");
        assertThat(Symbols.normalize("AA PL")).isEqualTo("AA PL");
        assertThat(Symbols.normalize("ßap")).isEqualTo("ßap");
        assertThat(Symbols.normalize("   ")).isEmpty();
        assertThat(Symbols.normalize(null)).isNull();
    }
}
