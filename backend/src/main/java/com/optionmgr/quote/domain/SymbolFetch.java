package com.optionmgr.quote.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FEAT-016 / FEAT-052: what one run got for one symbol from its option source ({@code optionSource}) and, when that
 * gave no price, from Finnhub.
 * <ul>
 *   <li>CBOE: {@code chain} is non-null exactly when {@code cboeResult} is SUCCESS;</li>
 *   <li>Yahoo: {@code yahooChains} (by requested expiration; empty for a stock-only symbol) and {@code yahooPrice}
 *       (the last response with a price &gt; 0, null if none) are only set when {@code yahooResult} is SUCCESS - one
 *       failed request fails the whole symbol (rule 7).</li>
 * </ul>
 * {@code finnhubResult} is null when Finnhub was not needed; {@code finnhub} is non-null exactly when
 * {@code finnhubResult} is SUCCESS. Details are short, without key, crumb, cookie or URL query.
 */
public record SymbolFetch(String symbol, QuoteSource optionSource, SourceResult cboeResult, CboeChain chain,
                          String cboeDetail, SourceResult yahooResult, Map<LocalDate, YahooChain> yahooChains,
                          YahooChain yahooPrice, String yahooDetail, SourceResult finnhubResult, FinnhubQuote finnhub,
                          String finnhubDetail) {

    public SymbolFetch {
        yahooChains = yahooChains == null ? Map.of() : Map.copyOf(yahooChains);
    }

    /** FEAT-016 form: a CBOE fetch. */
    public SymbolFetch(String symbol, SourceResult cboeResult, CboeChain chain, String cboeDetail,
                       SourceResult finnhubResult, FinnhubQuote finnhub, String finnhubDetail) {
        this(symbol, QuoteSource.CBOE, cboeResult, chain, cboeDetail, null, null, null, null, finnhubResult, finnhub,
                finnhubDetail);
    }

    /** FEAT-052: a Yahoo fetch. */
    public static SymbolFetch yahoo(String symbol, SourceResult yahooResult, Map<LocalDate, YahooChain> chains,
                                    YahooChain price, String yahooDetail, SourceResult finnhubResult,
                                    FinnhubQuote finnhub, String finnhubDetail) {
        return new SymbolFetch(symbol, QuoteSource.YAHOO, null, null, null, yahooResult, chains, price, yahooDetail,
                finnhubResult, finnhub, finnhubDetail);
    }

    private boolean yahoo() {
        return optionSource == QuoteSource.YAHOO;
    }

    public boolean chainOk() {
        return yahoo() ? yahooResult == SourceResult.SUCCESS : cboeResult == SourceResult.SUCCESS;
    }

    private boolean sourcePrice() {
        return chainOk() && (yahoo() ? yahooPrice != null && yahooPrice.hasPrice() : chain.hasPrice());
    }

    /** Price from the option source if any, else Finnhub. */
    public boolean priceOk() {
        return sourcePrice() || finnhubResult == SourceResult.SUCCESS;
    }

    public PriceSource priceSource() {
        if (sourcePrice()) {
            return yahoo() ? PriceSource.YAHOO : PriceSource.CBOE;
        }
        return finnhubResult == SourceResult.SUCCESS ? PriceSource.FINNHUB : null;
    }

    public SymbolResult result() {
        if (chainOk()) {
            return SymbolResult.SUCCESS;
        }
        return priceOk() ? SymbolResult.FALLBACK : SymbolResult.FAILED;
    }

    /**
     * CBOE: "CBOE HTTP 500; Finnhub NO_KEY" (failures only). Yahoo: "Yahoo HTTP 403 (2026-12-18, after
     * re-handshake); Finnhub SUCCESS" (the Finnhub result whenever Finnhub was asked). Null when there is nothing.
     */
    public String failureDetail(List<String> extra) {
        List<String> parts = new ArrayList<>();
        if (yahoo()) {
            if (!chainOk()) {
                parts.add("Yahoo " + (yahooDetail == null ? yahooResult.name() : yahooDetail));
            } else if (!sourcePrice()) {
                parts.add("Yahoo no regularMarketPrice");
            }
            if (finnhubResult != null) {
                parts.add("Finnhub " + (finnhubResult == SourceResult.SUCCESS || finnhubDetail == null
                        ? finnhubResult.name() : finnhubDetail));
            }
        } else {
            if (!chainOk()) {
                parts.add("CBOE " + (cboeDetail == null ? cboeResult.name() : cboeDetail));
            } else if (!chain.hasPrice()) {
                parts.add("CBOE no current_price");
            }
            if (finnhubResult != null && finnhubResult != SourceResult.SUCCESS) {
                parts.add("Finnhub " + (finnhubDetail == null ? finnhubResult.name() : finnhubDetail));
            }
        }
        parts.addAll(extra);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }
}
