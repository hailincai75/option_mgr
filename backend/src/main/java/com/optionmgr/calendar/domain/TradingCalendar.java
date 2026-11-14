package com.optionmgr.calendar.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * FEAT-009: the US trading calendar. A trading day is Monday-Friday and not a full-day closure. Years present in the
 * stored Finnhub data ("covered years") use only Finnhub (entries with an empty tradingHour are closures; early
 * closes are trading days, Q122); all other years use the NYSE rules. Pure Java, immutable snapshot.
 */
public final class TradingCalendar {

    private final Map<LocalDate, ExchangeHoliday> finnhub = new HashMap<>();
    private final SortedSet<Integer> coveredYears = new TreeSet<>();
    private final HolidayRules rules;

    public TradingCalendar(Collection<ExchangeHoliday> finnhubHolidays, HolidayRules rules) {
        for (ExchangeHoliday h : finnhubHolidays) {
            finnhub.put(h.date(), h);
            coveredYears.add(h.date().getYear());
        }
        this.rules = rules;
    }

    /** Years covered by Finnhub, ascending. */
    public List<Integer> coveredYears() {
        return List.copyOf(coveredYears);
    }

    /** Why {@code date} is not a trading day; empty for a trading day. Weekends win over holidays. */
    public Optional<NonTradingDay> nonTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return Optional.of(new NonTradingDay(date, NonTradingDayType.WEEKEND, "星期六", null));
        }
        if (dow == DayOfWeek.SUNDAY) {
            return Optional.of(new NonTradingDay(date, NonTradingDayType.WEEKEND, "星期日", null));
        }
        if (coveredYears.contains(date.getYear())) {
            ExchangeHoliday h = finnhub.get(date);
            return h != null && h.closedAllDay()
                    ? Optional.of(new NonTradingDay(date, NonTradingDayType.HOLIDAY, h.eventName(), HolidaySource.FINNHUB))
                    : Optional.empty();
        }
        String name = rules.closures(date.getYear()).get(date);
        return name == null
                ? Optional.empty()
                : Optional.of(new NonTradingDay(date, NonTradingDayType.HOLIDAY, name, HolidaySource.RULES));
    }

    public boolean isTradingDay(LocalDate date) {
        return nonTradingDay(date).isEmpty();
    }

    /** All non-trading days in [from, to], ascending; empty when from is after to. */
    public List<NonTradingDay> nonTradingDays(LocalDate from, LocalDate to) {
        List<NonTradingDay> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            nonTradingDay(d).ifPresent(days::add);
        }
        return days;
    }

    /**
     * Q125: trading days in (today, expiration] - today excluded, expiration included; 0 on the expiration date;
     * null once expired (expiration before today). An expiration that is itself not a trading day is counted by the
     * same formula.
     */
    public Integer remainingTradingDays(LocalDate today, LocalDate expiration) {
        if (expiration.isBefore(today)) {
            return null;
        }
        int n = 0;
        for (LocalDate d = today.plusDays(1); !d.isAfter(expiration); d = d.plusDays(1)) {
            if (isTradingDay(d)) {
                n++;
            }
        }
        return n;
    }
}
