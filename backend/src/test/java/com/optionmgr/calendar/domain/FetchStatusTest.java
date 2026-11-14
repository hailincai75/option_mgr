package com.optionmgr.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** FEAT-009 rule 6: the "休市日数据未更新" banner. */
class FetchStatusTest {

    private static final Instant NOV20_0800_NY = Instant.parse("2026-11-20T13:00:00Z");
    private static final Instant NOV21_0800_NY = Instant.parse("2026-11-21T13:00:00Z");

    @Test
    void neverAttemptedIsNotStale() {
        assertThat(FetchStatus.NEVER_ATTEMPTED.stale()).isFalse();
        assertThat(FetchStatus.NEVER_ATTEMPTED.warning()).isNull();
    }

    @Test
    void ac7_neverSucceeded() {
        FetchStatus s = FetchStatus.NEVER_ATTEMPTED.after(NOV21_0800_NY, FetchResult.NO_KEY, "ignored");
        assertThat(s.stale()).isTrue();
        assertThat(s.lastFailureDetail()).isNull();
        assertThat(s.warning()).isEqualTo("休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）");
    }

    @Test
    void ac8_failureAfterSuccessShowsLastSuccessInNewYorkTimeAndNextSuccessClearsIt() {
        FetchStatus ok = FetchStatus.NEVER_ATTEMPTED.after(NOV20_0800_NY, FetchResult.SUCCESS, null);
        assertThat(ok.stale()).isFalse();
        FetchStatus failed = ok.after(NOV21_0800_NY, FetchResult.HTTP_ERROR, "HTTP 500");
        assertThat(failed.stale()).isTrue();
        assertThat(failed.lastSuccessAt()).isEqualTo(NOV20_0800_NY);
        assertThat(failed.lastFailureDetail()).isEqualTo("HTTP 500");
        assertThat(failed.warning()).isEqualTo("休市日数据未更新（上次成功：2026-11-20 08:00 美东）");
        FetchStatus again = failed.after(Instant.parse("2026-11-22T13:00:00Z"), FetchResult.SUCCESS, null);
        assertThat(again.stale()).isFalse();
        assertThat(again.warning()).isNull();
        assertThat(again.lastFailureDetail()).isNull();
        assertThat(again.lastSuccessAt()).isEqualTo(Instant.parse("2026-11-22T13:00:00Z"));
    }
}
