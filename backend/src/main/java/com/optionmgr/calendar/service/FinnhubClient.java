package com.optionmgr.calendar.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * FEAT-009: {@code GET <baseUrl>/api/v1/stock/market-holiday?exchange=US}. The key goes only into the
 * {@code X-Finnhub-Token} header, never into the URL, a log line or an exception detail.
 */
@Component
public class FinnhubClient {

    static final String PATH = "/api/v1/stock/market-holiday?exchange=US";
    static final String TOKEN_HEADER = "X-Finnhub-Token";

    private final FinnhubProperties props;
    private final HttpClient http;

    public FinnhubClient(FinnhubProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchAttempt fetch(String key) {
        String base = props.baseUrl().endsWith("/")
                ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + PATH))
                    .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                    .header(TOKEN_HEADER, key)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return new FetchAttempt.NetworkError("invalid base URL");
        }
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new FetchAttempt.Response(response.statusCode(), response.body());
        } catch (IOException e) {
            return new FetchAttempt.NetworkError(describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchAttempt.NetworkError("interrupted");
        }
    }

    /** Exception class and message only (they never contain request headers). */
    static String describe(IOException e) {
        String msg = e.getMessage();
        String text = e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg.strip());
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
