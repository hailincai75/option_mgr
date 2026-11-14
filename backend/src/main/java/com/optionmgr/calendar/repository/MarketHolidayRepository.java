package com.optionmgr.calendar.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketHolidayRepository extends JpaRepository<MarketHolidayEntity, LocalDate> {

    /** Q127: removes a whole year before it is replaced by a new response. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MarketHolidayEntity h where h.holidayDate >= :from and h.holidayDate <= :to")
    int deleteBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
