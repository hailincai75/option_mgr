package com.optionmgr.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FEAT-016 pure rules: Q139 mark, display, OCC matching, merge (rules 6-8), schedule (Q270), labels (Q141, 90 minutes Q270). */
class QuoteDomainTest {

    static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static final ContractKey P1 = new ContractKey("QRTV", LocalDate.of(2026, 12, 18), d("50"), OptionType.PUT);
    static final ContractKey P4 = new ContractKey("QRTV", LocalDate.of(2026, 12, 18), d("47.5"), OptionType.PUT);

    static CboeOption opt(String code, String bid, String ask, String theo) {
        return new CboeOption(OccCode.parse(code).orElseThrow(), d(bid), d(ask), null, d(theo), d("0.3512"),
                d("-0.3021"), null, d("-0.0456"), null, null, 10L, 20L);
    }

    static CboeChain chain(String price, CboeOption... options) {
        return new CboeChain(ny("2026-11-23T09:45:00"), d(price), null, null, d("51.90"), List.of(options));
    }

    @Nested
    class Mark {
        @Test
        void ac1_midWhenBidAndAskPositive() {
            assertThat(QuotePrices.mark(d("1.20"), d("1.30"), d("1.24")))
                    .isEqualTo(new QuotePrices.Mark(d("1.250"), MarkBasis.MID));
            assertThat(QuotePrices.display(QuotePrices.mark(d("2.10"), d("2.30"), null).value())).hasToString("2.20");
            assertThat(QuotePrices.display(QuotePrices.mark(d("1.20"), d("1.31"), null).value())).hasToString("1.255");
        }

        @Test
        void ac3_theoWhenBidZero_noneWithoutTheo() {
            assertThat(QuotePrices.mark(d("0"), d("0.05"), d("0.03"))).isEqualTo(new QuotePrices.Mark(d("0.03"), MarkBasis.THEO));
            assertThat(QuotePrices.mark(d("0"), d("0.05"), null)).isEqualTo(QuotePrices.Mark.NONE);
            assertThat(QuotePrices.mark(d("0"), d("0.05"), d("0"))).isEqualTo(QuotePrices.Mark.NONE);
        }

        @Test
        void displayHasAtLeastTwoDecimals() {
            assertThat(QuotePrices.display(d("52.4"))).hasToString("52.40");
            assertThat(QuotePrices.display(d("61"))).hasToString("61.00");
            assertThat(QuotePrices.display(d("0.035"))).hasToString("0.035");
            assertThat(QuotePrices.display(d("0"))).hasToString("0.00");
            assertThat(QuotePrices.display(null)).isNull();
        }
    }

    @Nested
    class Occ {
        @Test
        void parsesTheLast15Characters() {
            OccCode c = OccCode.parse("QRTV261218P00050000").orElseThrow();
            assertThat(c.root()).isEqualTo("QRTV");
            assertThat(c.expiration()).isEqualTo(LocalDate.of(2026, 12, 18));
            assertThat(c.optionType()).isEqualTo(OptionType.PUT);
            assertThat(c.strike()).isEqualByComparingTo("50");
            assertThat(OccCode.parse("QRTV261218P00047500").orElseThrow().strike()).isEqualByComparingTo("47.5");
            assertThat(OccCode.parse("B1XYZ261113C00710000").orElseThrow().root()).isEqualTo("B1XYZ");
            assertThat(OccCode.parse("QRTV261218X00050000")).isEmpty();
            assertThat(OccCode.parse("QRTV261318P00050000")).isEmpty();
            assertThat(OccCode.parse("261218P00050000")).isEmpty();
        }

        @Test
        void ac2_matchesOnlyHeldContracts_rootEqualOrSuffix() {
            Map<ContractKey, ChainMatch> m = ChainMatcher.match("QRTV",
                    chain("52.40", opt("QRTV261218P00050000", "1.20", "1.30", "1.24"),
                            opt("QRTV261218P00055000", "3", "3.2", null),
                            opt("QRTV1261218P00047500", "1", "1.1", null)),
                    List.of(P1, P4));
            assertThat(m.get(P1)).isInstanceOf(ChainMatch.Found.class);
            assertThat(((ChainMatch.Found) m.get(P1)).option().bid()).isEqualByComparingTo("1.20");
            // the adjusted root QRTV1 does not end with QRTV
            assertThat(m.get(P4)).isInstanceOf(ChainMatch.Absent.class);
        }

        @Test
        void twoDifferentCodesForOneContractAreAmbiguous() {
            ContractKey c = new ContractKey("XYZ", LocalDate.of(2026, 11, 13), d("710"), OptionType.CALL);
            Map<ContractKey, ChainMatch> m = ChainMatcher.match("XYZ",
                    chain("700", opt("XYZ261113C00710000", "1", "2", null), opt("B1XYZ261113C00710000", "1", "2", null)),
                    List.of(c));
            assertThat(m.get(c)).isEqualTo(new ChainMatch.Ambiguous(List.of("XYZ261113C00710000", "B1XYZ261113C00710000")));
        }
    }

    @Nested
    class Merge {
        final Instant t1000 = ny("2026-11-23T10:00:00");
        final Instant t1015 = ny("2026-11-23T10:15:00");

        SymbolFetch ok(CboeChain c) {
            return new SymbolFetch("QRTV", SourceResult.SUCCESS, c, null, null, null, null);
        }

        SymbolFetch failed() {
            return new SymbolFetch("QRTV", SourceResult.HTTP_ERROR, null, "HTTP 500", SourceResult.NETWORK_ERROR, null, "x");
        }

        @Test
        void ac4_finnhubFallbackForThePrice() {
            SymbolFetch f = new SymbolFetch("IXQZ", SourceResult.HTTP_ERROR, null, "HTTP 500", SourceResult.SUCCESS,
                    new FinnhubQuote(d("61.05"), d("60.80"), null), null);
            UnderlyingState u = QuoteMerge.underlying(null, f, f.failureDetail(List.of()), t1000);
            assertThat(u.price()).isEqualByComparingTo("61.05");
            assertThat(u.source()).isEqualTo(PriceSource.FINNHUB);
            assertThat(u.quoteTime()).isEqualTo(t1000);
            assertThat(u.priceFailed()).isFalse();
            assertThat(u.chainFailed()).isTrue();
            assertThat(u.lastChainSuccessAt()).isNull();
            assertThat(f.result()).isEqualTo(SymbolResult.FALLBACK);
            assertThat(f.failureDetail(List.of())).isEqualTo("CBOE HTTP 500");
        }

        @Test
        void ac6_failureKeepsEverything() {
            UnderlyingState before = QuoteMerge.underlying(null, ok(chain("52.40")), null, t1000);
            UnderlyingState after = QuoteMerge.underlying(before, failed(), "d", t1015);
            assertThat(after.price()).isEqualByComparingTo("52.40");
            assertThat(after.quoteTime()).isEqualTo(before.quoteTime());
            assertThat(after.lastPriceSuccessAt()).isEqualTo(t1000);
            assertThat(after.lastChainSuccessAt()).isEqualTo(t1000);
            assertThat(after.priceFailed()).isTrue();
            assertThat(after.chainFailed()).isTrue();
            assertThat(after.lastAttemptAt()).isEqualTo(t1015);
        }

        @Test
        void ac5_missingFromSourceKeepsLastQuote_neverQuotedStoresNothing() {
            CboeChain c = chain("52.40", opt("QRTV261218P00050000", "1.20", "1.30", "1.24"));
            OptionState p1 = QuoteMerge.option(null, P1, new ChainMatch.Found(c.options().get(0)), c.timestamp(), t1000);
            assertThat(p1.status()).isEqualTo(ContractStatus.QUOTED);
            assertThat(p1.iv()).isEqualByComparingTo("0.3512");
            OptionState gone = QuoteMerge.option(p1, P1, new ChainMatch.Absent(), c.timestamp(), t1015);
            assertThat(gone.status()).isEqualTo(ContractStatus.MISSING_FROM_SOURCE);
            assertThat(gone.automaticMark().value()).isEqualByComparingTo("1.25");
            assertThat(gone.missingSince()).isEqualTo(t1015);
            assertThat(QuoteMerge.option(null, P4, new ChainMatch.Absent(), c.timestamp(), t1015)).isNull();
            assertThat(QuoteMerge.option(gone, P1, new ChainMatch.Ambiguous(List.of("a", "b")), c.timestamp(), t1015))
                    .isEqualTo(gone);
            // back in the chain: QUOTED again
            OptionState back = QuoteMerge.option(gone, P1, new ChainMatch.Found(c.options().get(0)), c.timestamp(), t1015);
            assertThat(back.status()).isEqualTo(ContractStatus.QUOTED);
        }

        @Test
        void ac8_manualOverridesReplacedByNextSuccessfulAutomaticValue() {
            Instant t1005 = ny("2026-11-23T10:05:00");
            UnderlyingState u = QuoteMerge.underlying(null, ok(chain("52.40")), null, t1000).withManual(d("52.00"), t1005);
            assertThat(u.effectivePrice()).isEqualByComparingTo("52.00");
            UnderlyingState afterFail = QuoteMerge.underlying(u, failed(), "d", t1015);
            assertThat(afterFail.manualPrice()).isEqualByComparingTo("52.00");
            UnderlyingState afterOk = QuoteMerge.underlying(afterFail, ok(chain("52.40")), null, t1015);
            assertThat(afterOk.manual()).isFalse();
            assertThat(afterOk.effectivePrice()).isEqualByComparingTo("52.40");

            CboeChain c = chain("52.40", opt("QRTV261218P00050000", "1.20", "1.30", "1.24"));
            OptionState o = QuoteMerge.option(null, P1, new ChainMatch.Found(c.options().get(0)), c.timestamp(), t1000)
                    .withManual(d("1.40"), t1005);
            assertThat(QuoteMerge.option(o, P1, new ChainMatch.Absent(), c.timestamp(), t1015).manualMark())
                    .isEqualByComparingTo("1.40");
            assertThat(QuoteMerge.option(o, P1, new ChainMatch.Found(c.options().get(0)), c.timestamp(), t1015).manual())
                    .isFalse();
            // an automatic quote without a mark keeps the override
            CboeOption noMark = opt("QRTV261218P00050000", "0", "0.05", null);
            assertThat(QuoteMerge.option(o, P1, new ChainMatch.Found(noMark), c.timestamp(), t1015).manualMark())
                    .isEqualByComparingTo("1.40");
        }

        @Test
        void cboeWithoutCurrentPriceUsesFinnhubButChainCounts() {
            SymbolFetch f = new SymbolFetch("QRTV", SourceResult.SUCCESS, chain(null), null, SourceResult.NO_KEY, null, null);
            UnderlyingState u = QuoteMerge.underlying(null, f, f.failureDetail(List.of()), t1000);
            assertThat(f.result()).isEqualTo(SymbolResult.SUCCESS);
            assertThat(u.priceFailed()).isTrue();
            assertThat(u.chainFailed()).isFalse();
            assertThat(f.failureDetail(List.of())).isEqualTo("CBOE no current_price; Finnhub NO_KEY");
        }
    }

    @Nested
    class Schedule {
        @Test
        void q270_hourly10To16AndClose() {
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T09:15"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T09:30"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T10:15"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T11:00"))).contains(RunTrigger.SCHEDULED);
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T15:45"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T10:00:40"))).contains(RunTrigger.SCHEDULED);
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T10:05"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T16:00"))).contains(RunTrigger.SCHEDULED);
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T16:15"))).isEmpty();
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T16:20"))).contains(RunTrigger.CLOSE);
            assertThat(FetchSchedule.slotAt(LocalDateTime.parse("2026-11-23T16:30"))).isEmpty();
            assertThat(FetchSchedule.inStartupWindow(LocalTime.of(9, 29))).isFalse();
            assertThat(FetchSchedule.inStartupWindow(LocalTime.of(16, 20))).isTrue();
            assertThat(FetchSchedule.inStartupWindow(LocalTime.of(16, 21))).isFalse();
        }
    }

    @Nested
    class Labels {
        final Instant t1000 = ny("2026-11-23T10:00:00");

        @Test
        void q270_warningAfter90MinutesInSession() {
            assertThat(QuoteLabels.warning(true, t1000, ny("2026-11-23T11:30:00"), true)).isEmpty();
            assertThat(QuoteLabels.warning(true, t1000, ny("2026-11-23T11:31:00"), true))
                    .contains("行情未更新（上次：2026-11-23 10:00 美东）");
            assertThat(QuoteLabels.rowStale(t1000)).isEqualTo("行情未更新（上次：11-23 10:00）");
            assertThat(QuoteLabels.missingFromSource(ny("2026-11-23T09:45:00")))
                    .isEqualTo("数据源已无该合约（最后报价：11-23 09:45）");
            assertThat(QuoteLabels.expired(ny("2026-12-18T15:45:00"))).isEqualTo("已到期（最后报价：12-18 15:45）");
        }

        @Test
        void ac7_noWarningOutsideSessionOrOnHolidays_reference930() {
            Instant prevClose = ny("2026-11-23T16:20:00");
            assertThat(QuoteLabels.warning(true, prevClose, ny("2026-11-23T18:00:00"), true)).isEmpty();
            assertThat(QuoteLabels.warning(true, prevClose, ny("2026-11-24T09:20:00"), true)).isEmpty();
            assertThat(QuoteLabels.warning(true, prevClose, ny("2026-11-24T11:00:00"), true)).isEmpty();
            assertThat(QuoteLabels.warning(true, prevClose, ny("2026-11-24T11:01:00"), true)).isPresent();
            assertThat(QuoteLabels.warning(true, prevClose, ny("2026-11-26T12:00:00"), false)).isEmpty();
        }

        @Test
        void ac10_neverSucceededAlwaysWarns_noTrackedNeverWarns() {
            assertThat(QuoteLabels.warning(true, null, ny("2026-11-28T12:00:00"), false))
                    .contains("行情未更新（从未成功获取）");
            assertThat(QuoteLabels.warning(false, null, ny("2026-11-23T12:00:00"), true)).isEqualTo(Optional.empty());
            assertThat(QuoteLabels.rowStale(null)).isEqualTo("行情未更新（从未成功）");
        }
    }
}
