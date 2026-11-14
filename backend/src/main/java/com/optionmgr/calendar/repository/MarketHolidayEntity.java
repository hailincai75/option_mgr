package com.optionmgr.calendar.repository;

import com.optionmgr.calendar.domain.ExchangeHoliday;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** FEAT-009: one stored Finnhub market-holiday entry (V13). */
@Entity
@Table(name = "market_holiday")
public class MarketHolidayEntity {

    @Id
    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "event_name", nullable = false, length = 200)
    private String eventName;

    @Column(name = "trading_hour", length = 50)
    private String tradingHour;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected MarketHolidayEntity() {
    }

    public MarketHolidayEntity(ExchangeHoliday h, Instant fetchedAt) {
        this.holidayDate = h.date();
        this.eventName = h.eventName();
        this.tradingHour = h.tradingHour();
        this.fetchedAt = fetchedAt;
    }

    public ExchangeHoliday toDomain() {
        return new ExchangeHoliday(holidayDate, eventName, tradingHour);
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }
}
