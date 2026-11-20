package com.optionmgr.quote.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** FEAT-016 Q141: row labels and the top warning (texts shown verbatim by the frontend). Pure. */
public final class QuoteLabels {

    /** FEAT-052 Q270: 90 minutes (was 30, Q141). */
    public static final Duration STALE_AFTER = Duration.ofMinutes(90);

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private QuoteLabels() {
    }

    /** {@code 行情未更新（上次：11-23 10:00）}, or {@code 行情未更新（从未成功）} without a previous success. */
    public static String rowStale(Instant lastSuccessAt) {
        return lastSuccessAt == null ? "行情未更新（从未成功）" : "行情未更新（上次：" + shortTime(lastSuccessAt) + "）";
    }

    /** {@code 数据源已无该合约（最后报价：11-23 09:45）}. */
    public static String missingFromSource(Instant quoteTime) {
        return "数据源已无该合约（最后报价：" + shortTime(quoteTime) + "）";
    }

    /** Q151: an expired, no longer fetched contract that had a quote: {@code 已到期（最后报价：12-18 15:45）}. */
    public static String expired(Instant quoteTime) {
        return "已到期（最后报价：" + shortTime(quoteTime) + "）";
    }

    /** New York {@code MM-dd HH:mm}. */
    public static String shortTime(Instant t) {
        return SHORT.format(MarketTime.toLocal(t));
    }

    /**
     * The top warning:
     * <ol>
     *   <li>tracked symbols but never a successful run: always {@code 行情未更新（从未成功获取）};</li>
     *   <li>on a trading day between 09:30 and 16:00 New York, more than 90 minutes (Q270) since max(last success, 09:30
     *       today): {@code 行情未更新（上次：2026-11-23 10:00 美东）};</li>
     *   <li>otherwise none.</li>
     * </ol>
     */
    public static Optional<String> warning(boolean hasTrackedSymbols, Instant lastSuccessAt, Instant now,
                                           boolean tradingDay) {
        if (!hasTrackedSymbols) {
            return Optional.empty();
        }
        if (lastSuccessAt == null) {
            return Optional.of("行情未更新（从未成功获取）");
        }
        LocalDateTime local = MarketTime.toLocal(now);
        if (!tradingDay || local.toLocalTime().isBefore(FetchSchedule.OPEN)
                || local.toLocalTime().isAfter(FetchSchedule.LAST_INTRADAY)) {
            return Optional.empty();
        }
        Instant open = MarketTime.toInstant(local.toLocalDate().atTime(FetchSchedule.OPEN));
        Instant reference = lastSuccessAt.isAfter(open) ? lastSuccessAt : open;
        if (Duration.between(reference, now).compareTo(STALE_AFTER) <= 0) {
            return Optional.empty();
        }
        return Optional.of("行情未更新（上次：" + MarketTime.displayMinute(lastSuccessAt) + " 美东）");
    }
}
