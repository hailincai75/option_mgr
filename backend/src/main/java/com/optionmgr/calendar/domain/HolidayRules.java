package com.optionmgr.calendar.domain;

import java.time.LocalDate;
import java.util.Map;

/** FEAT-009: rule-based full-day closures (NYSE) for years Finnhub does not cover. */
public interface HolidayRules {

    /** Full-day closure dates of {@code year} (observed dates) with their names. */
    Map<LocalDate, String> closures(int year);
}
