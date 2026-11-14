package com.optionmgr.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FEAT-042 rules 1-2 and Q127 with fake NYSE rules (only Thanksgiving 2026-11-26 and Christmas each year). */
class AdhocClosureDetectorTest {

    static final LocalDate D1130 = LocalDate.of(2026, 11, 30);
    static final LocalDate D1201 = LocalDate.of(2026, 12, 1);
    static final LocalDate D1202 = LocalDate.of(2026, 12, 2);
    static final LocalDate MOURNING = LocalDate.of(2025, 1, 9);
    static final LocalDate DEC4 = LocalDate.of(2026, 12, 4);

    static final HolidayRules RULES = year -> year == 2026
            ? Map.of(LocalDate.of(2026, 11, 26), "Thanksgiving Day", LocalDate.of(2026, 12, 25), "Christmas Day")
            : Map.of(LocalDate.of(year, 12, 25), "Christmas Day");

    static ExchangeHoliday closed(String date, String name) {
        return new ExchangeHoliday(LocalDate.parse(date), name, "");
    }

    static ExchangeHoliday early(String date, String name) {
        return new ExchangeHoliday(LocalDate.parse(date), name, "09:30-13:00");
    }

    static final List<ExchangeHoliday> FIRST = List.of(
            closed("2025-01-09", "National Mourning Day"),
            closed("2025-12-25", "Christmas"),
            closed("2026-11-26", "Thanksgiving Day"),
            early("2026-11-27", "Thanksgiving Day"),
            closed("2026-12-25", "Christmas"));

    static List<ExchangeHoliday> withDec4() {
        return List.of(FIRST.get(0), FIRST.get(1), FIRST.get(2), FIRST.get(3),
                closed("2026-12-04", "National Day of Mourning"), FIRST.get(4));
    }

    @Test
    void ac1_firstFetchUsesOnlyTheRulesAndSkipsEarlyCloses() {
        assertThat(AdhocClosureDetector.changes(FIRST, List.of(), RULES, Map.of(), D1130))
                .containsExactly(AdhocClosure.detected(MOURNING, "National Mourning Day", D1130));
    }

    @Test
    void ac2_newDateInACoveredYearIsDetectedOnceAndKeepsItsDetectionDate() {
        AdhocClosure mourning = AdhocClosure.detected(MOURNING, "National Mourning Day", D1130);
        List<AdhocClosure> c = AdhocClosureDetector.changes(withDec4(), FIRST, RULES, Map.of(MOURNING, mourning), D1201);
        assertThat(c).containsExactly(AdhocClosure.detected(DEC4, "National Day of Mourning", D1201));

        AdhocClosure dec4 = c.get(0);
        assertThat(AdhocClosureDetector.changes(withDec4(), withDec4(), RULES,
                Map.of(MOURNING, mourning, DEC4, dec4), D1202)).isEmpty();
    }

    @Test
    void choice7_aRulesHolidayMissingFromTheStoredYearCountsWhenItAppears() {
        List<ExchangeHoliday> stored = List.of(closed("2026-12-25", "Christmas"));
        List<ExchangeHoliday> response = List.of(closed("2026-11-26", "Thanksgiving Day"), closed("2026-12-25", "Christmas"));
        assertThat(AdhocClosureDetector.changes(response, stored, RULES, Map.of(), D1201))
                .containsExactly(AdhocClosure.detected(LocalDate.of(2026, 11, 26), "Thanksgiving Day", D1201));
    }

    @Test
    void choice3_earlyCloseBecomingFullDayIsDetected_andBackToEarlyCloseIsRevoked() {
        List<ExchangeHoliday> earlyClose = List.of(early("2026-11-27", "Thanksgiving Day"));
        List<ExchangeHoliday> fullDay = List.of(closed("2026-11-27", "Thanksgiving Day"));
        LocalDate d = LocalDate.of(2026, 11, 27);
        List<AdhocClosure> c = AdhocClosureDetector.changes(fullDay, earlyClose, RULES, Map.of(), D1201);
        assertThat(c).containsExactly(AdhocClosure.detected(d, "Thanksgiving Day", D1201));
        assertThat(AdhocClosureDetector.changes(earlyClose, fullDay, RULES, Map.of(d, c.get(0)), D1202))
                .containsExactly(new AdhocClosure(d, "Thanksgiving Day", D1201, AdhocClosureStatus.REVOKED, D1202));
    }

    @Test
    void weekendsAreNeverRecorded() {
        List<ExchangeHoliday> response = List.of(closed("2026-12-05", "Saturday Thing"), closed("2026-12-25", "Christmas"));
        assertThat(AdhocClosureDetector.changes(response, List.of(), RULES, Map.of(), D1201)).isEmpty();
    }

    @Test
    void ac8_removedDateIsRevoked_otherYearsUntouched() {
        AdhocClosure mourning = AdhocClosure.detected(MOURNING, "National Mourning Day", D1130);
        AdhocClosure dec4 = AdhocClosure.detected(DEC4, "National Day of Mourning", D1201);
        // the response contains only 2026: 2025 records are kept (Q127)
        List<ExchangeHoliday> only2026 = List.of(closed("2026-11-26", "Thanksgiving Day"), closed("2026-12-25", "Christmas"));
        assertThat(AdhocClosureDetector.changes(only2026, withDec4(), RULES, Map.of(MOURNING, mourning, DEC4, dec4), D1202))
                .containsExactly(new AdhocClosure(DEC4, "National Day of Mourning", D1201, AdhocClosureStatus.REVOKED, D1202));
    }

    @Test
    void choice1_revokedDateReappearing_isActiveAgainWithTheOriginalDetectionDate() {
        AdhocClosure revoked = new AdhocClosure(DEC4, "National Day of Mourning", D1201, AdhocClosureStatus.REVOKED, D1202);
        assertThat(AdhocClosureDetector.changes(withDec4(), FIRST, RULES, Map.of(DEC4, revoked), LocalDate.of(2026, 12, 3)))
                .contains(new AdhocClosure(DEC4, "National Day of Mourning", D1201, AdhocClosureStatus.ACTIVE, null));
    }

    @Test
    void choice2_eventNameFollowsTheLatestResponse() {
        AdhocClosure dec4 = AdhocClosure.detected(DEC4, "National Day of Mourning", D1201);
        List<ExchangeHoliday> renamed = List.of(closed("2026-12-04", "Day of Mourning"), closed("2026-12-25", "Christmas"));
        assertThat(AdhocClosureDetector.changes(renamed, renamed, RULES, Map.of(DEC4, dec4), D1202))
                .containsExactly(AdhocClosure.detected(DEC4, "Day of Mourning", D1201));
    }
}
