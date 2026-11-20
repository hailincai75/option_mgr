package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * FEAT-016 / FEAT-052 fake sources (only in the {@code test} / {@code e2e} profiles with
 * {@code optionmgr.quotes.transport=in-memory}; with that setting in any other profile there is no transport and the
 * application does not start). Replies are configured per symbol (unconfigured = unreachable); every request made is
 * recorded. Yahoo replies may be sequences (one per request, the last one repeated); the handshake is one fake
 * request (cookie + getcrumb); no request interval is waited for.
 */
@Component
@Profile({"test", "e2e"})
@ConditionalOnProperty(prefix = "optionmgr.quotes", name = "transport", havingValue = "in-memory")
public class InMemoryQuoteTransport implements QuoteTransport {

    /** Crumb of a handshake without a configured reply. */
    public static final String DEFAULT_CRUMB = "testcrumb01";

    /** A configured reply: {@code unreachable}, or an HTTP status + body text. */
    public record Reply(boolean unreachable, int status, String body) {

        public static Reply unreachableReply() {
            return new Reply(true, 0, null);
        }

        public static Reply of(int status, String body) {
            return new Reply(false, status, body);
        }
    }

    /**
     * FEAT-052: replies of one Yahoo symbol: {@code byDate} for requests with {@code date}; {@code fallback}
     * ("default") for requests without {@code date} and for dates not in {@code byDate}. Each value is a sequence.
     */
    public record YahooSymbol(Map<LocalDate, List<Reply>> byDate, List<Reply> fallback) {

        public YahooSymbol {
            byDate = byDate == null ? Map.of() : Map.copyOf(byDate);
            fallback = fallback == null ? null : List.copyOf(fallback);
        }
    }

    /** FEAT-052: fake Yahoo; {@code handshake} null = success with {@link #DEFAULT_CRUMB}. */
    public record Yahoo(List<Reply> handshake, Map<String, YahooSymbol> symbols) {

        public static final Yahoo EMPTY = new Yahoo(null, Map.of());

        public Yahoo {
            handshake = handshake == null ? null : List.copyOf(handshake);
            symbols = symbols == null ? Map.of() : Map.copyOf(symbols);
        }
    }

    /** The whole configuration. */
    public record Sources(boolean finnhubKeyPresent, Map<String, Reply> cboe, Map<String, Reply> finnhub,
                          Yahoo yahoo) {

        public static final Sources EMPTY = new Sources(true, Map.of(), Map.of(), Yahoo.EMPTY);

        public Sources {
            cboe = Map.copyOf(cboe);
            finnhub = Map.copyOf(finnhub);
            yahoo = yahoo == null ? Yahoo.EMPTY : yahoo;
        }

        /** FEAT-016 form (empty Yahoo). */
        public Sources(boolean finnhubKeyPresent, Map<String, Reply> cboe, Map<String, Reply> finnhub) {
            this(finnhubKeyPresent, cboe, finnhub, Yahoo.EMPTY);
        }
    }

    /**
     * One request received (FINNHUB requests are not made without a key). FEAT-052: {@code kind} HANDSHAKE / OPTIONS
     * for Yahoo (null otherwise), {@code expirationDate} the Yahoo {@code date} (null without).
     */
    public record Request(String source, String kind, String symbol, LocalDate expirationDate, Instant at) {

        public Request(String source, String symbol, Instant at) {
            this(source, null, symbol, null, at);
        }
    }

    private final Clock clock;
    private volatile Sources sources = Sources.EMPTY;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> counters = new ConcurrentHashMap<>();

    public InMemoryQuoteTransport(Clock clock) {
        this.clock = clock;
    }

    /** Replaces the configuration; sequence counters restart. */
    public void configure(Sources sources) {
        this.sources = sources;
        counters.clear();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    /** Back to the empty configuration, no requests. */
    public void reset() {
        configure(Sources.EMPTY);
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

    @Override
    public Handshake yahooHandshake() {
        requests.add(new Request("YAHOO", "HANDSHAKE", null, null, clock.instant()));
        List<Reply> replies = sources.yahoo().handshake();
        if (replies == null) {
            return new Handshake.Ok(DEFAULT_CRUMB);
        }
        Reply r = next("handshake", replies);
        if (r == null || r.unreachable()) {
            return new Handshake.Failed("fake: unreachable");
        }
        if (r.status() != 200) {
            return new Handshake.Failed("getcrumb HTTP " + r.status());
        }
        return QuoteParsers.yahooCrumb(r.body()).<Handshake>map(Handshake.Ok::new)
                .orElseGet(() -> new Handshake.Failed("getcrumb invalid crumb"));
    }

    @Override
    public FetchAttempt yahooOptions(String symbol, LocalDate date, String crumb) {
        requests.add(new Request("YAHOO", "OPTIONS", symbol, date, clock.instant()));
        YahooSymbol s = sources.yahoo().symbols().get(symbol);
        if (s == null) {
            return answer(null);
        }
        if (date != null && s.byDate().containsKey(date)) {
            return answer(next(symbol + "|" + date, s.byDate().get(date)));
        }
        return answer(s.fallback() == null ? null : next(symbol + "|default", s.fallback()));
    }

    /** The n-th reply of a sequence, the last one once used up. */
    private Reply next(String key, List<Reply> replies) {
        if (replies.isEmpty()) {
            return null;
        }
        int n = counters.merge(key, 1, Integer::sum) - 1;
        return replies.get(Math.min(n, replies.size() - 1));
    }

    private static FetchAttempt answer(Reply r) {
        if (r == null || r.unreachable()) {
            return new FetchAttempt.NetworkError("fake: unreachable");
        }
        return new FetchAttempt.Response(r.status(), r.body());
    }
}
