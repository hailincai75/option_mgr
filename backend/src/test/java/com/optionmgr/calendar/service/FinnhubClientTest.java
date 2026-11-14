package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.calendar.FakeFinnhub;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** FEAT-009 rules 1, 5 / AC9: the HTTP call (against a local fake, never the real Finnhub). */
class FinnhubClientTest {

    private final FakeFinnhub fake = new FakeFinnhub();

    @AfterEach
    void stop() {
        fake.close();
    }

    private FinnhubClient client(String baseUrl) {
        return new FinnhubClient(new FinnhubProperties(baseUrl, null, List.of(), false, false, 2, 2));
    }

    @Test
    void ac9_keyOnlyInTheHeaderNeverInTheUrl() {
        fake.respond(200, FakeFinnhub.scenario());
        FetchAttempt a = client(fake.baseUrl()).fetch("test-key-123");
        assertThat(a).isInstanceOf(FetchAttempt.Response.class);
        assertThat(((FetchAttempt.Response) a).status()).isEqualTo(200);
        assertThat(fake.received()).singleElement().satisfies(r -> {
            assertThat(r.token()).isEqualTo("test-key-123");
            assertThat(r.uri()).isEqualTo("/api/v1/stock/market-holiday?exchange=US").doesNotContain("test-key-123");
        });
    }

    @Test
    void non200IsReturnedAsAResponse() {
        fake.respond(500, "oops");
        FetchAttempt a = client(fake.baseUrl() + "/").fetch("k");
        assertThat(a).isEqualTo(new FetchAttempt.Response(500, "oops"));
    }

    @Test
    void connectionFailureIsANetworkErrorWithoutTheKey() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        FetchAttempt a = client("http://127.0.0.1:" + port).fetch("test-key-123");
        assertThat(a).isInstanceOf(FetchAttempt.NetworkError.class);
        assertThat(((FetchAttempt.NetworkError) a).detail()).isNotBlank().doesNotContain("test-key-123");
    }
}
