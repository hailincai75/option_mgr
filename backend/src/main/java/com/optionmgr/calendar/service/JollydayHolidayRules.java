package com.optionmgr.calendar.service;

import com.optionmgr.calendar.domain.HolidayRules;
import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayCalendar;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import java.time.LocalDate;
import java.time.Year;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * FEAT-009: NYSE full-day closures from Jollyday's built-in NYSE calendar (offline rules), at their observed dates
 * (e.g. July 4 on a Saturday is closed on Friday July 3). Names are Jollyday's English (root bundle) descriptions,
 * e.g. {@code Good Friday}. Cached per year.
 */
@Component
public class JollydayHolidayRules implements HolidayRules {

    private final HolidayManager manager = HolidayManager.getInstance(ManagerParameters.create(HolidayCalendar.NYSE));
    private final Map<Integer, Map<LocalDate, String>> cache = new ConcurrentHashMap<>();

    @Override
    public Map<LocalDate, String> closures(int year) {
        return cache.computeIfAbsent(year, this::load);
    }

    private Map<LocalDate, String> load(int year) {
        Map<LocalDate, String> closures = new TreeMap<>();
        for (Holiday h : manager.getHolidays(Year.of(year))) {
            LocalDate date = h.getObservedDate().orElse(h.getActualDate());
            if (date.getYear() == year) {
                closures.merge(date, h.getDescription(Locale.ROOT), (a, b) -> a);
            }
        }
        return Map.copyOf(closures);
    }
}
