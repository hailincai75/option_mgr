package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.AdhocClosureDetector;
import com.optionmgr.calendar.domain.ExchangeHoliday;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FEAT-042 rule 1 baseline: Jollyday's NYSE rules (jollyday 2.22.1) against the full-day closures of the Finnhub US
 * data for 2023-2027 (fixture {@code finnhub/market-holiday-us-2023-2027.json}, built from the published NYSE
 * calendar: 10 regular holidays a year incl. Juneteenth and observed dates, early closes, plus 2025-01-09 National
 * Mourning Day). If they disagreed, the "rules do not produce it" condition (and choice 7) would raise false ad-hoc
 * closures.
 *
 * <p>jollyday 2.22.1 contains 2025-01-09 ("National Day of Mourning (death of President Jimmy Carter)") as a
 * one-time special day; by Q134 only recurring holidays count as "produced by the rules", so the first fetch of
 * the real calendar records exactly 2025-01-09 and nothing else. Never calls the real Finnhub.
 */
class JollydayFinnhubAgreementTest {

    private static final LocalDate MOURNING_DAY = LocalDate.of(2025, 1, 9);

    private final JollydayHolidayRules rules = new JollydayHolidayRules();

    static List<ExchangeHoliday> fixture() throws Exception {
        try (InputStream in = JollydayFinnhubAgreementTest.class
                .getResourceAsStream("/finnhub/market-holiday-us-2023-2027.json")) {
            if (in == null) {
                throw new IOException("fixture missing");
            }
            return new FinnhubHolidayParser(new ObjectMapper()).parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Set<LocalDate> fullDay(List<ExchangeHoliday> data, int year) {
        return data.stream().filter(ExchangeHoliday::closedAllDay).map(ExchangeHoliday::date)
                .filter(d -> d.getYear() == year).collect(Collectors.toCollection(TreeSet::new));
    }

    @ParameterizedTest
    @ValueSource(ints = {2023, 2024, 2025, 2026, 2027})
    void jollydayMatchesEveryFinnhubFullDayClosure(int year) throws Exception {
        Set<LocalDate> finnhub = fullDay(fixture(), year);
        assertThat(finnhub).as("fixture %d", year).hasSize(year == 2025 ? 11 : 10);
        assertThat(new TreeSet<>(rules.closures(year).keySet())).as("Jollyday NYSE %d", year)
                .containsExactlyElementsOf(finnhub);
    }

    @Test
    void q134_mourningDayIsInJollydayButOnlyAsAOneTimeDay() {
        assertThat(rules.closures(2025).get(MOURNING_DAY)).startsWith("National Day of Mourning");
        assertThat(rules.recurringClosures(2025)).doesNotContainKey(MOURNING_DAY);
        assertThat(rules.closures(2026)).doesNotContainKey(LocalDate.of(2026, 12, 4));
    }

    @Test
    void firstFetchOfTheRealCalendarRecordsExactlyTheMourningDay() throws Exception {
        assertThat(AdhocClosureDetector.changes(fixture(), List.of(), rules, Map.of(), LocalDate.of(2026, 11, 30)))
                .containsExactly(AdhocClosure.detected(MOURNING_DAY, "National Mourning Day", LocalDate.of(2026, 11, 30)));
    }

    @Test
    void refetchOfTheSameDataDetectsNothingNew() throws Exception {
        List<ExchangeHoliday> data = fixture();
        AdhocClosure recorded = AdhocClosure.detected(MOURNING_DAY, "National Mourning Day", LocalDate.of(2026, 11, 30));
        assertThat(AdhocClosureDetector.changes(data, data, rules, Map.of(MOURNING_DAY, recorded),
                LocalDate.of(2026, 12, 1))).isEmpty();
    }

    @Test
    void juneteenthAndObservedDatesAreNotAdhoc() {
        assertThat(rules.closures(2023)).containsKey(LocalDate.of(2023, 6, 19)).containsKey(LocalDate.of(2023, 1, 2));
        assertThat(rules.closures(2027)).containsKey(LocalDate.of(2027, 6, 18)).containsKey(LocalDate.of(2027, 7, 5))
                .containsKey(LocalDate.of(2027, 12, 24));
    }

    @Test
    void aNewClosureInTheRealCalendarIsTheOnlyDetection() throws Exception {
        List<ExchangeHoliday> stored = fixture();
        List<ExchangeHoliday> next = new java.util.ArrayList<>(stored);
        next.add(new ExchangeHoliday(LocalDate.of(2026, 12, 4), "National Day of Mourning", ""));
        AdhocClosure recorded = AdhocClosure.detected(MOURNING_DAY, "National Mourning Day", LocalDate.of(2026, 11, 30));
        assertThat(AdhocClosureDetector.changes(next, stored, rules, Map.of(MOURNING_DAY, recorded),
                LocalDate.of(2026, 12, 1)))
                .containsExactly(AdhocClosure.detected(LocalDate.of(2026, 12, 4), "National Day of Mourning",
                        LocalDate.of(2026, 12, 1)));
    }
}
