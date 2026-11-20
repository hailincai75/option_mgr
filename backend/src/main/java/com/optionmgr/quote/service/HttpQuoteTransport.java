package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.FinnhubKeyResolver;
import com.optionmgr.calendar.service.FinnhubProperties;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FEAT-016 real sources: CBOE {@code GET <cboe-base-url>/api/global/delayed_quotes/options/{SYMBOL}.json} (no key)
 * and Finnhub {@code GET <finnhub base-url>/api/v1/quote?symbol=} with the key only in the {@code X-Finnhub-Token}
 * header (same key lookup as FEAT-009). The key never appears in a URL, log line or detail.
 *
 * <p>FEAT-052 Yahoo: a separate client with an in-memory cookie store (process only, never written or logged), the
 * configured browser User-Agent on every request, and at least {@code request-interval-millis} between two Yahoo
 * requests (handshake steps included).
 */
@Component
@ConditionalOnProperty(prefix = "optionmgr.quotes", name = "transport", havingValue = "http", matchIfMissing = true)
public class HttpQuoteTransport implements QuoteTransport {

    static final String CBOE_PATH = "/api/global/delayed_quotes/options/";
    static final String FINNHUB_PATH = "/api/v1/quote?symbol=";
    static final String TOKEN_HEADER = "X-Finnhub-Token";
    static final String YAHOO_CRUMB_PATH = "/v1/test/getcrumb";
    static final String YAHOO_OPTIONS_PATH = "/v7/finance/options/";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; option-mgr)";

    /** Waits between Yahoo requests (tests: no-op). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final QuoteProperties props;
    private final FinnhubProperties finnhub;
    private final HttpClient http;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient yahooHttp;
    private final Sleeper sleeper;
    private long lastYahooRequestNanos;
    private boolean yahooRequested;

    @Autowired
    public HttpQuoteTransport(QuoteProperties props, FinnhubProperties finnhub) {
        this(props, finnhub, Thread::sleep);
    }

    HttpQuoteTransport(QuoteProperties props, FinnhubProperties finnhub, Sleeper sleeper) {
        this.props = props;
        this.finnhub = finnhub;
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                // cdn.cboe.com answers 307 to cdn-api.cboe.com; NORMAL never follows https -> http
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.yahooHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookies)
                .build();
    }

    @Override
    public FetchAttempt cboe(String symbol) {
        return get(http, trim(props.cboeBaseUrl()) + CBOE_PATH + encode(symbol) + ".json", null, USER_AGENT);
    }

    @Override
    public FetchAttempt finnhubQuote(String symbol) {
        Map<String, String> env = finnhub.readSystemEnv() ? System.getenv() : Map.of();
        List<Path> files = finnhub.envFiles().stream().filter(s -> !s.isBlank()).map(Path::of).toList();
        Optional<String> key = FinnhubKeyResolver.resolve(finnhub.apiKey(), env, files);
        if (key.isEmpty()) {
            return new FetchAttempt.NoKey();
        }
        return get(http, trim(finnhub.baseUrl()) + FINNHUB_PATH + encode(symbol), key.get(), USER_AGENT);
    }

    @Override
    public Handshake yahooHandshake() {
        cookies.getCookieStore().removeAll();
        // 1. any HTTP status will do (fc.yahoo.com answers 404): only the cookie matters
        FetchAttempt page = yahooGet(props.yahoo().cookieUrl());
        if (page instanceof FetchAttempt.NetworkError e) {
            return new Handshake.Failed("cookie " + e.detail());
        }
        // 2. the crumb, with the cookie
        FetchAttempt crumb = yahooGet(trim(props.yahoo().baseUrl()) + YAHOO_CRUMB_PATH);
        return switch (crumb) {
            case FetchAttempt.NetworkError e -> new Handshake.Failed("getcrumb " + e.detail());
            case FetchAttempt.Response r when r.status() != 200 -> new Handshake.Failed("getcrumb HTTP " + r.status());
            case FetchAttempt.Response r -> QuoteParsers.yahooCrumb(r.body())
                    .<Handshake>map(Handshake.Ok::new)
                    .orElseGet(() -> new Handshake.Failed("getcrumb invalid crumb"));
            case FetchAttempt.NoKey k -> new Handshake.Failed("getcrumb");
        };
    }

    @Override
    public FetchAttempt yahooOptions(String symbol, LocalDate date, String crumb) {
        StringBuilder url = new StringBuilder(trim(props.yahoo().baseUrl())).append(YAHOO_OPTIONS_PATH)
                .append(encode(symbol)).append("?crumb=").append(encode(crumb == null ? "" : crumb));
        if (date != null) {
            url.append("&date=").append(epochSecond(date));
        }
        return yahooGet(url.toString());
    }

    /** 00:00 UTC of the date in Unix seconds (2027-02-19 -&gt; 1802995200). */
    static long epochSecond(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private synchronized FetchAttempt yahooGet(String url) {
        long interval = props.requestIntervalMillis();
        if (yahooRequested && interval > 0) {
            long waitMillis = interval - (System.nanoTime() - lastYahooRequestNanos) / 1_000_000;
            if (waitMillis > 0) {
                try {
                    sleeper.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new FetchAttempt.NetworkError("interrupted");
                }
            }
        }
        try {
            return get(yahooHttp, url, null, props.yahoo().userAgent());
        } finally {
            yahooRequested = true;
            lastYahooRequestNanos = System.nanoTime();
        }
    }

    private FetchAttempt get(HttpClient client, String url, String token, String userAgent) {
        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET();
            if (token != null) {
                b.header(TOKEN_HEADER, token);
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            return new FetchAttempt.NetworkError("invalid base URL");
        }
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new FetchAttempt.Response(response.statusCode(), response.body());
        } catch (IOException e) {
            String msg = e.getMessage();
            String text = e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg.strip());
            return new FetchAttempt.NetworkError(text.length() > 200 ? text.substring(0, 200) : text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchAttempt.NetworkError("interrupted");
        }
    }

    private static String trim(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String encode(String symbol) {
        return URLEncoder.encode(symbol, StandardCharsets.UTF_8);
    }
}
