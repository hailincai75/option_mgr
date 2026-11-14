package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FEAT-009 rule 3: Jollyday's NYSE calendar as the fallback for years Finnhub does not cover. */
class JollydayHolidayRulesTest {

    private final JollydayHolidayRules rules = new JollydayHolidayRules();

    @Test
    void goodFridayAndThanksgiving2028() {
        Map<LocalDate, String> y2028 = rules.closures(2028);
        assertThat(y2028).containsEntry(LocalDate.of(2028, 4, 14), "Good Friday");
        assertThat(y2028).containsEntry(LocalDate.of(2028, 11, 23), "Thanksgiving Day");
    }

    @Test
    void observedDates() {
        // July 4 2026 is a Saturday -> Friday July 3; Juneteenth 2027 Saturday -> Friday June 18
        assertThat(rules.closures(2026)).containsKey(LocalDate.of(2026, 7, 3)).doesNotContainKey(LocalDate.of(2026, 7, 4));
        assertThat(rules.closures(2027)).containsKey(LocalDate.of(2027, 6, 18));
        // NYSE does not observe New Year's Day on a Saturday (2028-01-01): Friday 2027-12-31 stays open
        assertThat(rules.closures(2027)).doesNotContainKey(LocalDate.of(2027, 12, 31));
        assertThat(rules.closures(2028)).doesNotContainKey(LocalDate.of(2027, 12, 31));
    }

    @Test
    void a2026FullYearMatchesTheNyseSchedule() {
        assertThat(rules.closures(2026).keySet()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16),
                LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 12, 25));
    }
}
