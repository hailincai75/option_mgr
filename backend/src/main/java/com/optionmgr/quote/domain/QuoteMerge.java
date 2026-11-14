package com.optionmgr.quote.domain;

import java.time.Instant;

/**
 * FEAT-016 rules 6-8 / Q140-Q144: how one run's result changes the stored data. Failures never clear or change a
 * stored quote; a manual override is replaced by the next successful automatic value. Pure.
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
                manualPrice, manualAt);
    }

    /**
     * A held contract after a successful chain (a failed chain changes nothing, so it is not passed here).
     *
     * @param before   stored state (null = no row)
     * @param match    how the contract appears in the chain
     * @return the new state, or null when there is nothing to store (never quoted, not found, no row before)
     */
    public static OptionState option(OptionState before, com.optionmgr.trade.domain.ContractKey contract,
                                     ChainMatch match, Instant chainTime, Instant runAt) {
        OptionState b = before == null ? OptionState.empty(contract) : before;
        if (match instanceof ChainMatch.Found found) {
            CboeOption o = found.option();
            OptionState quoted = new OptionState(contract, o.code().code(), o.bid(), o.ask(), o.last(), o.theo(),
                    o.iv(), o.delta(), o.gamma(), o.theta(), o.vega(), o.rho(), o.volume(), o.openInterest(),
                    chainTime, runAt, null, b.manualMark(), b.manualEnteredAt());
            // Q140: the next successful automatic quote replaces the override - only when it gives a mark
            return quoted.automaticMark().value() != null ? quoted.withManual(null, null) : quoted;
        }
        if (before == null) {
            return null;
        }
        if (b.quoted() && b.missingSince() == null) {
            return new OptionState(contract, b.occCode(), b.bid(), b.ask(), b.last(), b.theo(), b.iv(), b.delta(),
                    b.gamma(), b.theta(), b.vega(), b.rho(), b.volume(), b.openInterest(), b.quoteTime(),
                    b.fetchedAt(), runAt, b.manualMark(), b.manualEnteredAt());
        }
        return b;
    }
}
