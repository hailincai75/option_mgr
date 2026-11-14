package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.FinnhubKeyResolver;
import com.optionmgr.calendar.service.FinnhubProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FEAT-016 real sources: CBOE {@code GET <cboe-base-url>/api/global/delayed_quotes/options/{SYMBOL}.json} (no key)
 * and Finnhub {@code GET <finnhub base-url>/api/v1/quote?symbol=} with the key only in the {@code X-Finnhub-Token}
 * header (same key lookup as FEAT-009). The key never appears in a URL, log line or detail.
 */
@Component
@ConditionalOnProperty(prefix = "optionmgr.quotes", name = "transport", havingValue = "http", matchIfMissing = true)
public class HttpQuoteTransport implements QuoteTransport {

    static final String CBOE_PATH = "/api/global/delayed_quotes/options/";
    static final String FINNHUB_PATH = "/api/v1/quote?symbol=";
    static final String TOKEN_HEADER = "X-Finnhub-Token";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; option-mgr)";

    private final QuoteProperties props;
    private final FinnhubProperties finnhub;
    private final HttpClient http;

    public HttpQuoteTransport(QuoteProperties props, FinnhubProperties finnhub) {
        this.props = props;
        this.finnhub = finnhub;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                // cdn.cboe.com answers 307 to cdn-api.cboe.com; NORMAL never follows https -> http
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public FetchAttempt cboe(String symbol) {
        return get(trim(props.cboeBaseUrl()) + CBOE_PATH + encode(symbol) + ".json", null);
    }

    @Override
    public FetchAttempt finnhubQuote(String symbol) {
        Map<String, String> env = finnhub.readSystemEnv() ? System.getenv() : Map.of();
        List<Path> files = finnhub.envFiles().stream().filter(s -> !s.isBlank()).map(Path::of).toList();
        Optional<String> key = FinnhubKeyResolver.resolve(finnhub.apiKey(), env, files);
        if (key.isEmpty()) {
            return new FetchAttempt.NoKey();
        }
        return get(trim(finnhub.baseUrl()) + FINNHUB_PATH + encode(symbol), key.get());
    }

    private FetchAttempt get(String url, String token) {
        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (token != null) {
                b.header(TOKEN_HEADER, token);
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            return new FetchAttempt.NetworkError("invalid base URL");
        }
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
