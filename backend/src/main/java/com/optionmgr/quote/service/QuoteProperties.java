package com.optionmgr.quote.service;

import com.optionmgr.quote.domain.QuoteSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * FEAT-016 / FEAT-052 settings ({@code optionmgr.quotes.*}). Finnhub (base URL, key) reuses
 * {@code optionmgr.finnhub.*}. An invalid option source or risk-free rate stops the application at startup.
 *
 * @param fetchEnabled          scheduled runs + the startup run (off in the test and e2e profiles)
 * @param transport             {@code http} (real sources) or {@code in-memory} (test / e2e fake sources)
 * @param cboeBaseUrl           CBOE CDN base URL; tests point it at a fake server
 * @param connectTimeoutSeconds connect timeout
 * @param readTimeoutSeconds    response timeout
 * @param indexSymbols          index underlyings that are never fetched (their options show "无报价")
 * @param optionSource          FEAT-052 Q261: {@code yahoo} (default) or {@code cboe}
 * @param riskFreeRatePercent   FEAT-052 Q263: annual rate in percent for the Black-Scholes Greeks, 0-20 (default 4.00)
 * @param requestIntervalMillis FEAT-052: pause between two Yahoo requests (default 1000)
 * @param yahoo                 FEAT-052: Yahoo endpoints and User-Agent
 */
@ConfigurationProperties(prefix = "optionmgr.quotes")
public record QuoteProperties(Boolean fetchEnabled, String transport, String cboeBaseUrl, Integer connectTimeoutSeconds,
                              Integer readTimeoutSeconds, List<String> indexSymbols, String optionSource,
                              BigDecimal riskFreeRatePercent, Long requestIntervalMillis, Yahoo yahoo) {

    public static final List<String> DEFAULT_INDEX_SYMBOLS = List.of("SPX", "SPXW", "NDX", "NDXP", "RUT", "RUTW",
            "VIX", "VIXW", "XSP", "DJX", "OEX", "XEO");
    public static final BigDecimal DEFAULT_RATE = new BigDecimal("4.00");
    public static final BigDecimal MAX_RATE = new BigDecimal("20");

    /**
     * @param baseUrl   getcrumb and options endpoints
     * @param cookieUrl the page that sets the session cookie
     * @param userAgent browser User-Agent sent with every Yahoo request
     */
    public record Yahoo(String baseUrl, String cookieUrl, String userAgent) {

        public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

        public Yahoo {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://query2.finance.yahoo.com" : baseUrl.strip();
            cookieUrl = cookieUrl == null || cookieUrl.isBlank() ? "https://fc.yahoo.com/" : cookieUrl.strip();
            userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.strip();
        }
    }

    @ConstructorBinding
    public QuoteProperties {
        fetchEnabled = fetchEnabled == null || fetchEnabled;
        transport = transport == null || transport.isBlank() ? "http" : transport.strip();
        cboeBaseUrl = cboeBaseUrl == null || cboeBaseUrl.isBlank() ? "https://cdn-api.cboe.com" : cboeBaseUrl.strip();
        connectTimeoutSeconds = connectTimeoutSeconds == null ? 5 : connectTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds == null ? 10 : readTimeoutSeconds;
        indexSymbols = indexSymbols == null ? DEFAULT_INDEX_SYMBOLS : List.copyOf(indexSymbols);
        optionSource = optionSource == null || optionSource.isBlank() ? "yahoo" : optionSource.strip();
        QuoteSource.parse(optionSource);
        riskFreeRatePercent = riskFreeRatePercent == null ? DEFAULT_RATE : riskFreeRatePercent;
        checkRate(riskFreeRatePercent);
        requestIntervalMillis = requestIntervalMillis == null ? 1000L : requestIntervalMillis;
        if (requestIntervalMillis < 0) {
            throw new IllegalArgumentException("optionmgr.quotes.request-interval-millis must be >= 0");
        }
        yahoo = yahoo == null ? new Yahoo(null, null, null) : yahoo;
    }

    /** FEAT-016 form (tests): CBOE source, rate 0, no request interval. */
    public QuoteProperties(Boolean fetchEnabled, String transport, String cboeBaseUrl, Integer connectTimeoutSeconds,
                           Integer readTimeoutSeconds, List<String> indexSymbols) {
        this(fetchEnabled, transport, cboeBaseUrl, connectTimeoutSeconds, readTimeoutSeconds, indexSymbols, "cboe",
                BigDecimal.ZERO, 0L, null);
    }

    /** 0-20 inclusive. */
    public static void checkRate(BigDecimal percent) {
        if (percent.signum() < 0 || percent.compareTo(MAX_RATE) > 0) {
            throw new IllegalArgumentException("optionmgr.quotes.risk-free-rate-percent must be between 0 and 20");
        }
    }

    public QuoteSource optionSourceValue() {
        return QuoteSource.parse(optionSource);
    }

    public Set<String> indexSymbolSet() {
        return indexSymbols.stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isIndex(String symbol) {
        return symbol != null && indexSymbolSet().contains(symbol.toUpperCase(Locale.ROOT));
    }
}
