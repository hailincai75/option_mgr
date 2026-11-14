package com.optionmgr.calendar.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * FEAT-042 rules 1-2 and Q127 (pure Java): what one successful Finnhub fetch changes in the ad-hoc closure records.
 *
 * <p>A full-day closure ({@code tradingHour} empty) on a weekday in the response is ad-hoc when it was a trading day
 * before this fetch: it is not a recurring annual NYSE holiday (Q134: one-time special days of the rules, such as
 * 2025-01-09, do not count), or the data stored before the merge covered its year without
 * it as a full-day closure (missing, or only an early close). On the first fetch nothing is stored, so only the rules
 * decide. An already recorded date is not recorded again ({@code detectedOn} kept); its eventName follows the
 * response; a REVOKED one that reappears becomes ACTIVE again. An ACTIVE closure whose year is in the response but
 * which is no longer a full-day closure there is REVOKED; years absent from the response keep their records (Q127).
 */
public final class AdhocClosureDetector {

    private AdhocClosureDetector() {
    }

    /**
     * @param response     parsed entries of this successful response
     * @param storedBefore Finnhub entries stored before this fetch's merge
     * @param rules        NYSE rules (Jollyday)
     * @param existing     current records by date
     * @param today        New York date of this fetch
     * @return the records to insert or update (unchanged records are not returned), by date
     */
    public static List<AdhocClosure> changes(Collection<ExchangeHoliday> response,
                                             Collection<ExchangeHoliday> storedBefore, HolidayRules rules,
                                             Map<LocalDate, AdhocClosure> existing, LocalDate today) {
        Set<Integer> storedYears = new HashSet<>();
        Set<LocalDate> storedFullDay = new HashSet<>();
        for (ExchangeHoliday h : storedBefore) {
            storedYears.add(h.date().getYear());
            if (h.closedAllDay()) {
                storedFullDay.add(h.date());
            }
        }
        Set<Integer> responseYears = new HashSet<>();
        Map<LocalDate, String> responseFullDay = new TreeMap<>();
        for (ExchangeHoliday h : response) {
            responseYears.add(h.date().getYear());
            if (h.closedAllDay() && weekday(h.date())) {
                responseFullDay.putIfAbsent(h.date(), h.eventName());
            }
        }

        Map<LocalDate, AdhocClosure> changes = new TreeMap<>();
        responseFullDay.forEach((date, name) -> {
            AdhocClosure current = existing.get(date);
            if (current != null) {
                if (!current.active() || !current.eventName().equals(name)) {
                    changes.put(date, new AdhocClosure(date, name, current.detectedOn(), AdhocClosureStatus.ACTIVE,
                            null));
                }
                return;
            }
            // Q134: one-time special days in the rules (e.g. 2025-01-09) do not count as produced by the rules
            boolean notByRules = !rules.recurringClosures(date.getYear()).containsKey(date);
            boolean newInCoveredYear = storedYears.contains(date.getYear()) && !storedFullDay.contains(date);
            if (notByRules || newInCoveredYear) {
                changes.put(date, AdhocClosure.detected(date, name, today));
            }
        });
        for (AdhocClosure c : existing.values()) {
            if (c.active() && responseYears.contains(c.date().getYear()) && !responseFullDay.containsKey(c.date())) {
                changes.put(c.date(), new AdhocClosure(c.date(), c.eventName(), c.detectedOn(),
                        AdhocClosureStatus.REVOKED, today));
            }
        }
        return new ArrayList<>(changes.values());
    }

    private static boolean weekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
