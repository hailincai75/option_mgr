package com.optionmgr.quote.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FEAT-016 settings ({@code optionmgr.quotes.*}). Finnhub (base URL, key) reuses {@code optionmgr.finnhub.*}.
 *
 * @param fetchEnabled          scheduled runs + the startup run (off in the test and e2e profiles)
 * @param transport             {@code http} (real CBOE / Finnhub) or {@code in-memory} (test / e2e fake sources)
 * @param cboeBaseUrl           CBOE CDN base URL; tests point it at a fake server
 * @param connectTimeoutSeconds connect timeout
 * @param readTimeoutSeconds    response timeout
 * @param indexSymbols          index underlyings that are never fetched (their options show "无报价")
 */
@ConfigurationProperties(prefix = "optionmgr.quotes")
public record QuoteProperties(Boolean fetchEnabled, String transport, String cboeBaseUrl, Integer connectTimeoutSeconds,
                              Integer readTimeoutSeconds, List<String> indexSymbols) {

    public static final List<String> DEFAULT_INDEX_SYMBOLS = List.of("SPX", "SPXW", "NDX", "NDXP", "RUT", "RUTW",
            "VIX", "VIXW", "XSP", "DJX", "OEX", "XEO");

    public QuoteProperties {
        fetchEnabled = fetchEnabled == null || fetchEnabled;
        transport = transport == null || transport.isBlank() ? "http" : transport.strip();
        cboeBaseUrl = cboeBaseUrl == null || cboeBaseUrl.isBlank() ? "https://cdn-api.cboe.com" : cboeBaseUrl.strip();
        connectTimeoutSeconds = connectTimeoutSeconds == null ? 5 : connectTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds == null ? 10 : readTimeoutSeconds;
        indexSymbols = indexSymbols == null ? DEFAULT_INDEX_SYMBOLS : List.copyOf(indexSymbols);
    }

    public Set<String> indexSymbolSet() {
        return indexSymbols.stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isIndex(String symbol) {
        return symbol != null && indexSymbolSet().contains(symbol.toUpperCase(Locale.ROOT));
    }
}
