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
    final List<String> accepts = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("X-Finnhub-Token")));
            accepts.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
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

    // ---------------------------------------------------------------- FEAT-052 Yahoo (local fake server only)

    record YahooReceived(String uri, String cookie, String userAgent, String accept, String acceptLanguage) {
    }

    final List<YahooReceived> yahooReceived = new CopyOnWriteArrayList<>();
    volatile int optionsStatus = 200;
    volatile String crumbBody = "AbCdEfGhIjK";
    final List<Long> slept = new CopyOnWriteArrayList<>();

    HttpQuoteTransport yahoo(long intervalMillis) {
        server.removeContext("/");
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            yahooReceived.add(new YahooReceived(ex.getRequestURI().toString(),
                    ex.getRequestHeaders().getFirst("Cookie"), ex.getRequestHeaders().getFirst("User-Agent"),
                    ex.getRequestHeaders().getFirst("Accept"), ex.getRequestHeaders().getFirst("Accept-Language")));
            int code;
            String text;
            if (path.equals("/fc/")) {
                ex.getResponseHeaders().add("Set-Cookie", "A3=d=fakecookie; Path=/");
                code = 404;
                text = "not found";
            } else if (path.equals("/v1/test/getcrumb")) {
                String c = ex.getRequestHeaders().getFirst("Cookie");
                boolean ok = c != null && c.contains("A3=d=fakecookie");
                if (!acceptsTextPlain(ex.getRequestHeaders().getFirst("Accept"))) {
                    // like real Yahoo: getcrumb is text/plain, so Accept: application/json gets 406
                    code = 406;
                    text = "Not Acceptable";
                } else {
                    code = ok ? 200 : 401;
                    text = ok ? crumbBody : "{\"finance\":{\"error\":\"Unauthorized\"}}";
                }
            } else {
                code = optionsStatus;
                text = "{\"optionChain\":{}}";
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
            ex.close();
        });
        QuoteProperties p = new QuoteProperties(false, "http", base(), 2, 2, null, "yahoo",
                java.math.BigDecimal.ZERO, intervalMillis,
                new QuoteProperties.Yahoo(base() + "/", base() + "/fc/", "FakeBrowser/1.0"));
        return new HttpQuoteTransport(p, new FinnhubProperties(base(), null, List.of("build/no-env-in-tests/.env"),
                false, false, 2, 2), slept::add);
    }

    @Test
    void yahoo_handshakeSendsCookieAndUserAgent_thenOptionsWithCrumbAndDate() {
        HttpQuoteTransport t = yahoo(0);
        assertThat(t.yahooHandshake()).isEqualTo(new QuoteTransport.Handshake.Ok("AbCdEfGhIjK"));
        FetchAttempt a = t.yahooOptions("QRTV", java.time.LocalDate.parse("2027-02-19"), "AbCdEfGhIjK");
        assertThat(a).isEqualTo(new FetchAttempt.Response(200, "{\"optionChain\":{}}"));
        t.yahooOptions("IXQZ", null, "AbCdEfGhIjK");
        assertThat(yahooReceived).extracting(YahooReceived::uri).containsExactly("/fc/", "/v1/test/getcrumb",
                "/v7/finance/options/QRTV?crumb=AbCdEfGhIjK&date=1802995200",
                "/v7/finance/options/IXQZ?crumb=AbCdEfGhIjK");
        assertThat(yahooReceived).extracting(YahooReceived::userAgent).containsOnly("FakeBrowser/1.0");
        assertThat(yahooReceived.subList(1, 4)).extracting(YahooReceived::cookie).containsOnly("A3=d=fakecookie");
        assertThat(slept).as("interval 0").isEmpty();
    }

    /** No Accept header means anything; otherwise one of the listed media ranges must cover text/plain. */
    static boolean acceptsTextPlain(String accept) {
        if (accept == null) {
            return true;
        }
        for (String range : accept.split(",")) {
            String type = range.split(";")[0].strip();
            if (type.equals("*/*") || type.equals("text/*") || type.equals("text/plain")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void yahoo_acceptAllowsTextPlainCrumb_cboeAndFinnhubStayJson() {
        HttpQuoteTransport t = yahoo(0);
        assertThat(t.yahooHandshake()).as("getcrumb must not answer 406")
                .isEqualTo(new QuoteTransport.Handshake.Ok("AbCdEfGhIjK"));
        t.yahooOptions("QRTV", null, "AbCdEfGhIjK");
        assertThat(yahooReceived).hasSize(3);
        assertThat(yahooReceived).extracting(YahooReceived::accept).containsOnly("*/*");
        assertThat(yahooReceived).extracting(YahooReceived::acceptLanguage).containsOnly("en-US,en;q=0.9");

        server.removeContext("/");
        server.createContext("/", ex -> {
            accepts.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        transport("fake-test-key").cboe("QRTV");
        transport("fake-test-key").finnhubQuote("QRTV");
        assertThat(accepts).containsExactly("application/json", "application/json");
    }

    @Test
    void yahoo_fakeAnswers406WhenAcceptIsJsonOnly() {
        assertThat(acceptsTextPlain("application/json")).isFalse();
        assertThat(acceptsTextPlain("*/*")).isTrue();
        assertThat(acceptsTextPlain("text/plain;q=0.9, application/json")).isTrue();
    }

    @Test
    void yahoo_handshakeFailures() {
        HttpQuoteTransport t = yahoo(0);
        crumbBody = "<html>blocked</html>";
        assertThat(t.yahooHandshake()).isEqualTo(new QuoteTransport.Handshake.Failed("getcrumb invalid crumb"));
        HttpQuoteTransport down = new HttpQuoteTransport(new QuoteProperties(false, "http", "http://127.0.0.1:9", 1, 1,
                null, "yahoo", java.math.BigDecimal.ZERO, 0L,
                new QuoteProperties.Yahoo("http://127.0.0.1:9", "http://127.0.0.1:9/", null)),
                new FinnhubProperties("http://127.0.0.1:9", null, List.of(), false, false, 1, 1));
        assertThat(down.yahooHandshake()).isInstanceOf(QuoteTransport.Handshake.Failed.class)
                .extracting(h -> ((QuoteTransport.Handshake.Failed) h).detail()).asString().startsWith("cookie ");
        assertThat(down.yahooOptions("QRTV", null, "x")).isInstanceOf(FetchAttempt.NetworkError.class);
    }

    @Test
    void yahoo_rejectedStatusesReturnedAsIs_noRetryInTransport() {
        HttpQuoteTransport t = yahoo(0);
        optionsStatus = 429;
        assertThat(t.yahooOptions("QRTV", null, "c")).isEqualTo(new FetchAttempt.Response(429, "{\"optionChain\":{}}"));
        optionsStatus = 401;
        assertThat(t.yahooOptions("QRTV", null, "c")).isEqualTo(new FetchAttempt.Response(401, "{\"optionChain\":{}}"));
        assertThat(yahooReceived).hasSize(2);
    }

    @Test
    void yahoo_requestIntervalBetweenRequests() {
        HttpQuoteTransport t = yahoo(1000);
        t.yahooHandshake();
        t.yahooOptions("QRTV", null, "c");
        assertThat(slept).hasSize(2).allSatisfy(ms -> assertThat(ms).isBetween(1L, 1000L));
    }

    @Test
    void yahoo_epochSecondIsMidnightUtc() {
        assertThat(HttpQuoteTransport.epochSecond(java.time.LocalDate.parse("2026-11-20"))).isEqualTo(1795132800L);
        assertThat(HttpQuoteTransport.epochSecond(java.time.LocalDate.parse("2027-02-19"))).isEqualTo(1802995200L);
    }
}
