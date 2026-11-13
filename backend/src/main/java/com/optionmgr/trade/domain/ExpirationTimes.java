package com.optionmgr.trade.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * FEAT-012 / Q83 / Q10: the two time points of an expiration date, in New York time of that day (DST-correct).
 * <ul>
 *   <li>{@link #tradingCutoff}: 16:00 - trading stops; opens and closes must not be later (exactly 16:00:00 is
 *       allowed); an expiry is recorded at this instant (Q63).</li>
 *   <li>{@link #expirableFrom}: 17:30 - the outcome is final (no more assignment); expiring is allowed from then on.</li>
 * </ul>
 * No trading calendar: weekends, holidays and early closes are not considered (FEAT-009). Pure Java.
 */
public final class ExpirationTimes {

    public static final LocalTime TRADING_CUTOFF = LocalTime.of(16, 0);
    public static final LocalTime EXPIRABLE_FROM = LocalTime.of(17, 30);

    private ExpirationTimes() {
    }

    /** 16:00 New York on the expiration date, e.g. 2026-11-20 -&gt; 2026-11-20T21:00:00Z (EST). */
    public static Instant tradingCutoff(LocalDate expirationDate) {
        return MarketTime.toInstant(expirationDate.atTime(TRADING_CUTOFF));
    }

    /** 17:30 New York on the expiration date. */
    public static Instant expirableFrom(LocalDate expirationDate) {
        return MarketTime.toInstant(expirationDate.atTime(EXPIRABLE_FROM));
    }

    /** True if a trade at {@code tradedAt} is later than the cutoff (16:00:00 itself is allowed). */
    public static boolean afterCutoff(LocalDate expirationDate, Instant tradedAt) {
        return tradedAt.isAfter(tradingCutoff(expirationDate));
    }

    /** Positions page: closing is possible before the cutoff. */
    public static boolean closable(LocalDate expirationDate, Instant now) {
        return now.isBefore(tradingCutoff(expirationDate));
    }

    /** "到期待确认": cutoff &lt;= now &lt; 17:30 - neither close nor expire. */
    public static boolean expiryPending(LocalDate expirationDate, Instant now) {
        return !closable(expirationDate, now) && !expirable(expirationDate, now);
    }

    /** Expiring is possible from 17:30 on (inclusive). */
    public static boolean expirable(LocalDate expirationDate, Instant now) {
        return !now.isBefore(expirableFrom(expirationDate));
    }

    /** "成交时间不能晚于到期日（2026-11-20）美东 16:00". */
    public static String afterCutoffMessage(LocalDate expirationDate) {
        return "成交时间不能晚于到期日（" + expirationDate + "）美东 16:00";
    }
}
