package com.optionmgr.calendar.domain;

import java.time.LocalDate;

/**
 * FEAT-009: one non-trading day. {@code name} is the holiday name (Finnhub eventName or Jollyday name) or
 * 星期六 / 星期日; {@code source} is null for weekends.
 */
public record NonTradingDay(LocalDate date, NonTradingDayType type, String name, HolidaySource source) {
}
