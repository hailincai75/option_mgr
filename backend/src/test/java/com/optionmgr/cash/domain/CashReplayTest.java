package com.optionmgr.cash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q19 replay rule, independent of Spring and the database. Times below are New York wall-clock times. */
class CashReplayTest {

    private final List<CashMovement> ledger = new ArrayList<>();
    private long nextId = 1;

    private static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    private static BigDecimal m(String amount) {
        return Money.of(amount);
    }

    private void record(String nyTime, String cashChange) {
        record(nyTime, cashChange, "0");
    }

    private void record(String nyTime, String cashChange, String frozenChange) {
        ledger.add(new CashMovement(ny(nyTime), nextId++, m(cashChange), m(frozenChange)));
    }

    private InsertionCheck tryWithdraw(String nyTime, String amount) {
        return CashReplay.checkInsertion(ledger, CashMovement.pending(ny(nyTime), m(amount).negate(), Money.ZERO));
    }

    @Test
    void emptyLedgerIsAllZero() {
        CashBalance b = CashReplay.finalBalance(ledger);
        assertThat(b.total()).isEqualTo(m("0.00"));
        assertThat(b.frozen()).isEqualTo(m("0.00"));
        assertThat(b.available()).isEqualTo(m("0.00"));
    }

    @Test
    void withdrawalOfExactlyAvailableIsAllowedOneCentMoreIsNot() {
        record("2026-11-02T10:00", "30000.00");
        record("2026-11-03T09:30", "-2500.50");

        InsertionCheck tooMuch = tryWithdraw("2026-11-10T09:00", "27499.51");
        assertThat(tooMuch.allowed()).isFalse();
        assertThat(tooMuch.violatedAtInsertedPoint()).isTrue();
        assertThat(CashShortfallMessages.insufficient(m("27499.51"), tooMuch))
                .isEqualTo("可用现金不足：需要 27,499.51，可用 27,499.50");

        assertThat(tryWithdraw("2026-11-10T09:00", "27499.50").allowed()).isTrue();
    }

    @Test
    void backdatedWithdrawalIsRejectedWhenALaterPointGoesNegative() {
        record("2026-11-02T10:00", "10000.00");
        record("2026-11-05T10:00", "-9000.00");

        InsertionCheck check = tryWithdraw("2026-11-03T10:00", "1500.00");

        assertThat(check.allowed()).isFalse();
        assertThat(check.violatedAtInsertedPoint()).isFalse();
        assertThat(check.inserted().after().available()).isEqualTo(m("8500.00"));
        assertThat(check.firstViolation().orElseThrow().movement().occurredAt()).isEqualTo(ny("2026-11-05T10:00"));
        assertThat(check.firstViolation().orElseThrow().after().available()).isEqualTo(m("-500.00"));
        assertThat(CashShortfallMessages.insufficient(m("1500.00"), check))
                .isEqualTo("可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 −500.00");

        assertThat(tryWithdraw("2026-11-03T10:00", "1000.00").allowed()).isTrue();
    }

    @Test
    void withdrawalBeforeFirstDepositSeesZeroAvailable() {
        record("2026-11-02T10:00", "10000.00");
        record("2026-11-05T10:00", "-9000.00");

        InsertionCheck check = tryWithdraw("2026-11-01T10:00", "100.00");

        assertThat(check.violatedAtInsertedPoint()).isTrue();
        assertThat(CashShortfallMessages.insufficient(m("100.00"), check))
                .isEqualTo("可用现金不足：需要 100.00，可用 0.00");
    }

    @Test
    void sameInstantIsOrderedByEntryOrder() {
        record("2026-11-03T10:00", "1000.00");

        // Withdrawal entered after the deposit at the same instant sorts after it.
        assertThat(tryWithdraw("2026-11-03T10:00", "1000.00").allowed()).isTrue();

        List<CashMovement> sameTime = List.of(
                new CashMovement(ny("2026-11-03T10:00"), 2, m("-1000.00"), Money.ZERO),
                new CashMovement(ny("2026-11-03T10:00"), 1, m("1000.00"), Money.ZERO));
        List<ReplayStep> steps = CashReplay.replay(sameTime);
        assertThat(steps).extracting(s -> s.movement().sequence()).containsExactly(1L, 2L);
        assertThat(steps).extracting(s -> s.after().total()).containsExactly(m("1000.00"), m("0.00"));
    }

    @Test
    void withdrawalOnEmptyLedgerIsRejectedEvenIfDepositAtSameTimeComesLater() {
        InsertionCheck check = tryWithdraw("2026-11-03T10:00", "1000.00");
        assertThat(check.allowed()).isFalse();
        assertThat(CashShortfallMessages.insufficient(m("1000.00"), check))
                .isEqualTo("可用现金不足：需要 1,000.00，可用 0.00");
    }

    @Test
    void backdatedDepositRecomputesLaterRunningBalances() {
        record("2026-11-05T10:00", "10000.00");
        record("2026-11-02T10:00", "5000.00");

        List<ReplayStep> steps = CashReplay.replay(ledger);
        assertThat(steps).extracting(s -> s.movement().occurredAt())
                .containsExactly(ny("2026-11-02T10:00"), ny("2026-11-05T10:00"));
        assertThat(steps).extracting(s -> s.after().total()).containsExactly(m("5000.00"), m("15000.00"));
        assertThat(CashReplay.finalBalance(ledger).available()).isEqualTo(m("15000.00"));
    }

    @Test
    void frozenCashCannotBeWithdrawn() {
        // FEAT-001 AC14 shape: deposit 25,000, back-dated STO put (+349.35 cash, 20,000 frozen), then withdrawal.
        record("2026-11-02T10:00", "25000.00");
        record("2026-11-03T10:00", "349.35", "20000.00");

        CashBalance now = CashReplay.finalBalance(ledger);
        assertThat(now.total()).isEqualTo(m("25349.35"));
        assertThat(now.frozen()).isEqualTo(m("20000.00"));
        assertThat(now.available()).isEqualTo(m("5349.35"));

        InsertionCheck tooMuch = tryWithdraw("2026-11-05T10:00", "5500.00");
        assertThat(tooMuch.allowed()).isFalse();
        assertThat(tooMuch.firstViolation().orElseThrow().after().total()).isEqualTo(m("19849.35"));
        assertThat(tooMuch.firstViolation().orElseThrow().after().available()).isEqualTo(m("-150.65"));

        assertThat(tryWithdraw("2026-11-05T10:00", "5349.35").allowed()).isTrue();
    }

    @Test
    void laterFreezeMakesEarlierBackdatedWithdrawalFail() {
        record("2026-11-02T10:00", "25000.00");
        record("2026-11-05T10:00", "349.35", "20000.00");

        InsertionCheck check = tryWithdraw("2026-11-03T10:00", "5500.00");
        assertThat(check.allowed()).isFalse();
        assertThat(check.violatedAtInsertedPoint()).isFalse();
        assertThat(check.firstViolation().orElseThrow().movement().sequence()).isEqualTo(2L);
    }

    @Test
    void decimalSumsAreExact() {
        record("2026-11-02T10:00", "0.10");
        record("2026-11-02T10:01", "0.20");
        assertThat(CashReplay.finalBalance(ledger).total().toPlainString()).isEqualTo("0.30");
    }
}
