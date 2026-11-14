package com.optionmgr.calendar.service;

import com.optionmgr.calendar.domain.HolidayRules;
import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayCalendar;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import java.time.LocalDate;
import java.time.Year;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * FEAT-009: NYSE full-day closures from Jollyday's built-in NYSE calendar (offline rules), at their observed dates
 * (e.g. July 4 on a Saturday is closed on Friday July 3). Names are Jollyday's English (root bundle) descriptions,
 * e.g. {@code Good Friday}. Cached per year.
 *
 * <p>FEAT-042 Q134: Jollyday does not expose its rule definitions (validFrom / validTo) through the public API, so a
 * holiday counts as <b>recurring</b> when a holiday with the same properties key (e.g. {@code CHRISTMAS},
 * {@code christian.GOOD_FRIDAY}) also occurs in the previous or the next year. One-time special days are defined in
 * Jollyday's NYSE file with {@code validFrom == validTo} (e.g. {@code NATIONAL_DAY_OF_MOURNING_CARTER} 2025), so they
 * occur in a single year only. Pinned by {@code JollydayHolidayRulesTest}.
 */
@Component
public class JollydayHolidayRules implements HolidayRules {

    private record Entry(String name, String key) {
    }

    private final HolidayManager manager = HolidayManager.getInstance(ManagerParameters.create(HolidayCalendar.NYSE));
    private final Map<Integer, Map<LocalDate, Entry>> cache = new ConcurrentHashMap<>();

    @Override
    public Map<LocalDate, String> closures(int year) {
        Map<LocalDate, String> out = new TreeMap<>();
        entries(year).forEach((d, e) -> out.put(d, e.name()));
        return Map.copyOf(out);
    }

    @Override
    public Map<LocalDate, String> recurringClosures(int year) {
        Set<String> neighbourKeys = new HashSet<>();
        entries(year - 1).values().forEach(e -> neighbourKeys.add(e.key()));
        entries(year + 1).values().forEach(e -> neighbourKeys.add(e.key()));
        Map<LocalDate, String> out = new TreeMap<>();
        entries(year).forEach((d, e) -> {
            if (neighbourKeys.contains(e.key())) {
                out.put(d, e.name());
            }
        });
        return Map.copyOf(out);
    }

    private Map<LocalDate, Entry> entries(int year) {
        return cache.computeIfAbsent(year, this::load);
    }

    private Map<LocalDate, Entry> load(int year) {
        Map<LocalDate, Entry> closures = new TreeMap<>();
        for (Holiday h : manager.getHolidays(Year.of(year))) {
            LocalDate date = h.getObservedDate().orElse(h.getActualDate());
            if (date.getYear() == year) {
                closures.putIfAbsent(date, new Entry(h.getDescription(Locale.ROOT), h.getPropertiesKey()));
            }
        }
        return Map.copyOf(closures);
    }
}
