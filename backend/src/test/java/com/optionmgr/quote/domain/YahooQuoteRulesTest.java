package com.optionmgr.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FEAT-052 rule 4 (Q265, Q271): VALID / LAST_ONLY / NONE, replace or keep, the mark by source, manual marks. */
class YahooQuoteRulesTest {

    static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    static Instant ny(String local) {
        return MarketTime.toInstant(LocalDateTime.parse(local));
    }

    static final LocalDate FEB19 = LocalDate.parse("2027-02-19");
    static final ContractKey P1 = new ContractKey("QRTV", FEB19, d("50"), OptionType.PUT);
    static final ContractKey P4 = new ContractKey("QRTV", FEB19, d("45"), OptionType.PUT);
    static final Instant T1000 = ny("2026-11-20T10:00:00");
    static final Instant T1600 = ny("2026-11-20T16:00:00");
    static final Instant RUN1620 = ny("2026-11-20T16:20:00");

    static YahooOption opt(String code, String bid, String ask, String last, String iv) {
        return new YahooOption(OccCode.parse(code).orElseThrow(), d(bid), d(ask), d(last), d(iv), 12L, 340L);
    }

    static YahooChain chain(Instant time, String price, YahooOption... options) {
        return new YahooChain(time, d(price), null, null, d("49.60"), List.of(FEB19), List.of(options));
    }

    static OptionState merge(OptionState before, ContractKey k, YahooOption o, YahooChain c, Instant runAt) {
        return QuoteMerge.yahooOption(before, k, new ChainMatch.FoundYahoo(o), c, BigDecimal.ZERO, runAt);
    }

    static final YahooOption P1_VALID = opt("QRTV270219P00050000", "3.90", "4.10", "3.95", "0.3000");
    static final YahooOption P1_CLOSED = opt("QRTV270219P00050000", "0", "0", "3.95", "0.00001");

    @Nested
    class Classify {
        @Test
        void validLastOnlyNone() {
            assertThat(YahooQuoteRules.classify(P1_VALID)).isEqualTo(YahooQuoteRules.Kind.VALID);
            assertThat(YahooQuoteRules.classify(opt("QRTV270219P00050000", "0", "0.05", null, null)))
                    .as("bid 0 is valid").isEqualTo(YahooQuoteRules.Kind.VALID);
            assertThat(YahooQuoteRules.classify(opt("QRTV270219P00050000", "1.00", "1.00", null, null)))
                    .isEqualTo(YahooQuoteRules.Kind.VALID);
            assertThat(YahooQuoteRules.classify(P1_CLOSED)).isEqualTo(YahooQuoteRules.Kind.LAST_ONLY);
            assertThat(YahooQuoteRules.classify(opt("QRTV270219P00050000", "1.20", "1.10", "1.15", null)))
                    .as("ask < bid").isEqualTo(YahooQuoteRules.Kind.LAST_ONLY);
            assertThat(YahooQuoteRules.classify(opt("QRTV270219P00050000", null, "1.10", null, null)))
                    .isEqualTo(YahooQuoteRules.Kind.NONE);
            assertThat(YahooQuoteRules.classify(opt("QRTV270219P00050000", "0", "0", "0", "0")))
                    .isEqualTo(YahooQuoteRules.Kind.NONE);
        }

        @Test
        void q271_markBySource() {
            assertThat(QuotePrices.mark(QuoteSource.YAHOO, d("0"), d("0.05"), null, null))
                    .isEqualTo(new QuotePrices.Mark(d("0.025"), MarkBasis.MID));
            assertThat(QuotePrices.mark(QuoteSource.YAHOO, d("3.90"), d("4.10"), d("3.95"), null).value())
                    .isEqualByComparingTo("4.00");
            assertThat(QuotePrices.mark(QuoteSource.YAHOO, d("0"), d("0"), d("1.10"), null))
                    .isEqualTo(new QuotePrices.Mark(d("1.10"), MarkBasis.LAST));
            assertThat(QuotePrices.mark(QuoteSource.YAHOO, d("0"), d("0"), d("0"), null)).isEqualTo(QuotePrices.Mark.NONE);
            // CBOE unchanged (Q139): bid 0 is not a mid, theo used
            assertThat(QuotePrices.mark(QuoteSource.CBOE, d("0"), d("0.05"), d("0.04"), d("0.03")))
                    .isEqualTo(new QuotePrices.Mark(d("0.03"), MarkBasis.THEO));
        }
    }

    @Nested
    class Merge {
        @Test
        void ac1_valid_replacesWithComputedGreeks() {
            OptionState s = merge(null, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), T1000);
            assertThat(s.source()).isEqualTo(QuoteSource.YAHOO);
            assertThat(s.automaticMark().value()).isEqualByComparingTo("4.00");
            assertThat(s.automaticMark().basis()).isEqualTo(MarkBasis.MID);
            assertThat(s.quoteTime()).isEqualTo(T1000);
            assertThat(s.iv()).isEqualByComparingTo("0.3");
            assertThat(s.delta()).isEqualByComparingTo("-0.47010736");
            assertThat(s.theo()).isNull();
            assertThat(s.rho()).isNull();
            assertThat(s.volume()).isEqualTo(12L);
        }

        @Test
        void ac6_afterCloseZeros_keepLastValidQuote() {
            OptionState valid = merge(null, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), T1000);
            OptionState kept = merge(valid, P1, P1_CLOSED, chain(T1600, "50.30", P1_CLOSED), RUN1620);
            assertThat(kept).isEqualTo(valid);
            OptionState none = merge(valid, P1, opt("QRTV270219P00050000", "0", "0", "0", "0"),
                    chain(T1600, "50.30"), RUN1620);
            assertThat(none).isEqualTo(valid);
        }

        @Test
        void ac6_neverQuoted_lastOnlyStored_noneStoresNothing() {
            YahooOption p4 = opt("QRTV270219P00045000", "0", "0", "1.10", "0");
            OptionState s = merge(null, P4, p4, chain(T1600, "50.30", p4), RUN1620);
            assertThat(s.automaticMark()).isEqualTo(new QuotePrices.Mark(d("1.10"), MarkBasis.LAST));
            assertThat(s.iv()).isEqualByComparingTo("0");
            assertThat(s.delta()).isNull();
            assertThat(s.gamma()).isNull();
            assertThat(YahooQuoteRules.usable(s)).isFalse();
            assertThat(merge(null, P4, opt("QRTV270219P00045000", "0", "0", "0", "0"), chain(T1600, "50.30"),
                    RUN1620)).isNull();
            // a later LAST_ONLY replaces a LAST-only quote; a later VALID replaces it too
            YahooOption p4b = opt("QRTV270219P00045000", "0", "0", "1.20", "0.25");
            assertThat(merge(s, P4, p4b, chain(T1600, "50.30", p4b), RUN1620).last()).isEqualByComparingTo("1.20");
            // NONE keeps the LAST quote (decision 3)
            assertThat(merge(s, P4, opt("QRTV270219P00045000", "0", "0", null, null), chain(T1600, "50.30"),
                    RUN1620)).isEqualTo(s);
        }

        @Test
        void rule9_cboeQuote_keptUntilValidYahoo() {
            OptionState cboe = new OptionState(P1, "QRTV270219P00050000", d("3.40"), d("3.60"), null, d("3.50"),
                    d("0.31"), d("-0.45"), d("0.05"), d("-0.02"), d("0.10"), d("-0.01"), 1L, 2L, T1000, T1000, null,
                    null, null, QuoteSource.CBOE);
            assertThat(YahooQuoteRules.usable(cboe)).isTrue();
            assertThat(merge(cboe, P1, P1_CLOSED, chain(T1600, "50.30", P1_CLOSED), RUN1620)).isEqualTo(cboe);
            OptionState replaced = merge(cboe, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), RUN1620);
            assertThat(replaced.source()).isEqualTo(QuoteSource.YAHOO);
            assertThat(replaced.theo()).isNull();
            // a CBOE quote without a mark (no bid / ask / theo) is replaced by LAST_ONLY
            OptionState noMark = new OptionState(P1, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, T1000, T1000, null, null, null, QuoteSource.CBOE);
            assertThat(YahooQuoteRules.usable(noMark)).isFalse();
            assertThat(merge(noMark, P1, P1_CLOSED, chain(T1600, "50.30", P1_CLOSED), RUN1620).source())
                    .isEqualTo(QuoteSource.YAHOO);
        }

        @Test
        void q148_manualMark_removedOnlyByValidReplacement() {
            OptionState valid = merge(null, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), T1000)
                    .withManual(d("4.20"), T1000);
            assertThat(merge(valid, P1, P1_CLOSED, chain(T1600, "50.30", P1_CLOSED), RUN1620).manualMark())
                    .isEqualByComparingTo("4.20");
            assertThat(merge(valid, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), RUN1620).manual()).isFalse();
            OptionState manualOnly = OptionState.empty(P4).withManual(d("1.00"), T1000);
            YahooOption p4 = opt("QRTV270219P00045000", "0", "0", "1.10", "0");
            OptionState lastOnly = merge(manualOnly, P4, p4, chain(T1600, "50.30", p4), RUN1620);
            assertThat(lastOnly.quoted()).isTrue();
            assertThat(lastOnly.manualMark()).as("LAST_ONLY keeps the manual mark").isEqualByComparingTo("1.00");
        }

        @Test
        void q143_absentMarksMissing_presentClearsIt() {
            OptionState valid = merge(null, P1, P1_VALID, chain(T1000, "50.00", P1_VALID), T1000);
            OptionState gone = QuoteMerge.yahooOption(valid, P1, new ChainMatch.Absent(), chain(T1600, "50"),
                    BigDecimal.ZERO, RUN1620);
            assertThat(gone.status()).isEqualTo(ContractStatus.MISSING_FROM_SOURCE);
            OptionState back = merge(gone, P1, P1_CLOSED, chain(T1600, "50.30", P1_CLOSED), RUN1620);
            assertThat(back.status()).isEqualTo(ContractStatus.QUOTED);
            assertThat(back.quoteTime()).isEqualTo(T1000);
        }
    }

    @Test
    void ac7_symbolFetchDetailAndPriceSource() {
        SymbolFetch failed = SymbolFetch.yahoo("QRTV", SourceResult.HTTP_ERROR, null, null,
                "HTTP 403 (2026-12-18, after re-handshake)", SourceResult.SUCCESS,
                new FinnhubQuote(d("50.20"), null, null), null);
        assertThat(failed.result()).isEqualTo(SymbolResult.FALLBACK);
        assertThat(failed.priceSource()).isEqualTo(PriceSource.FINNHUB);
        assertThat(failed.failureDetail(List.of()))
                .isEqualTo("Yahoo HTTP 403 (2026-12-18, after re-handshake); Finnhub SUCCESS");
        YahooChain c = chain(T1000, "50.00");
        SymbolFetch ok = SymbolFetch.yahoo("QRTV", SourceResult.SUCCESS, java.util.Map.of(FEB19, c), c, null, null,
                null, null);
        assertThat(ok.priceSource()).isEqualTo(PriceSource.YAHOO);
        assertThat(ok.failureDetail(List.of())).isNull();
        assertThat(ok.cboeResult()).isNull();
        UnderlyingState u = QuoteMerge.underlying(null, ok, null, T1000);
        assertThat(u.source()).isEqualTo(PriceSource.YAHOO);
        assertThat(u.price()).isEqualByComparingTo("50.00");
        assertThat(u.lastYahooResult()).isEqualTo(SourceResult.SUCCESS);
    }
}
