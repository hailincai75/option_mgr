package com.optionmgr.quote.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * FEAT-016 Q138 (rule 5): on a trading day, a run every 15 minutes from 09:30 to 16:00 New York (both included) and
 * the close run at 16:20. The trading-day check is the caller's (FEAT-009 calendar). Pure.
 */
public final class FetchSchedule {

    public static final LocalTime OPEN = LocalTime.of(9, 30);
    public static final LocalTime LAST_INTRADAY = LocalTime.of(16, 0);
    public static final LocalTime CLOSE_RUN = LocalTime.of(16, 20);

    private FetchSchedule() {
    }

    /** The run due at this New York minute (seconds ignored), if any. */
    public static Optional<RunTrigger> slotAt(LocalDateTime newYork) {
        LocalTime t = newYork.toLocalTime().withSecond(0).withNano(0);
        if (t.equals(CLOSE_RUN)) {
            return Optional.of(RunTrigger.CLOSE);
        }
        if (!t.isBefore(OPEN) && !t.isAfter(LAST_INTRADAY) && t.getMinute() % 15 == 0) {
            return Optional.of(RunTrigger.SCHEDULED);
        }
        return Optional.empty();
    }

    /** Startup run (Q147): when the backend starts on a trading day between 09:30 and 16:20 New York. */
    public static boolean inStartupWindow(LocalTime newYork) {
        return !newYork.isBefore(OPEN) && !newYork.isAfter(CLOSE_RUN);
    }
}
