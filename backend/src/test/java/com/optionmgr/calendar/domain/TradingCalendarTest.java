package com.optionmgr.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FEAT-009 rules 3, 4, 7 (Q122, Q125) on the pure calendar, with fixed rules standing in for Jollyday. */
class TradingCalendarTest {

    /** Rules for 2028 only: Good Friday 2028-04-14 (and anything a test adds). */
    private static final HolidayRules RULES = year -> year == 2028
            ? Map.of(LocalDate.of(2028, 4, 14), "Good Friday", LocalDate.of(2028, 11, 23), "Thanksgiving Day")
            : Map.of(LocalDate.of(year, 12, 25), "Christmas (rules)");

    /** The scenario's Finnhub data (2023-2027 covered; only the entries the tests need, plus one per year). */
    static List<ExchangeHoliday> scenarioFinnhub() {
        List<ExchangeHoliday> list = new ArrayList<>();
        list.add(new ExchangeHoliday(LocalDate.of(2023, 12, 25), "Christmas", ""));
        list.add(new ExchangeHoliday(LocalDate.of(2024, 12, 25), "Christmas", ""));
        list.add(new ExchangeHoliday(LocalDate.of(2025, 1, 9), "National Mourning Day", ""));
        list.add(new ExchangeHoliday(LocalDate.of(2026, 11, 26), "Thanksgiving Day", ""));
        list.add(new ExchangeHoliday(LocalDate.of(2026, 11, 27), "Thanksgiving Day", "09:30-13:00"));
        list.add(new ExchangeHoliday(LocalDate.of(2026, 12, 25), "Christmas", null));
        list.add(new ExchangeHoliday(LocalDate.of(2027, 3, 26), "Good Friday", ""));
        return list;
    }

    private final TradingCalendar cal = new TradingCalendar(scenarioFinnhub(), RULES);

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void ac1_remainingTradingDaysSkipWeekendsAndHolidaysAndCountEarlyCloses() {
        // today Monday 2026-11-23: 11-24, 11-25, (11-26 holiday), 11-27 early close -> 3
        assertThat(cal.remainingTradingDays(d("2026-11-23"), d("2026-11-27"))).isEqualTo(3);
        // to 12-18: 3 + Nov 30..Dec 18 (15 weekdays) = 18
        assertThat(cal.remainingTradingDays(d("2026-11-23"), d("2026-12-18"))).isEqualTo(18);
    }

    @Test
    void ac2_expirationDayIsZeroAndExpiredIsNull() {
        assertThat(cal.remainingTradingDays(d("2026-11-27"), d("2026-11-27"))).isZero();
        assertThat(cal.remainingTradingDays(d("2026-11-30"), d("2026-11-27"))).isNull();
    }

    @Test
    void ac10_nonTradingExpirationOfOldDataIsCountedByTheSameFormula() {
        // today Monday 2026-11-16, expiration Saturday 2026-11-21: 11-17..11-20 -> 4
        assertThat(cal.remainingTradingDays(d("2026-11-16"), d("2026-11-21"))).isEqualTo(4);
    }

    @Test
    void todayIsNeverCountedEvenOnAWeekendOrHoliday() {
        // today Sunday 2026-11-22: 11-23..11-25 + 11-27
        assertThat(cal.remainingTradingDays(d("2026-11-22"), d("2026-11-27"))).isEqualTo(4);
        // today the holiday itself
        assertThat(cal.remainingTradingDays(d("2026-11-26"), d("2026-11-27"))).isEqualTo(1);
    }

    @Test
    void ac4_finnhubClosureAndWeekendsInRange() {
        assertThat(cal.nonTradingDays(d("2025-01-06"), d("2025-01-12"))).containsExactly(
                new NonTradingDay(d("2025-01-09"), NonTradingDayType.HOLIDAY, "National Mourning Day", HolidaySource.FINNHUB),
                new NonTradingDay(d("2025-01-11"), NonTradingDayType.WEEKEND, "星期六", null),
                new NonTradingDay(d("2025-01-12"), NonTradingDayType.WEEKEND, "星期日", null));
    }

    @Test
    void ac3_earlyCloseIsATradingDayHolidayIsNot() {
        assertThat(cal.isTradingDay(d("2026-11-27"))).isTrue();
        assertThat(cal.isTradingDay(d("2026-11-26"))).isFalse();
        assertThat(cal.isTradingDay(d("2027-03-26"))).isFalse();
        assertThat(cal.isTradingDay(d("2027-03-25"))).isTrue();
        assertThat(cal.nonTradingDays(d("2026-11-01"), d("2026-11-30")).stream().map(NonTradingDay::date).toList())
                .contains(d("2026-11-26"), d("2026-11-28"), d("2026-11-29"))
                .doesNotContain(d("2026-11-27"), d("2026-11-25"));
    }

    @Test
    void ac5_uncoveredYearUsesRulesAndSwitchesToFinnhubOnceCovered() {
        assertThat(cal.nonTradingDays(d("2028-04-10"), d("2028-04-16"))).containsExactly(
                new NonTradingDay(d("2028-04-14"), NonTradingDayType.HOLIDAY, "Good Friday", HolidaySource.RULES),
                new NonTradingDay(d("2028-04-15"), NonTradingDayType.WEEKEND, "星期六", null),
                new NonTradingDay(d("2028-04-16"), NonTradingDayType.WEEKEND, "星期日", null));

        List<ExchangeHoliday> more = scenarioFinnhub();
        more.add(new ExchangeHoliday(d("2028-04-14"), "Good Friday", ""));
        TradingCalendar later = new TradingCalendar(more, RULES);
        assertThat(later.nonTradingDay(d("2028-04-14")).orElseThrow().source()).isEqualTo(HolidaySource.FINNHUB);
        // a covered year uses only Finnhub: the rules' Thanksgiving 2028 is no longer a closure
        assertThat(later.isTradingDay(d("2028-11-23"))).isTrue();
        assertThat(later.coveredYears()).containsExactly(2023, 2024, 2025, 2026, 2027, 2028);
    }

    @Test
    void coveredYearIgnoresRulesAndUncoveredYearIgnoresNothingFromFinnhub() {
        // 2026 is covered: the rules' Christmas closure is not consulted, Finnhub's is
        assertThat(cal.nonTradingDay(d("2026-12-25")).orElseThrow())
                .isEqualTo(new NonTradingDay(d("2026-12-25"), NonTradingDayType.HOLIDAY, "Christmas", HolidaySource.FINNHUB));
        // 2030 is not covered: rules
        assertThat(cal.nonTradingDay(d("2030-12-25")).orElseThrow().source()).isEqualTo(HolidaySource.RULES);
    }

    @Test
    void neverFetchedUsesRulesForEveryYear() {
        TradingCalendar rulesOnly = new TradingCalendar(List.of(), RULES);
        assertThat(rulesOnly.coveredYears()).isEmpty();
        assertThat(rulesOnly.nonTradingDay(d("2028-04-14")).orElseThrow().source()).isEqualTo(HolidaySource.RULES);
        assertThat(rulesOnly.isTradingDay(d("2026-11-26"))).isTrue(); // the stand-in rules have no 2026 Thanksgiving
    }

    @Test
    void weekendWinsOverAHolidayListedOnAWeekend() {
        List<ExchangeHoliday> list = scenarioFinnhub();
        list.add(new ExchangeHoliday(d("2026-07-04"), "Independence Day", ""));
        assertThat(new TradingCalendar(list, RULES).nonTradingDay(d("2026-07-04")).orElseThrow())
                .isEqualTo(new NonTradingDay(d("2026-07-04"), NonTradingDayType.WEEKEND, "星期六", null));
    }

    @Test
    void q126Message() {
        assertThat(TradingDayMessages.expirationNotTradingDay(cal.nonTradingDay(d("2027-03-26")).orElseThrow()))
                .isEqualTo("到期日 2027-03-26 不是美股交易日（Good Friday）");
        assertThat(TradingDayMessages.expirationNotTradingDay(cal.nonTradingDay(d("2026-11-21")).orElseThrow()))
                .isEqualTo("到期日 2026-11-21 不是美股交易日（星期六）");
    }
}
