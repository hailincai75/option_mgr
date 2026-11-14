package com.optionmgr.calendar;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FEAT-009 test double for Finnhub: a local HTTP server (random port) answering
 * {@code /api/v1/stock/market-holiday} with a configurable status and body, recording each request's URI and
 * {@code X-Finnhub-Token} header. Tests never call the real Finnhub.
 */
public final class FakeFinnhub implements AutoCloseable {

    /** One received request. */
    public record Received(String uri, String token) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"data\":[]}";

    public FakeFinnhub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-Finnhub-Token")));
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void clearReceived() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** One Finnhub entry as JSON. */
    public static String entry(String eventName, String atDate, String tradingHour) {
        return "{\"eventName\":\"" + eventName + "\",\"atDate\":\"" + atDate + "\",\"tradingHour\":\"" + tradingHour
                + "\",\"postMarket\":\"\"}";
    }

    /** A Finnhub response body with the given entries. */
    public static String response(String... entries) {
        return "{\"data\":[" + String.join(",", entries) + "],\"exchange\":\"US\",\"timezone\":\"America/New_York\"}";
    }

    /**
     * The acceptance-criteria scenario: 2023-2027 with 2025-01-09 National Mourning Day, 2026-11-26 Thanksgiving,
     * 2026-11-27 early close, 2027-03-26 Good Friday (plus a few real-looking closures per year).
     */
    public static String scenario() {
        return response(
                entry("New Year's Day", "2023-01-02", ""),
                entry("Christmas", "2023-12-25", ""),
                entry("Christmas", "2024-12-25", ""),
                entry("National Mourning Day", "2025-01-09", ""),
                entry("Christmas", "2025-12-25", ""),
                entry("Independence Day", "2026-07-03", ""),
                entry("Thanksgiving Day", "2026-11-26", ""),
                entry("Thanksgiving Day", "2026-11-27", "09:30-13:00"),
                entry("Christmas", "2026-12-25", ""),
                entry("Good Friday", "2027-03-26", ""),
                entry("Christmas", "2027-12-24", ""));
    }
}
