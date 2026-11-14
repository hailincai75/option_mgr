package com.optionmgr.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.trade.domain.CoverageTimeline.CallLot;
import com.optionmgr.trade.domain.CoverageTimeline.Key;
import com.optionmgr.trade.domain.CoverageTimeline.ShareMove;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-039 coverage timeline (pure domain). Fictional ticker. */
class CoverageTimelineTest {

    private static Key key(String utc, long seq) {
        return new Key(Instant.parse(utc), seq);
    }

    @Test
    void stateBeforeAKeyCountsOnlyEarlierEventsAndCheckedLots() {
        CoverageTimeline t = new CoverageTimeline(
                List.of(new ShareMove(key("2026-11-02T04:00:00Z", 1), 200)),
                List.of(new CallLot(2, key("2026-11-03T15:00:00Z", 2), 100, null, true),
                        new CallLot(3, key("2026-11-03T16:00:00Z", 3), 100, null, false)));
        CoverageTimeline.State s = t.before(key("2026-11-04T15:00:00Z", 4), CoverageTimeline.checked());
        assertThat(s.held()).isEqualTo(200);
        assertThat(s.covered()).isEqualTo(100);
        assertThat(s.available()).isEqualTo(100);
    }

    @Test
    void sameInstantLaterEntryDoesNotCountBeforeAnEarlierEntry() {
        // STO (seq 1) and BUY (seq 2) at the same instant: the buy is not held at the STO
        Instant at = Instant.parse("2026-11-03T15:00:00Z");
        CoverageTimeline t = new CoverageTimeline(List.of(new ShareMove(new Key(at, 2), 100)),
                List.of(new CallLot(1, new Key(at, 1), 100, null, true)));
        assertThat(t.before(new Key(at, 1), CoverageTimeline.checkedExcept(1)).held()).isZero();
        assertThat(t.firstShortfall(CoverageTimeline.checked(), at)).hasValueSatisfying(sf -> {
            assertThat(sf.at()).isEqualTo(at);
            assertThat(sf.required()).isEqualTo(100);
            assertThat(sf.held()).isZero();
        });
    }

    @Test
    void releaseAndDeliveredSharesAtTheSameKeyDoNotCreateAShortfall() {
        // call assignment: ASSIGN_SELL -100 and the lot's release share one key
        Key assigned = key("2026-12-18T21:00:00Z", 5);
        CoverageTimeline t = new CoverageTimeline(
                List.of(new ShareMove(key("2026-11-02T05:00:00Z", 1), 200), new ShareMove(assigned, -100)),
                List.of(new CallLot(2, key("2026-11-03T15:00:00Z", 2), 100, null, true),
                        new CallLot(3, key("2026-11-04T15:00:00Z", 3), 100, assigned, true)));
        assertThat(t.firstShortfall(CoverageTimeline.checked(), Instant.EPOCH)).isEmpty();
        assertThat(t.current(CoverageTimeline.checked())).isEqualTo(new CoverageTimeline.State(100, 100));
    }

    @Test
    void shortfallBeforeFromIsIgnored() {
        CoverageTimeline t = new CoverageTimeline(List.of(new ShareMove(key("2026-11-05T15:00:00Z", 2), 100)),
                List.of(new CallLot(1, key("2026-11-03T15:00:00Z", 1), 100, null, true)));
        assertThat(t.firstShortfall(CoverageTimeline.checked(), Instant.parse("2026-11-04T00:00:00Z"))).isEmpty();
        assertThat(t.firstShortfall(CoverageTimeline.checked(), Instant.EPOCH)).isPresent();
    }

    @Test
    void inclusionTakesWholeLotsInOpenOrderAndSkipsClosedOnes() {
        // 200 shares from 11-01; legacy lots: A 2 contracts (200) at 11-03, B 1 contract (100) at 11-04, C closed
        CoverageTimeline t = new CoverageTimeline(List.of(new ShareMove(key("2026-11-01T04:00:00Z", 1), 200)),
                List.of(new CallLot(10, key("2026-11-03T15:00:00Z", 2), 200, null, false),
                        new CallLot(11, key("2026-11-04T15:00:00Z", 3), 100, null, false),
                        new CallLot(12, key("2026-11-02T15:00:00Z", 4), 100, key("2026-11-02T16:00:00Z", 5),
                                false)));
        assertThat(t.inclusions()).containsExactly(10L);
    }

    @Test
    void legacyShortfallIsTheFirstPointTheLotWouldNotBeCovered() {
        // AC7: L1 2 contracts at 11-03 10:00, transfer-in 200 on 11-04, checked 12 C 100 at 11-10 11:00
        CoverageTimeline t = new CoverageTimeline(
                List.of(new ShareMove(key("2026-11-04T05:00:00Z", 3), 200),
                        new ShareMove(key("2026-11-10T15:00:00Z", 1), 100)),
                List.of(new CallLot(1, key("2026-11-03T15:00:00Z", 0), 200, null, false),
                        new CallLot(2, key("2026-11-10T16:00:00Z", 2), 100, null, true)));
        assertThat(t.inclusions()).isEmpty();
        CoverageTimeline.Shortfall sf = t.legacyShortfall(t.lot(1).orElseThrow()).orElseThrow();
        assertThat(sf.at()).isEqualTo(Instant.parse("2026-11-03T15:00:00Z"));
        assertThat(sf.held()).isZero();
        assertThat(t.current(CoverageTimeline.checked())).isEqualTo(new CoverageTimeline.State(300, 100));
    }

    @Test
    void messagesFollowRule7() {
        assertThat(CoverageMessages.callNotCovered(200, "IXQZ", new CoverageTimeline.State(200, 100)))
                .isEqualTo("需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）");
        assertThat(CoverageMessages.callNotCovered(100, "IXQZ", new CoverageTimeline.State(0, 0)))
                .isEqualTo("需要 100 股 IXQZ，可用 0 股（持有 0 股）");
        assertThat(CoverageMessages.saleOfCoveredShares(100, new CoverageTimeline.State(200, 200)))
                .isEqualTo("卖出 100 股超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call");
        assertThat(CoverageMessages.changeUncovers("删除", "IXQZ",
                new CoverageTimeline.Shortfall(Instant.parse("2026-11-05T17:00:00Z"), 200, 100)))
                .isEqualTo("删除后 2026-11-05 12:00 起 IXQZ 不足以覆盖卖出的 call：需要 200 股，持有 100 股");
    }
}
