package com.optionmgr.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MarketTimeTest {

    @Test
    void daylightSavingTimeIsUtcMinusFour() {
        assertThat(MarketTime.toInstant(LocalDateTime.parse("2026-07-15T10:30")))
                .isEqualTo(Instant.parse("2026-07-15T14:30:00Z"));
    }

    @Test
    void standardTimeIsUtcMinusFive() {
        assertThat(MarketTime.toInstant(LocalDateTime.parse("2026-11-02T10:00")))
                .isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));
    }

    @Test
    void springForwardGapIsShiftedLaterByOneHour() {
        // 2026-03-08 02:30 does not exist in New York; java.time moves it to 03:30 EDT.
        assertThat(MarketTime.toInstant(LocalDateTime.parse("2026-03-08T02:30")))
                .isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
    }

    @Test
    void fallBackOverlapTakesEarlierOffset() {
        // 2026-11-01 01:30 happens twice; java.time picks the first one (EDT, UTC-4).
        assertThat(MarketTime.toInstant(LocalDateTime.parse("2026-11-01T01:30")))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    @Test
    void displaysNewYorkMinute() {
        assertThat(MarketTime.displayMinute(Instant.parse("2026-11-05T15:00:00Z"))).isEqualTo("2026-11-05 10:00");
        assertThat(MarketTime.displayMinute(Instant.parse("2026-07-15T14:30:00Z"))).isEqualTo("2026-07-15 10:30");
    }
}
