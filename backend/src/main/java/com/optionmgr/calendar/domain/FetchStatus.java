package com.optionmgr.calendar.domain;

import com.optionmgr.common.time.MarketTime;
import java.time.Instant;

/**
 * FEAT-009 rule 6: state of the holiday fetch. Never contains the API key.
 *
 * @param lastAttemptAt     null = never attempted
 * @param lastAttemptResult null = never attempted
 * @param lastSuccessAt     null = never succeeded
 */
public record FetchStatus(Instant lastAttemptAt, FetchResult lastAttemptResult, String lastFailureDetail,
                          Instant lastSuccessAt) {

    public static final FetchStatus NEVER_ATTEMPTED = new FetchStatus(null, null, null, null);

    /** The latest attempt did not succeed (never attempted is not stale). */
    public boolean stale() {
        return lastAttemptResult != null && lastAttemptResult != FetchResult.SUCCESS;
    }

    /** Banner text when stale, otherwise null. */
    public String warning() {
        if (!stale()) {
            return null;
        }
        if (lastSuccessAt == null) {
            return "休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）";
        }
        return "休市日数据未更新（上次成功：" + MarketTime.displayMinute(lastSuccessAt) + " 美东）";
    }

    /**
     * Status after an attempt at {@code at}: keeps the last success unless this attempt succeeded; the failure
     * detail is dropped on success and for NO_KEY.
     */
    public FetchStatus after(Instant at, FetchResult result, String failureDetail) {
        boolean ok = result == FetchResult.SUCCESS;
        return new FetchStatus(at, result, ok || result == FetchResult.NO_KEY ? null : failureDetail,
                ok ? at : lastSuccessAt);
    }
}
