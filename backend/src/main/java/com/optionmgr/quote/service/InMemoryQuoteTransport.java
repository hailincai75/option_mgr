package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * FEAT-016 fake sources (only in the {@code test} / {@code e2e} profiles with
 * {@code optionmgr.quotes.transport=in-memory}; with that setting in any other profile there is no transport and the
 * application does not start). Replies are configured per symbol (unconfigured = unreachable); every request made is
 * recorded.
 */
@Component
@Profile({"test", "e2e"})
@ConditionalOnProperty(prefix = "optionmgr.quotes", name = "transport", havingValue = "in-memory")
public class InMemoryQuoteTransport implements QuoteTransport {

    /** A configured reply: {@code unreachable}, or an HTTP status + body text. */
    public record Reply(boolean unreachable, int status, String body) {

        public static Reply unreachableReply() {
            return new Reply(true, 0, null);
        }

        public static Reply of(int status, String body) {
            return new Reply(false, status, body);
        }
    }

    /** The whole configuration. */
    public record Sources(boolean finnhubKeyPresent, Map<String, Reply> cboe, Map<String, Reply> finnhub) {

        public static final Sources EMPTY = new Sources(true, Map.of(), Map.of());

        public Sources {
            cboe = Map.copyOf(cboe);
            finnhub = Map.copyOf(finnhub);
        }
    }

    /** One request received (FINNHUB requests are not made without a key). */
    public record Request(String source, String symbol, Instant at) {
    }

    private final Clock clock;
    private volatile Sources sources = Sources.EMPTY;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public InMemoryQuoteTransport(Clock clock) {
        this.clock = clock;
    }

    public void configure(Sources sources) {
        this.sources = sources;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    /** Back to the empty configuration, no requests. */
    public void reset() {
        sources = Sources.EMPTY;
        requests.clear();
    }

    @Override
    public FetchAttempt cboe(String symbol) {
        requests.add(new Request("CBOE", symbol, clock.instant()));
        return answer(sources.cboe().get(symbol));
    }

    @Override
    public FetchAttempt finnhubQuote(String symbol) {
        Sources s = sources;
        if (!s.finnhubKeyPresent()) {
            return new FetchAttempt.NoKey();
        }
        requests.add(new Request("FINNHUB", symbol, clock.instant()));
        return answer(s.finnhub().get(symbol));
    }

    private static FetchAttempt answer(Reply r) {
        if (r == null || r.unreachable()) {
            return new FetchAttempt.NetworkError("fake: unreachable");
        }
        return new FetchAttempt.Response(r.status(), r.body());
    }
}
