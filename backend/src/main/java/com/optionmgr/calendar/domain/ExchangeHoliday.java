package com.optionmgr.calendar.domain;

import java.time.LocalDate;

/**
 * FEAT-009: one Finnhub market-holiday entry. {@code tradingHour} null = closed all day; non-null (e.g.
 * {@code 09:30-13:00}) = early close, still a trading day (Q122). A blank tradingHour is normalized to null.
 */
public record ExchangeHoliday(LocalDate date, String eventName, String tradingHour) {

    public ExchangeHoliday {
        if (tradingHour != null) {
            tradingHour = tradingHour.strip();
            if (tradingHour.isEmpty()) {
                tradingHour = null;
            }
        }
    }

    public boolean closedAllDay() {
        return tradingHour == null;
    }
}
