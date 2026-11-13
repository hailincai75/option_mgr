package com.optionmgr.stock.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDate;

/** FEAT-011 time rules of stock records. Pure Java, no Spring. */
public final class StockTimes {

    private StockTimes() {
    }

    /**
     * Q86: a transfer-in is recorded at 00:00 New York of its date (EDT: 04:00Z, EST: 05:00Z). Midnight always
     * exists in New York (DST switches at 02:00), so this is exact.
     */
    public static Instant transferInstant(LocalDate date) {
        return date.atStartOfDay(MarketTime.ZONE).toInstant();
    }

    /** The New York date of an instant (the date of a transfer-in). */
    public static LocalDate marketDate(Instant instant) {
        return MarketTime.toLocal(instant).toLocalDate();
    }
}
