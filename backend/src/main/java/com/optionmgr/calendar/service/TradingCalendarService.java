package com.optionmgr.calendar.service;

import com.optionmgr.calendar.domain.FetchStatus;
import com.optionmgr.calendar.domain.HolidayRules;
import com.optionmgr.calendar.domain.TradingCalendar;
import com.optionmgr.calendar.domain.TradingDayMessages;
import com.optionmgr.calendar.repository.FetchStatusEntity;
import com.optionmgr.calendar.repository.FetchStatusRepository;
import com.optionmgr.calendar.repository.MarketHolidayEntity;
import com.optionmgr.calendar.repository.MarketHolidayRepository;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-009: the current trading calendar (stored Finnhub data + NYSE rules) and the fetch status. One for all accounts (Q53). */
@Service
public class TradingCalendarService {

    private final MarketHolidayRepository holidays;
    private final FetchStatusRepository statuses;
    private final HolidayRules rules;

    public TradingCalendarService(MarketHolidayRepository holidays, FetchStatusRepository statuses, HolidayRules rules) {
        this.holidays = holidays;
        this.statuses = statuses;
        this.rules = rules;
    }

    /** A snapshot of the calendar as stored now. */
    @Transactional(readOnly = true)
    public TradingCalendar calendar() {
        return new TradingCalendar(holidays.findAll().stream().map(MarketHolidayEntity::toDomain).toList(), rules);
    }

    @Transactional(readOnly = true)
    public FetchStatus status() {
        return statuses.findById(FetchStatusEntity.ID).map(FetchStatusEntity::toDomain).orElse(FetchStatus.NEVER_ATTEMPTED);
    }

    /** Q126: a new opening trade must expire on a trading day; otherwise 422 EXPIRATION_NOT_TRADING_DAY. */
    public void requireTradingDayForOpen(LocalDate expiration) {
        calendar().nonTradingDay(expiration).ifPresent(day -> {
            throw new ApiException(ErrorCode.EXPIRATION_NOT_TRADING_DAY, TradingDayMessages.expirationNotTradingDay(day));
        });
    }
}
