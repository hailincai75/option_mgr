package com.optionmgr.quote.domain;

import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * FEAT-016 rules 6-8 / Q140-Q144 and FEAT-052 rule 4 (Q265, Q271): how one run's result changes the stored data.
 * Failures never clear or change a stored quote; a manual override is replaced by the next successful automatic value.
 * Pure.
 */
public final class QuoteMerge {

    private QuoteMerge() {
    }

    /** The underlying after a run that fetched {@code f} at {@code runAt}. */
    public static UnderlyingState underlying(UnderlyingState before, SymbolFetch f, String failureDetail, Instant runAt) {
        UnderlyingState b = before == null ? UnderlyingState.empty(f.symbol()) : before;
        var price = b.price();
        var bid = b.bid();
        var ask = b.ask();
        var prevClose = b.prevClose();
        var source = b.source();
        var quoteTime = b.quoteTime();
        var lastPriceSuccess = b.lastPriceSuccessAt();
        var lastChainSuccess = b.lastChainSuccessAt();
        var manualPrice = b.manualPrice();
        var manualAt = b.manualEnteredAt();
        if (f.chainOk()) {
            lastChainSuccess = runAt;
        }
        PriceSource ps = f.priceSource();
        if (ps == PriceSource.CBOE) {
            CboeChain c = f.chain();
            price = c.currentPrice();
            bid = c.bid();
            ask = c.ask();
            prevClose = c.prevClose();
            quoteTime = c.timestamp();
        } else if (ps == PriceSource.YAHOO) {
            YahooChain c = f.yahooPrice();
            price = c.price();
            bid = c.bid();
            ask = c.ask();
            prevClose = c.prevClose();
            quoteTime = c.time();
        } else if (ps == PriceSource.FINNHUB) {
            FinnhubQuote q = f.finnhub();
            price = q.price();
            bid = null;
            ask = null;
            prevClose = q.prevClose();
            quoteTime = q.time() == null ? runAt : q.time();
        }
        if (ps != null) {
            source = ps;
            lastPriceSuccess = runAt;
            manualPrice = null;
            manualAt = null;
        }
        return new UnderlyingState(b.symbol(), price, bid, ask, prevClose, source, quoteTime, lastPriceSuccess,
                lastChainSuccess, runAt, ps == null, !f.chainOk(), f.cboeResult(), f.finnhubResult(), failureDetail,
                manualPrice, manualAt, f.yahooResult());
    }

    /**
     * A held contract after a successful CBOE chain (a failed chain changes nothing, so it is not passed here).
     *
     * @param before   stored state (null = no row)
     * @param match    how the contract appears in the chain
     * @return the new state, or null when there is nothing to store (never quoted, not found, no row before)
     */
    public static OptionState option(OptionState before, ContractKey contract, ChainMatch match, Instant chainTime,
                                     Instant runAt) {
        OptionState b = before == null ? OptionState.empty(contract) : before;
        if (match instanceof ChainMatch.Found found) {
            CboeOption o = found.option();
            OptionState quoted = new OptionState(contract, o.code().code(), o.bid(), o.ask(), o.last(), o.theo(),
                    o.iv(), o.delta(), o.gamma(), o.theta(), o.vega(), o.rho(), o.volume(), o.openInterest(),
                    chainTime, runAt, null, b.manualMark(), b.manualEnteredAt(), QuoteSource.CBOE);
            // Q140: the next successful automatic quote replaces the override - only when it gives a mark
            return quoted.automaticMark().value() != null ? quoted.withManual(null, null) : quoted;
        }
        return notInChain(before, runAt);
    }

    /**
     * FEAT-052: a held contract after a successful Yahoo symbol, from the response of its expiration.
     * <ul>
     *   <li>found and {@link YahooQuoteRules#replaces}: the whole quote replaced by this response's values with the
     *       Greeks computed now (Black-Scholes, S = this response's price, r = {@code rate}); a manual mark is removed
     *       only by a VALID quote (Q148);</li>
     *   <li>found otherwise: everything kept (Q265), only {@code missingSince} cleared;</li>
     *   <li>not found / ambiguous / expiration not listed: as FEAT-016 (Q143).</li>
     * </ul>
     *
     * @param rate risk-free rate as a decimal (0.04 = 4 %)
     * @return the new state, or null when there is nothing to store
     */
    public static OptionState yahooOption(OptionState before, ContractKey contract, ChainMatch match, YahooChain chain,
                                          BigDecimal rate, Instant runAt) {
        if (!(match instanceof ChainMatch.FoundYahoo found)) {
            return notInChain(before, runAt);
        }
        YahooOption o = found.option();
        YahooQuoteRules.Kind kind = YahooQuoteRules.classify(o);
        if (!YahooQuoteRules.replaces(kind, before)) {
            return before == null ? null : before.withMissingSince(null);
        }
        OptionState b = before == null ? OptionState.empty(contract) : before;
        BigDecimal iv = QuotePrices.forStorage(o.iv(), BlackScholes.SCALE);
        BlackScholes.Greeks g = BlackScholes.greeks(chain.price(), contract.strike(), iv, rate, chain.time(),
                contract.expirationDate(), contract.optionType() == OptionType.CALL);
        OptionState quoted = new OptionState(contract, o.code().code(), o.bid(), o.ask(), o.last(), null, iv,
                g.delta(), g.gamma(), g.theta(), g.vega(), null, o.volume(), o.openInterest(), chain.time(), runAt,
                null, b.manualMark(), b.manualEnteredAt(), QuoteSource.YAHOO);
        return kind == YahooQuoteRules.Kind.VALID ? quoted.withManual(null, null) : quoted;
    }

    private static OptionState notInChain(OptionState before, Instant runAt) {
        if (before == null) {
            return null;
        }
        if (before.quoted() && before.missingSince() == null) {
            return before.withMissingSince(runAt);
        }
        return before;
    }
}
