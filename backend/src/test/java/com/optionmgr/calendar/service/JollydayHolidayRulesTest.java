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
    void q134_mourningDay2025IsOneTime_stillAClosure() {
        assertThat(rules.closures(2025)).containsKey(LocalDate.of(2025, 1, 9));
        assertThat(rules.recurringClosures(2025)).doesNotContainKey(LocalDate.of(2025, 1, 9));
        // other one-time days of the NYSE file
        assertThat(rules.recurringClosures(2018)).doesNotContainKey(LocalDate.of(2018, 12, 5));
        assertThat(rules.recurringClosures(2012)).doesNotContainKey(LocalDate.of(2012, 10, 29));
    }

    @Test
    void q134_everyRegularHoliday2023To2027IsRecurring() {
        for (int year = 2023; year <= 2027; year++) {
            Map<LocalDate, String> recurring = rules.recurringClosures(year);
            Map<LocalDate, String> all = new java.util.HashMap<>(rules.closures(year));
            all.remove(LocalDate.of(2025, 1, 9));
            assertThat(recurring).as("%d", year).isEqualTo(all).hasSize(10);
        }
        // observed dates
        assertThat(rules.recurringClosures(2023)).containsKey(LocalDate.of(2023, 1, 2));
        assertThat(rules.recurringClosures(2026)).containsKey(LocalDate.of(2026, 7, 3));
        assertThat(rules.recurringClosures(2027)).containsKeys(LocalDate.of(2027, 6, 18), LocalDate.of(2027, 7, 5),
                LocalDate.of(2027, 12, 24));
        // Juneteenth's first year (validFrom 2022) is recurring too
        assertThat(rules.recurringClosures(2022)).containsKey(LocalDate.of(2022, 6, 20));
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
