package com.optionmgr.quote.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-016: what one run got for one symbol. {@code chain} is non-null exactly when {@code cboeResult} is
 * SUCCESS; {@code finnhubResult} is null when Finnhub was not needed (chain with a price); {@code finnhub} is
 * non-null exactly when {@code finnhubResult} is SUCCESS. Details are short, without key or URL query.
 */
public record SymbolFetch(String symbol, SourceResult cboeResult, CboeChain chain, String cboeDetail,
                          SourceResult finnhubResult, FinnhubQuote finnhub, String finnhubDetail) {

    public boolean chainOk() {
        return cboeResult == SourceResult.SUCCESS;
    }

    /** CBOE price if any, else Finnhub. */
    public boolean priceOk() {
        return chainOk() && chain.hasPrice() || finnhubResult == SourceResult.SUCCESS;
    }

    public PriceSource priceSource() {
        if (chainOk() && chain.hasPrice()) {
            return PriceSource.CBOE;
        }
        return finnhubResult == SourceResult.SUCCESS ? PriceSource.FINNHUB : null;
    }

    public SymbolResult result() {
        if (chainOk()) {
            return SymbolResult.SUCCESS;
        }
        return priceOk() ? SymbolResult.FALLBACK : SymbolResult.FAILED;
    }

    /** "CBOE HTTP 500; Finnhub NO_KEY" style summary of the failures (null when nothing failed). */
    public String failureDetail(List<String> extra) {
        List<String> parts = new ArrayList<>();
        if (!chainOk()) {
            parts.add("CBOE " + (cboeDetail == null ? cboeResult.name() : cboeDetail));
        } else if (!chain.hasPrice()) {
            parts.add("CBOE no current_price");
        }
        if (finnhubResult != null && finnhubResult != SourceResult.SUCCESS) {
            parts.add("Finnhub " + (finnhubDetail == null ? finnhubResult.name() : finnhubDetail));
        }
        parts.addAll(extra);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }
}
