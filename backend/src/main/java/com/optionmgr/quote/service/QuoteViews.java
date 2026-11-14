package com.optionmgr.quote.service;

import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.domain.ContractStatus;
import com.optionmgr.quote.domain.MarkBasis;
import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.PriceSource;
import com.optionmgr.quote.domain.QuoteLabels;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.quote.domain.UnderlyingState;
import com.optionmgr.trade.domain.ContractKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/** FEAT-016 response objects (docs/api/FEAT-016.md), built from the stored states. */
public final class QuoteViews {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private QuoteViews() {
    }

    /** 「标的行情对象」: {@code underlyingQuote} of option rows, {@code quote} of stock rows. */
    public record UnderlyingQuote(String symbol, BigDecimal price, BigDecimal automaticPrice, PriceSource source,
                                  Instant quoteTime, String quoteTimeLocal, boolean manual, Instant manualEnteredAt,
                                  String manualEnteredAtLocal, boolean fetchFailed, Instant lastSuccessAt,
                                  String lastSuccessAtLocal, String staleLabel) {
    }

    /** {@code optionQuote} of option rows. */
    public record OptionQuote(BigDecimal bid, BigDecimal ask, BigDecimal mark, BigDecimal automaticMark,
                              MarkBasis markBasis, String source, Instant quoteTime, String quoteTimeLocal,
                              ContractStatus contractStatus, String contractStatusLabel, boolean manual,
                              Instant manualEnteredAt, String manualEnteredAtLocal, boolean fetchFailed,
                              Instant lastSuccessAt, String lastSuccessAtLocal, String staleLabel) {
    }

    /** {@code GET /api/market-data/status}. */
    public record Status(boolean stale, String warning, Instant lastSuccessAt, String lastSuccessAtLocal,
                         Instant lastAttemptAt, String lastAttemptAtLocal, Instant lastRefreshAt,
                         String lastRefreshAtLocal, Instant refreshAvailableAt, boolean refreshAllowed,
                         int trackedSymbolCount) {
    }

    /** All stored quotes at one moment, for building the position rows of one response. */
    /**
     * @param today          New York date of the book (Q150: contracts expiring before it are no longer fetched)
     * @param trackedSymbols symbols a run fetches now; the others never show a failure
     */
    public record QuoteBook(Map<String, UnderlyingState> underlyings, Map<ContractKey, OptionState> options,
                            Set<String> indexSymbols, java.time.LocalDate today, Set<String> trackedSymbols) {

        public UnderlyingQuote underlying(String symbol) {
            return QuoteViews.underlying(symbol, underlyings.get(symbol), indexSymbols.contains(symbol),
                    trackedSymbols.contains(symbol));
        }

        public OptionQuote option(ContractKey c) {
            return QuoteViews.option(options.get(c), underlyings.get(c.symbol()), indexSymbols.contains(c.symbol()),
                    c.expirationDate().isBefore(today));
        }

        /** FEAT-018: the stored quote of the contract (IV / Greeks included); {@code null} = no row. */
        public OptionState stored(ContractKey c) {
            return options.get(c);
        }
    }

    static UnderlyingQuote underlying(String symbol, UnderlyingState stored, boolean index, boolean tracked) {
        UnderlyingState s = stored == null ? UnderlyingState.empty(symbol) : stored;
        if (index) {
            // never fetched (Q149): only a manual price
            s = UnderlyingState.empty(symbol).withManual(s.manualPrice(), s.manualEnteredAt());
        }
        // Q150: a symbol no longer fetched (only expired positions left) keeps its last price without a failure tag
        boolean failed = tracked && s.priceFailed();
        return new UnderlyingQuote(symbol, QuotePrices.display(s.effectivePrice()), QuotePrices.display(s.price()),
                s.source(), s.quoteTime(), local(s.quoteTime()), s.manual(), s.manualEnteredAt(),
                local(s.manualEnteredAt()), failed, s.lastPriceSuccessAt(), local(s.lastPriceSuccessAt()),
                failed ? QuoteLabels.rowStale(s.lastPriceSuccessAt()) : null);
    }

    static OptionQuote option(OptionState stored, UnderlyingState underlying, boolean index, boolean expired) {
        OptionState o = stored;
        if (index && o != null) {
            o = OptionState.empty(o.contract()).withManual(o.manualMark(), o.manualEnteredAt());
        }
        if (expired && o != null && o.quoted() && o.missingSince() == null) {
            // Q150: no longer fetched after expiration - MISSING_FROM_SOURCE with the last quote kept (Q151 label)
            o = new OptionState(o.contract(), o.occCode(), o.bid(), o.ask(), o.last(), o.theo(), o.iv(), o.delta(),
                    o.gamma(), o.theta(), o.vega(), o.rho(), o.volume(), o.openInterest(), o.quoteTime(), o.fetchedAt(),
                    o.quoteTime(), o.manualMark(), o.manualEnteredAt());
        }
        boolean chainFailed = !index && !expired && underlying != null && underlying.chainFailed();
        Instant lastSuccess = index || underlying == null ? null : underlying.lastChainSuccessAt();
        String stale = chainFailed ? QuoteLabels.rowStale(lastSuccess) : null;
        if (o == null) {
            return new OptionQuote(null, null, null, null, null, null, null, null, ContractStatus.NEVER_QUOTED, null,
                    false, null, null, chainFailed, lastSuccess, local(lastSuccess), stale);
        }
        QuotePrices.Mark auto = o.automaticMark();
        BigDecimal mark = o.manual() ? o.manualMark() : auto.value();
        ContractStatus status = o.status();
        return new OptionQuote(QuotePrices.display(o.bid()), QuotePrices.display(o.ask()), QuotePrices.display(mark),
                QuotePrices.display(auto.value()), auto.basis(), o.quoted() ? "CBOE" : null, o.quoteTime(),
                local(o.quoteTime()), status,
                status != ContractStatus.MISSING_FROM_SOURCE ? null
                        // Q151: an expired contract (no longer fetched, Q150) says so instead of "数据源已无该合约"
                        : expired ? QuoteLabels.expired(o.quoteTime()) : QuoteLabels.missingFromSource(o.quoteTime()),
                o.manual(), o.manualEnteredAt(), local(o.manualEnteredAt()), chainFailed, lastSuccess,
                local(lastSuccess), stale);
    }

    static String local(Instant t) {
        return t == null ? null : LOCAL_SECONDS.format(MarketTime.toLocal(t));
    }
}
