package com.optionmgr.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.cash.domain.CashBalance;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-035 Q214 / Q215 / Q216: rows of several accounts and exact sums. */
class AccountRowsTest {

    @Test
    void sortsByTheEndpointOrderThenAccountId_neverMerging() {
        List<AccountRows.Row<String>> rows = List.of(
                new AccountRows.Row<>(2, "乙账户", "QRTV"),
                new AccountRows.Row<>(1, "甲账户", "QRTV"),
                new AccountRows.Row<>(2, "乙账户", "IXQZ"),
                new AccountRows.Row<>(3, "丙账户", "AAAA"));
        List<AccountRows.Row<String>> sorted = AccountRows.sorted(rows, Comparator.naturalOrder());
        assertThat(sorted).extracting(r -> r.accountId() + " " + r.value())
                .containsExactly("3 AAAA", "2 IXQZ", "1 QRTV", "2 QRTV");
        assertThat(sorted.get(2).accountName()).isEqualTo("甲账户");
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(AccountRows.sorted(List.<AccountRows.Row<String>>of(), Comparator.naturalOrder())).isEmpty();
    }

    @Test
    void cashBalancesAddExactly() {
        CashBalance a = new CashBalance(new BigDecimal("50263.05"), new BigDecimal("5000.00"));
        CashBalance b = new CashBalance(new BigDecimal("20210.00"), new BigDecimal("9500.00"));
        CashBalance sum = CashBalance.EMPTY.plus(a).plus(b);
        assertThat(sum.total()).isEqualByComparingTo("70473.05");
        assertThat(sum.total().scale()).isEqualTo(2);
        assertThat(sum.frozen().toPlainString()).isEqualTo("14500.00");
        assertThat(sum.available().toPlainString()).isEqualTo("55973.05");
    }
}
