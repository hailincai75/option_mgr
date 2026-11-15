package com.optionmgr.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FEAT-042 rules 3-4, Q129. */
class AdhocClosureAlertsTest {

    static final LocalDate MOURNING = LocalDate.of(2025, 1, 9);
    static final LocalDate DEC4 = LocalDate.of(2026, 12, 4);
    static final Map<LocalDate, String> ACTIVE = Map.of(MOURNING, "National Mourning Day", DEC4, "National Day of Mourning");

    @Test
    void ac3_countsPositionsPerDate_withTheExactMessage() {
        List<AdhocClosureAlerts.Alert> alerts = AdhocClosureAlerts.alerts(ACTIVE,
                List.of(DEC4, DEC4, LocalDate.of(2026, 12, 18)));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).affectedPositionCount()).isEqualTo(2);
        assertThat(alerts.get(0).message()).isEqualTo("2026-12-04 临时休市（National Day of Mourning），你有 2 个持仓在该日到期，"
                + "期权通常改为前一个交易日到期，请核对并调整到期日");
    }

    @Test
    void ac6_oneLinePerDateByDateAscending() {
        List<AdhocClosureAlerts.Alert> alerts = AdhocClosureAlerts.alerts(ACTIVE, List.of(DEC4, MOURNING));
        assertThat(alerts).extracting(AdhocClosureAlerts.Alert::date).containsExactly(MOURNING, DEC4);
        assertThat(alerts).extracting(AdhocClosureAlerts.Alert::affectedPositionCount).containsExactly(1, 1);
    }

    @Test
    void q129_noAffectedPositionsNoAlert() {
        assertThat(AdhocClosureAlerts.alerts(ACTIVE, List.of(LocalDate.of(2026, 12, 18)))).isEmpty();
        assertThat(AdhocClosureAlerts.alerts(Map.of(), List.of(DEC4))).isEmpty();
    }

    // ---------------------------------------------------------------- FEAT-035

    static final List<AdhocClosureAlerts.AccountPositions> TWO_ACCOUNTS = List.of(
            new AdhocClosureAlerts.AccountPositions(1, "甲账户", List.of(DEC4, LocalDate.of(2026, 12, 18))),
            new AdhocClosureAlerts.AccountPositions(2, "乙账户", List.of(DEC4, DEC4, MOURNING)),
            new AdhocClosureAlerts.AccountPositions(3, "丙账户", List.of()));

    @Test
    void feat035_allAccounts_sumsRowsAndSplitsByAccountInTheMessage() {
        List<AdhocClosureAlerts.AccountsAlert> alerts = AdhocClosureAlerts.alerts(ACTIVE, TWO_ACCOUNTS, true);
        assertThat(alerts).extracting(AdhocClosureAlerts.AccountsAlert::date).containsExactly(MOURNING, DEC4);
        AdhocClosureAlerts.AccountsAlert dec4 = alerts.get(1);
        assertThat(dec4.affectedPositionCount()).isEqualTo(3);
        assertThat(dec4.byAccount()).containsExactly(new AdhocClosureAlerts.AccountCount(1, "甲账户", 1),
                new AdhocClosureAlerts.AccountCount(2, "乙账户", 2));
        assertThat(dec4.message()).isEqualTo("2026-12-04 临时休市（National Day of Mourning），你有 3 个持仓在该日到期"
                + "（甲账户 1 个、乙账户 2 个），期权通常改为前一个交易日到期，请核对并调整到期日");
        // one affected account: still listed (contract note 4)
        assertThat(alerts.get(0).byAccount()).containsExactly(new AdhocClosureAlerts.AccountCount(2, "乙账户", 1));
        assertThat(alerts.get(0).message()).contains("你有 1 个持仓在该日到期（乙账户 1 个），");
    }

    @Test
    void feat035_singleAccount_keepsTheFeat042MessageWithItsOwnByAccount() {
        List<AdhocClosureAlerts.AccountsAlert> alerts = AdhocClosureAlerts.alerts(ACTIVE,
                List.of(TWO_ACCOUNTS.get(1)), false);
        AdhocClosureAlerts.AccountsAlert dec4 = alerts.get(1);
        assertThat(dec4.byAccount()).containsExactly(new AdhocClosureAlerts.AccountCount(2, "乙账户", 2));
        assertThat(dec4.message()).isEqualTo(AdhocClosureAlerts.message(DEC4, "National Day of Mourning", 2));
        assertThat(AdhocClosureAlerts.alerts(ACTIVE, List.of(TWO_ACCOUNTS.get(2)), true)).isEmpty();
    }
}
