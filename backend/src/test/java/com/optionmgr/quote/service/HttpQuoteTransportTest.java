package com.optionmgr.quote.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.FinnhubProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FEAT-016 real transport against a local fake server (never the real CBOE / Finnhub). */
class HttpQuoteTransportTest {

    record Received(String uri, String token) {
    }

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("X-Finnhub-Token")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String base() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    HttpQuoteTransport transport(String key) {
        return new HttpQuoteTransport(new QuoteProperties(false, "http", base() + "/", 2, 2, null),
                new FinnhubProperties(base(), key, List.of("build/no-env-in-tests/.env"), false, false, 2, 2));
    }

    @Test
    void cboePathAndBody() {
        body = "{\"data\":{}}";
        FetchAttempt a = transport(null).cboe("QRTV");
        assertThat(a).isEqualTo(new FetchAttempt.Response(200, "{\"data\":{}}"));
        assertThat(received).containsExactly(new Received("/api/global/delayed_quotes/options/QRTV.json", null));
    }

    @Test
    void finnhubKeyOnlyInHeader() {
        status = 403;
        FetchAttempt a = transport("fake-test-key").finnhubQuote("IXQZ");
        assertThat(a).isEqualTo(new FetchAttempt.Response(403, "{}"));
        assertThat(received).containsExactly(new Received("/api/v1/quote?symbol=IXQZ", "fake-test-key"));
    }

    @Test
    void finnhubWithoutKeyMakesNoRequest() {
        assertThat(transport(null).finnhubQuote("IXQZ")).isInstanceOf(FetchAttempt.NoKey.class);
        assertThat(received).isEmpty();
    }

    @Test
    void unreachableIsNetworkError() {
        HttpQuoteTransport t = new HttpQuoteTransport(new QuoteProperties(false, "http", "http://127.0.0.1:9", 1, 1, null),
                new FinnhubProperties("http://127.0.0.1:9", "k", List.of(), false, false, 1, 1));
        assertThat(t.cboe("QRTV")).isInstanceOf(FetchAttempt.NetworkError.class);
        assertThat(t.finnhubQuote("QRTV")).isInstanceOf(FetchAttempt.NetworkError.class);
    }
}
