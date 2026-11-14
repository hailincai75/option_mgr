package com.optionmgr.calendar.domain;

import java.time.LocalDate;
import java.util.Map;

/** FEAT-009: rule-based full-day closures (NYSE) for years Finnhub does not cover. */
public interface HolidayRules {

    /** Full-day closure dates of {@code year} (observed dates) with their names, one-time special days included. */
    Map<LocalDate, String> closures(int year);

    /**
     * FEAT-042 Q134: only the recurring annual holidays of {@code year} (observed dates), i.e. {@link #closures} without
     * one-time special days such as the 2025-01-09 National Day of Mourning. Used to decide "produced by the rules";
     * the trading calendar itself (FEAT-009, Q126) still uses {@link #closures}. Default: every closure is recurring.
     */
    default Map<LocalDate, String> recurringClosures(int year) {
        return closures(year);
    }
}
