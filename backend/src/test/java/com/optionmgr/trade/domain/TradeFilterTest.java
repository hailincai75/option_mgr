package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Q33: New York trade-date bounds, including DST days. */
class TradeFilterTest {

    private static TradeFilter range(String start, String end) {
        return new TradeFilter(null, null, null, null, Set.of(),
                start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end));
    }

    @Test
    void boundsAreNewYorkMidnights() {
        TradeFilter f = range("2026-11-03", "2026-11-03");
        assertThat(f.tradedFrom()).contains(Instant.parse("2026-11-03T05:00:00Z"));
        // end of 2026-11-03 New York = 2026-11-04 00:00 EST; 23:30 NY (04:30Z) is inside
        assertThat(f.tradedBefore()).contains(Instant.parse("2026-11-04T05:00:00Z"));
    }

    @Test
    void summerBoundsUseEdt() {
        TradeFilter f = range("2026-07-15", "2026-07-15");
        assertThat(f.tradedFrom()).contains(Instant.parse("2026-07-15T04:00:00Z"));
        assertThat(f.tradedBefore()).contains(Instant.parse("2026-07-16T04:00:00Z"));
    }

    @Test
    void dstSwitchDaysHave23And25Hours() {
        TradeFilter spring = range("2026-03-08", "2026-03-08");
        assertThat(spring.tradedFrom()).contains(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(spring.tradedBefore()).contains(Instant.parse("2026-03-09T04:00:00Z"));
        TradeFilter fall = range("2026-11-01", "2026-11-01");
        assertThat(fall.tradedFrom()).contains(Instant.parse("2026-11-01T04:00:00Z"));
        assertThat(fall.tradedBefore()).contains(Instant.parse("2026-11-02T05:00:00Z"));
    }

    @Test
    void openEndedAndEmpty() {
        assertThat(range("2026-11-03", null).tradedBefore()).isEmpty();
        assertThat(range(null, "2026-11-03").tradedFrom()).isEmpty();
        assertThat(TradeFilter.NONE.actions()).isEmpty();
    }

    @Test
    void strikeIsNormalizedAndStartAfterEndIsRejected() {
        TradeFilter f = new TradeFilter(null, null, new BigDecimal("200.00"), null, null, null, null);
        assertThat(f.strike()).isEqualTo(new BigDecimal("200"));
        assertThatThrownBy(() -> range("2026-11-04", "2026-11-03")).isInstanceOf(IllegalArgumentException.class);
    }
}
