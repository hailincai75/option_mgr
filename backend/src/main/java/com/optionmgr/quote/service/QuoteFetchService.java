package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.domain.CboeChain;
import com.optionmgr.quote.domain.ChainMatch;
import com.optionmgr.quote.domain.ChainMatcher;
import com.optionmgr.quote.domain.FetchSchedule;
import com.optionmgr.quote.domain.FinnhubQuote;
import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.PriceSource;
import com.optionmgr.quote.domain.QuoteMerge;
import com.optionmgr.quote.domain.QuoteSource;
import com.optionmgr.quote.domain.RunTrigger;
import com.optionmgr.quote.domain.SourceResult;
import com.optionmgr.quote.domain.SymbolFetch;
import com.optionmgr.quote.domain.SymbolResult;
import com.optionmgr.quote.domain.UnderlyingState;
import com.optionmgr.quote.domain.YahooChain;
import com.optionmgr.quote.repository.MarketDataStatusEntity;
import com.optionmgr.quote.repository.MarketDataStatusRepository;
import com.optionmgr.quote.repository.OptionQuoteCloseEntity;
import com.optionmgr.quote.repository.OptionQuoteCloseRepository;
import com.optionmgr.quote.repository.OptionQuoteEntity;
import com.optionmgr.quote.repository.OptionQuoteRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteCloseEntity;
import com.optionmgr.quote.repository.UnderlyingQuoteCloseRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteEntity;
import com.optionmgr.quote.repository.UnderlyingQuoteRepository;
import com.optionmgr.trade.domain.ContractKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FEAT-016 rules 1, 2, 5, 6 / FEAT-052 rules 1-7: one fetch run over the tracked symbols (held in any account, index
 * symbols excluded) from the configured option source (Yahoo or CBOE, never both in one run), Finnhub for the price
 * when the source gave none. Runs are serialized; the network calls happen outside the database transaction, the
 * results are applied in one transaction. A single symbol's failure never stops the run and never clears stored data.
 */
@Service
public class QuoteFetchService {

    private static final Logger log = LoggerFactory.getLogger(QuoteFetchService.class);

    public static final Duration REFRESH_COOLDOWN = Duration.ofSeconds(60);

    /** One symbol's outcome in a run. */
    public record SymbolOutcome(String symbol, SymbolResult result, QuoteSource optionSource, SourceResult yahooResult,
                                SourceResult cboeResult, SourceResult finnhubResult, PriceSource underlyingPriceSource,
                                String failureDetail) {
    }

    /** {@code performed = false}: refresh refused by the cooldown. */
    public record RefreshOutcome(boolean performed, String reason, List<SymbolOutcome> results) {
    }

    /** Simulated scheduler tick (e2e) or a real one: {@code ran} with its trigger, or the skip reason. */
    public record TickOutcome(boolean ran, RunTrigger trigger, String skipReason, List<SymbolOutcome> results) {
    }

    /** FEAT-052: Yahoo session state of one run (at most one re-handshake; a failed handshake ends Yahoo requests). */
    private static final class YahooRun {
        boolean reHandshaked;
        String handshakeFailure;
    }

    /** FEAT-052: outcome of the Yahoo requests of one symbol. */
    private record YahooOutcome(SourceResult result, String detail) {
        static final YahooOutcome OK = new YahooOutcome(SourceResult.SUCCESS, null);
    }

    private final QuoteProperties props;
    private final QuoteSettings settings;
    private final YahooSession session;
    private final QuoteTransport transport;
    private final HeldPositionsService held;
    private final UnderlyingQuoteRepository underlyings;
    private final OptionQuoteRepository options;
    private final UnderlyingQuoteCloseRepository underlyingCloses;
    private final OptionQuoteCloseRepository optionCloses;
    private final MarketDataStatusRepository statuses;
    private final TradingCalendarService calendar;
    private final QuoteWriteLock writeLock;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final QuoteParsers parsers = new QuoteParsers();
    private final ReentrantLock runLock = new ReentrantLock(true);

    public QuoteFetchService(QuoteProperties props, QuoteSettings settings, YahooSession session,
                             QuoteTransport transport, HeldPositionsService held,
                             UnderlyingQuoteRepository underlyings, OptionQuoteRepository options,
                             UnderlyingQuoteCloseRepository underlyingCloses, OptionQuoteCloseRepository optionCloses,
                             MarketDataStatusRepository statuses, TradingCalendarService calendar,
                             QuoteWriteLock writeLock, PlatformTransactionManager txManager, Clock clock) {
        this.props = props;
        this.settings = settings;
        this.session = session;
        this.transport = transport;
        this.held = held;
        this.underlyings = underlyings;
        this.options = options;
        this.underlyingCloses = underlyingCloses;
        this.optionCloses = optionCloses;
        this.statuses = statuses;
        this.calendar = calendar;
        this.writeLock = writeLock;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** One run now (waits for a run in progress). */
    public List<SymbolOutcome> run(RunTrigger trigger) {
        runLock.lock();
        try {
            return runLocked(trigger);
        } finally {
            runLock.unlock();
        }
    }

    /** "立即刷新": no fetch within 60 s of the last accepted refresh (rule 5). */
    public RefreshOutcome refresh() {
        runLock.lock();
        try {
            Instant now = clock.instant();
            Instant last = statusRow().getLastRefreshAt();
            if (last != null && now.isBefore(last.plus(REFRESH_COOLDOWN))) {
                return new RefreshOutcome(false, "COOLDOWN", List.of());
            }
            tx.executeWithoutResult(s -> {
                MarketDataStatusEntity row = statusRow();
                row.recordRefresh(now);
                statuses.save(row);
            });
            return new RefreshOutcome(true, null, runLocked(RunTrigger.REFRESH));
        } finally {
            runLock.unlock();
        }
    }

    /** The scheduler at the current clock: runs only at a Q270 slot on a FEAT-009 trading day. */
    public TickOutcome tick() {
        LocalDateTime local = MarketTime.toLocal(clock.instant());
        Optional<RunTrigger> slot = FetchSchedule.slotAt(local);
        if (slot.isEmpty()) {
            return new TickOutcome(false, null, "NOT_A_SLOT", List.of());
        }
        if (!calendar.calendar().isTradingDay(local.toLocalDate())) {
            return new TickOutcome(false, null, "NON_TRADING_DAY", List.of());
        }
        return new TickOutcome(true, slot.get(), null, run(slot.get()));
    }

    /** Q147: at startup, a run when it is a trading day between 09:30 and 16:20 New York. */
    public boolean runIfInStartupWindow() {
        LocalDateTime local = MarketTime.toLocal(clock.instant());
        if (!FetchSchedule.inStartupWindow(local.toLocalTime()) || !calendar.calendar().isTradingDay(local.toLocalDate())) {
            return false;
        }
        run(RunTrigger.SCHEDULED);
        return true;
    }

    private MarketDataStatusEntity statusRow() {
        return statuses.findById((short) 1).orElseThrow(() -> new IllegalStateException("market_data_status row missing"));
    }

    private List<SymbolOutcome> runLocked(RunTrigger trigger) {
        Instant runAt = clock.instant();
        QuoteSettings.Values s = settings.current();
        BigDecimal rate = s.riskFreeRatePercent().movePointLeft(2);
        HeldPositionsService.Tracked h = held.current()
                .tracked(MarketTime.toLocal(runAt).toLocalDate(), props::isIndex);
        List<String> symbols = h.symbols().stream().sorted().toList();
        List<SymbolFetch> fetches = new ArrayList<>();
        YahooRun yahooRun = new YahooRun();
        for (String symbol : symbols) {
            if (s.optionSource() == QuoteSource.YAHOO) {
                List<LocalDate> dates = h.contractsOf(symbol).stream().map(ContractKey::expirationDate).distinct()
                        .sorted().toList();
                fetches.add(fetchYahoo(symbol, dates, yahooRun));
            } else {
                fetches.add(fetchSymbol(symbol));
            }
        }
        List<SymbolOutcome> outcomes = new ArrayList<>();
        writeLock.lock();
        try {
            tx.executeWithoutResult(st -> outcomes.addAll(apply(trigger, runAt, h, fetches, rate)));
        } finally {
            writeLock.unlock();
        }
        long ok = outcomes.stream().filter(o -> o.result() == SymbolResult.SUCCESS).count();
        if (ok == outcomes.size()) {
            log.info("Quotes {} run ({}): {} symbols updated", trigger, s.optionSource(), ok);
        } else {
            log.warn("Quotes {} run ({}): {} of {} symbols without chain ({})", trigger, s.optionSource(),
                    outcomes.size() - ok, outcomes.size(), outcomes.stream()
                            .filter(o -> o.result() != SymbolResult.SUCCESS)
                            .map(o -> o.symbol() + " " + o.failureDetail()).collect(Collectors.joining(", ")));
        }
        return outcomes;
    }

    /** Network part for one symbol: CBOE, then Finnhub when there is no CBOE price. Never throws. */
    SymbolFetch fetchSymbol(String symbol) {
        SourceResult cboeResult;
        CboeChain chain = null;
        String cboeDetail = null;
        try {
            FetchAttempt a = transport.cboe(symbol);
            switch (a) {
                case FetchAttempt.NetworkError e -> {
                    cboeResult = SourceResult.NETWORK_ERROR;
                    cboeDetail = e.detail();
                }
                case FetchAttempt.Response r when r.status() != 200 -> {
                    cboeResult = SourceResult.HTTP_ERROR;
                    cboeDetail = "HTTP " + r.status();
                }
                case FetchAttempt.Response r -> {
                    try {
                        chain = parsers.parseCboe(r.body());
                        cboeResult = SourceResult.SUCCESS;
                    } catch (QuoteParsers.InvalidResponseException e) {
                        cboeResult = SourceResult.INVALID_RESPONSE;
                        cboeDetail = e.getMessage();
                    }
                }
                case FetchAttempt.NoKey k -> cboeResult = SourceResult.NETWORK_ERROR;
            }
        } catch (RuntimeException e) {
            cboeResult = SourceResult.INVALID_RESPONSE;
            cboeDetail = e.getClass().getSimpleName();
            chain = null;
        }
        if (chain != null && chain.hasPrice()) {
            return new SymbolFetch(symbol, cboeResult, chain, cboeDetail, null, null, null);
        }
        Finnhub f = finnhub(symbol);
        return new SymbolFetch(symbol, cboeResult, chain, cboeDetail, f.result(), f.quote(), f.detail());
    }

    /**
     * FEAT-052 network part for one symbol: one Yahoo request per held expiration (ascending; one request without
     * {@code date} for a stock-only symbol), the session handshake when needed, Finnhub when Yahoo gave no price.
     * One failed request fails the whole symbol. Never throws.
     */
    SymbolFetch fetchYahoo(String symbol, List<LocalDate> dates, YahooRun run) {
        Map<LocalDate, YahooChain> chains = new LinkedHashMap<>();
        List<YahooChain> responses = new ArrayList<>();
        YahooOutcome outcome;
        try {
            outcome = yahooRequests(symbol, dates, run, chains, responses);
        } catch (RuntimeException e) {
            outcome = new YahooOutcome(SourceResult.INVALID_RESPONSE, "INVALID_RESPONSE (" + e.getClass().getSimpleName()
                    + ")");
        }
        YahooChain price = null;
        if (outcome.result() == SourceResult.SUCCESS) {
            for (YahooChain c : responses) {
                if (c.hasPrice()) {
                    price = c;
                }
            }
        } else {
            chains.clear();
        }
        if (price != null) {
            return SymbolFetch.yahoo(symbol, outcome.result(), chains, price, outcome.detail(), null, null, null);
        }
        Finnhub f = finnhub(symbol);
        return SymbolFetch.yahoo(symbol, outcome.result(), chains, null, outcome.detail(), f.result(), f.quote(),
                f.detail());
    }

    private YahooOutcome yahooRequests(String symbol, List<LocalDate> dates, YahooRun run,
                                       Map<LocalDate, YahooChain> chains, List<YahooChain> responses) {
        if (run.handshakeFailure != null) {
            return new YahooOutcome(SourceResult.HANDSHAKE_FAILED, run.handshakeFailure);
        }
        if (session.crumb() == null && !handshake(run)) {
            return new YahooOutcome(SourceResult.HANDSHAKE_FAILED, run.handshakeFailure);
        }
        List<LocalDate> requests = dates.isEmpty() ? Collections.singletonList(null) : dates;
        for (LocalDate date : requests) {
            String where = date == null ? "stock quote" : date.toString();
            FetchAttempt a = transport.yahooOptions(symbol, date, session.crumb());
            boolean retried = false;
            if (rejected(a) && !run.reHandshaked) {
                run.reHandshaked = true;
                session.clear();
                if (!handshake(run)) {
                    return new YahooOutcome(SourceResult.HANDSHAKE_FAILED, run.handshakeFailure);
                }
                a = transport.yahooOptions(symbol, date, session.crumb());
                retried = true;
            }
            String suffix = " (" + where + (retried ? ", after re-handshake" : "") + ")";
            switch (a) {
                case FetchAttempt.NetworkError e -> {
                    return new YahooOutcome(SourceResult.NETWORK_ERROR, e.detail() + suffix);
                }
                case FetchAttempt.Response r when r.status() != 200 -> {
                    return new YahooOutcome(SourceResult.HTTP_ERROR, "HTTP " + r.status() + suffix);
                }
                case FetchAttempt.Response r -> {
                    try {
                        YahooChain c = parsers.parseYahoo(r.body());
                        responses.add(c);
                        if (date != null) {
                            chains.put(date, c);
                        }
                    } catch (QuoteParsers.InvalidResponseException e) {
                        log.warn("Quotes: Yahoo response for {} ({}) is invalid: {}", symbol, where, e.getMessage());
                        return new YahooOutcome(SourceResult.INVALID_RESPONSE, "INVALID_RESPONSE" + suffix);
                    }
                }
                case FetchAttempt.NoKey k -> {
                    return new YahooOutcome(SourceResult.NETWORK_ERROR, "NETWORK_ERROR" + suffix);
                }
            }
        }
        return YahooOutcome.OK;
    }

    /** A new session; false (and the run's handshake failure set) when it failed. */
    private boolean handshake(YahooRun run) {
        QuoteTransport.Handshake h = transport.yahooHandshake();
        if (h instanceof QuoteTransport.Handshake.Ok ok) {
            session.set(ok.crumb());
            return true;
        }
        session.clear();
        run.handshakeFailure = "handshake failed (" + ((QuoteTransport.Handshake.Failed) h).detail() + ")";
        return false;
    }

    /** 401 / 403: the session was refused. */
    private static boolean rejected(FetchAttempt a) {
        return a instanceof FetchAttempt.Response r && (r.status() == 401 || r.status() == 403);
    }

    private record Finnhub(SourceResult result, FinnhubQuote quote, String detail) {
    }

    private Finnhub finnhub(String symbol) {
        try {
            FetchAttempt a = transport.finnhubQuote(symbol);
            return switch (a) {
                case FetchAttempt.NoKey k -> new Finnhub(SourceResult.NO_KEY, null, null);
                case FetchAttempt.NetworkError e -> new Finnhub(SourceResult.NETWORK_ERROR, null, e.detail());
                case FetchAttempt.Response r when r.status() != 200 ->
                        new Finnhub(SourceResult.HTTP_ERROR, null, "HTTP " + r.status());
                case FetchAttempt.Response r -> {
                    try {
                        yield new Finnhub(SourceResult.SUCCESS, parsers.parseFinnhub(r.body()), null);
                    } catch (QuoteParsers.InvalidResponseException e) {
                        yield new Finnhub(SourceResult.INVALID_RESPONSE, null, e.getMessage());
                    }
                }
            };
        } catch (RuntimeException e) {
            return new Finnhub(SourceResult.INVALID_RESPONSE, null, e.getClass().getSimpleName());
        }
    }

    private List<SymbolOutcome> apply(RunTrigger trigger, Instant runAt, HeldPositionsService.Tracked h,
                                      List<SymbolFetch> fetches, BigDecimal rate) {
        Map<String, UnderlyingQuoteEntity> uRows = underlyings.findAll().stream()
                .collect(Collectors.toMap(UnderlyingQuoteEntity::getSymbol, Function.identity()));
        Map<ContractKey, OptionQuoteEntity> oRows = new HashMap<>();
        options.findAll().forEach(e -> oRows.put(e.contract(), e));
        LocalDate today = MarketTime.toLocal(runAt).toLocalDate();
        Map<String, UnderlyingQuoteCloseEntity> uClose = new HashMap<>();
        Map<ContractKey, OptionQuoteCloseEntity> oClose = new HashMap<>();
        if (trigger == RunTrigger.CLOSE) {
            underlyingCloses.findBySnapshotDate(today).forEach(e -> uClose.put(e.getSymbol(), e));
            optionCloses.findBySnapshotDate(today).forEach(e -> oClose.put(e.contract(), e));
        }
        List<SymbolOutcome> outcomes = new ArrayList<>();
        boolean anyChain = false;
        for (SymbolFetch f : fetches) {
            List<String> extra = new ArrayList<>();
            if (f.chainOk()) {
                anyChain = true;
                Set<ContractKey> contracts = h.contractsOf(f.symbol());
                for (Match m : matches(f, contracts)) {
                    if (m.match() instanceof ChainMatch.Ambiguous a) {
                        extra.add("ambiguous: " + String.join(",", a.codes()));
                        log.warn("Quotes: {} matches several option codes {}", m.contract().label(), a.codes());
                    }
                    OptionQuoteEntity row = oRows.get(m.contract());
                    OptionState before = row == null ? null : row.toDomain();
                    OptionState after = f.optionSource() == QuoteSource.YAHOO
                            ? QuoteMerge.yahooOption(before, m.contract(), m.match(), m.yahoo(), rate, runAt)
                            : QuoteMerge.option(before, m.contract(), m.match(), f.chain().timestamp(), runAt);
                    if (after == null) {
                        continue;
                    }
                    if (row == null) {
                        row = new OptionQuoteEntity(m.contract());
                        oRows.put(m.contract(), row);
                    }
                    row.update(after);
                    options.save(row);
                    // Q142 / FEAT-052: the stored values (kept ones included) of contracts in the chain
                    if (trigger == RunTrigger.CLOSE && m.match().found() && after.quoted()) {
                        OptionQuoteCloseEntity c = oClose.computeIfAbsent(m.contract(),
                                k -> new OptionQuoteCloseEntity(today, after));
                        c.update(after, runAt);
                        optionCloses.save(c);
                    }
                }
            }
            String detail = f.failureDetail(extra);
            UnderlyingQuoteEntity u = uRows.computeIfAbsent(f.symbol(), UnderlyingQuoteEntity::new);
            UnderlyingState after = QuoteMerge.underlying(u.toDomain(), f, detail, runAt);
            u.update(after);
            underlyings.save(u);
            if (trigger == RunTrigger.CLOSE && f.priceOk()) {
                UnderlyingQuoteCloseEntity c = uClose.computeIfAbsent(f.symbol(),
                        s -> new UnderlyingQuoteCloseEntity(today, s));
                c.update(after, runAt);
                underlyingCloses.save(c);
            }
            outcomes.add(new SymbolOutcome(f.symbol(), f.result(), f.optionSource(), f.yahooResult(), f.cboeResult(),
                    f.finnhubResult(), f.priceSource(), detail));
        }
        MarketDataStatusEntity status = statusRow();
        status.recordRun(runAt, fetches.isEmpty() || anyChain);
        statuses.save(status);
        return outcomes;
    }

    /** How one held contract appears; {@code yahoo} = the Yahoo response of its expiration (Yahoo source only). */
    private record Match(ContractKey contract, ChainMatch match, YahooChain yahoo) {
    }

    private static List<Match> matches(SymbolFetch f, Set<ContractKey> contracts) {
        List<Match> out = new ArrayList<>();
        if (f.optionSource() != QuoteSource.YAHOO) {
            ChainMatcher.match(f.symbol(), f.chain(), contracts).forEach((k, m) -> out.add(new Match(k, m, null)));
            return out;
        }
        Map<LocalDate, List<ContractKey>> byDate = new TreeMap<>();
        contracts.forEach(k -> byDate.computeIfAbsent(k.expirationDate(), d -> new ArrayList<>()).add(k));
        for (Map.Entry<LocalDate, List<ContractKey>> e : byDate.entrySet()) {
            YahooChain chain = f.yahooChains().get(e.getKey());
            if (chain == null || !chain.listsExpiration(e.getKey())) {
                // Yahoo answered another expiration's chain: not in the source (rule 8)
                e.getValue().forEach(k -> out.add(new Match(k, new ChainMatch.Absent(), chain)));
            } else {
                ChainMatcher.matchYahoo(f.symbol(), chain, e.getValue())
                        .forEach((k, m) -> out.add(new Match(k, m, chain)));
            }
        }
        return out;
    }
}
