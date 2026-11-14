package com.optionmgr.systemtest;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * QA test double for FEAT-016 over real HTTP: one local server (random loopback port) standing in for both CBOE
 * ({@code /api/global/delayed_quotes/options/{SYMBOL}.json}) and Finnhub ({@code /api/v1/quote?symbol=}). Responses
 * are configured per request path (without query) or per Finnhub symbol; every request's URI and
 * {@code X-Finnhub-Token} header are recorded. Unconfigured paths answer 404. Never the real services.
 */
final class QaFakeQuoteServer implements AutoCloseable {

    record Received(String uri, String token) {
    }

    record Response(int status, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Response> byKey = new ConcurrentHashMap<>();

    QaFakeQuoteServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            received.add(new Received(uri, exchange.getRequestHeaders().getFirst("X-Finnhub-Token")));
            String path = exchange.getRequestURI().getPath();
            String key = path;
            if (path.equals("/api/v1/quote")) {
                String q = exchange.getRequestURI().getQuery();
                key = "finnhub:" + (q == null ? "" : q.replaceFirst("^.*symbol=([^&]*).*$", "$1"));
            }
            Response r = byKey.getOrDefault(key, new Response(404, "{\"error\":\"not configured\"}"));
            byte[] bytes = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void cboe(String symbol, int status, String body) {
        byKey.put("/api/global/delayed_quotes/options/" + symbol + ".json", new Response(status, body));
    }

    void finnhub(String symbol, int status, String body) {
        byKey.put("finnhub:" + symbol, new Response(status, body));
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void clear() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
