package com.optionmgr.calendar.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FEAT-042 rules 3-4 (pure Java): one alert per ACTIVE ad-hoc closure date with at least one affected position
 * (a current option position expiring on that date), by date ascending. Nothing to dismiss (Q128); no affected
 * positions = no alert (Q129).
 */
public final class AdhocClosureAlerts {

    private AdhocClosureAlerts() {
    }

    /** One alert line; {@code message} is the full text to display (without the trailing link). */
    public record Alert(LocalDate date, String eventName, int affectedPositionCount, String message) {
    }

    /**
     * @param activeClosures      ACTIVE ad-hoc closures: date to eventName
     * @param positionExpirations expiration date of each current option position of the account (one per position)
     */
    public static List<Alert> alerts(Map<LocalDate, String> activeClosures, Collection<LocalDate> positionExpirations) {
        Map<LocalDate, Integer> counts = new TreeMap<>();
        for (LocalDate exp : positionExpirations) {
            if (activeClosures.containsKey(exp)) {
                counts.merge(exp, 1, Integer::sum);
            }
        }
        List<Alert> alerts = new ArrayList<>();
        counts.forEach((date, n) -> {
            String name = activeClosures.get(date);
            alerts.add(new Alert(date, name, n, message(date, name, n)));
        });
        return alerts;
    }

    public static String message(LocalDate date, String eventName, int count) {
        return date + " 临时休市（" + eventName + "），你有 " + count
                + " 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日";
    }
}
