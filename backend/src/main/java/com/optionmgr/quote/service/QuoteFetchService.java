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
import com.optionmgr.quote.domain.QuoteMerge;
import com.optionmgr.quote.domain.RunTrigger;
import com.optionmgr.quote.domain.SourceResult;
import com.optionmgr.quote.domain.SymbolFetch;
import com.optionmgr.quote.domain.SymbolResult;
import com.optionmgr.quote.domain.UnderlyingState;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FEAT-016 rules 1, 2, 5, 6: one fetch run over the tracked symbols (held in any account, index symbols excluded).
 * Runs are serialized; the network calls happen outside the database transaction, the results are applied in one
 * transaction. A single symbol's failure never stops the run and never clears stored data.
 */
@Service
public class QuoteFetchService {

    private static final Logger log = LoggerFactory.getLogger(QuoteFetchService.class);

    public static final Duration REFRESH_COOLDOWN = Duration.ofSeconds(60);

    /** One symbol's outcome in a run. */
    public record SymbolOutcome(String symbol, SymbolResult result, SourceResult cboeResult,
                                SourceResult finnhubResult, com.optionmgr.quote.domain.PriceSource underlyingPriceSource,
                                String failureDetail) {
    }

    /** {@code performed = false}: refresh refused by the cooldown. */
    public record RefreshOutcome(boolean performed, String reason, List<SymbolOutcome> results) {
    }

    /** Simulated scheduler tick (e2e) or a real one: {@code ran} with its trigger, or the skip reason. */
    public record TickOutcome(boolean ran, RunTrigger trigger, String skipReason, List<SymbolOutcome> results) {
    }

    private final QuoteProperties props;
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

    public QuoteFetchService(QuoteProperties props, QuoteTransport transport, HeldPositionsService held,
                             UnderlyingQuoteRepository underlyings, OptionQuoteRepository options,
                             UnderlyingQuoteCloseRepository underlyingCloses, OptionQuoteCloseRepository optionCloses,
                             MarketDataStatusRepository statuses, TradingCalendarService calendar,
                             QuoteWriteLock writeLock, PlatformTransactionManager txManager, Clock clock) {
        this.props = props;
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

    /** The scheduler at the current clock: runs only at a Q138 slot on a FEAT-009 trading day. */
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
        HeldPositionsService.Tracked h = held.current()
                .tracked(MarketTime.toLocal(runAt).toLocalDate(), props::isIndex);
        List<String> symbols = h.symbols().stream().sorted().toList();
        List<SymbolFetch> fetches = new ArrayList<>();
        for (String symbol : symbols) {
            fetches.add(fetchSymbol(symbol));
        }
        List<SymbolOutcome> outcomes = new ArrayList<>();
        writeLock.lock();
        try {
            tx.executeWithoutResult(s -> outcomes.addAll(apply(trigger, runAt, h, fetches)));
        } finally {
            writeLock.unlock();
        }
        long ok = outcomes.stream().filter(o -> o.result() == SymbolResult.SUCCESS).count();
        if (ok == outcomes.size()) {
            log.info("Quotes {} run: {} symbols updated", trigger, ok);
        } else {
            log.warn("Quotes {} run: {} of {} symbols without chain ({})", trigger, outcomes.size() - ok, outcomes.size(),
                    outcomes.stream().filter(o -> o.result() != SymbolResult.SUCCESS)
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
        SourceResult fResult;
        FinnhubQuote quote = null;
        String fDetail = null;
        try {
            FetchAttempt a = transport.finnhubQuote(symbol);
            switch (a) {
                case FetchAttempt.NoKey k -> fResult = SourceResult.NO_KEY;
                case FetchAttempt.NetworkError e -> {
                    fResult = SourceResult.NETWORK_ERROR;
                    fDetail = e.detail();
                }
                case FetchAttempt.Response r when r.status() != 200 -> {
                    fResult = SourceResult.HTTP_ERROR;
                    fDetail = "HTTP " + r.status();
                }
                case FetchAttempt.Response r -> {
                    try {
                        quote = parsers.parseFinnhub(r.body());
                        fResult = SourceResult.SUCCESS;
                    } catch (QuoteParsers.InvalidResponseException e) {
                        fResult = SourceResult.INVALID_RESPONSE;
                        fDetail = e.getMessage();
                    }
                }
            }
        } catch (RuntimeException e) {
            fResult = SourceResult.INVALID_RESPONSE;
            fDetail = e.getClass().getSimpleName();
            quote = null;
        }
        return new SymbolFetch(symbol, cboeResult, chain, cboeDetail, fResult, quote, fDetail);
    }

    private List<SymbolOutcome> apply(RunTrigger trigger, Instant runAt, HeldPositionsService.Tracked h,
                                      List<SymbolFetch> fetches) {
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
                Map<ContractKey, ChainMatch> matches = ChainMatcher.match(f.symbol(), f.chain(), contracts);
                for (Map.Entry<ContractKey, ChainMatch> m : matches.entrySet()) {
                    if (m.getValue() instanceof ChainMatch.Ambiguous a) {
                        extra.add("ambiguous: " + String.join(",", a.codes()));
                        log.warn("Quotes: {} matches several option codes {}", m.getKey().label(), a.codes());
                    }
                    OptionQuoteEntity row = oRows.get(m.getKey());
                    OptionState before = row == null ? null : row.toDomain();
                    OptionState after = QuoteMerge.option(before, m.getKey(), m.getValue(), f.chain().timestamp(), runAt);
                    if (after == null) {
                        continue;
                    }
                    if (row == null) {
                        row = new OptionQuoteEntity(m.getKey());
                        oRows.put(m.getKey(), row);
                    }
                    row.update(after);
                    options.save(row);
                    if (trigger == RunTrigger.CLOSE && m.getValue() instanceof ChainMatch.Found) {
                        OptionQuoteCloseEntity c = oClose.computeIfAbsent(m.getKey(),
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
            outcomes.add(new SymbolOutcome(f.symbol(), f.result(), f.cboeResult(), f.finnhubResult(), f.priceSource(),
                    detail));
        }
        MarketDataStatusEntity status = statusRow();
        status.recordRun(runAt, fetches.isEmpty() || anyChain);
        statuses.save(status);
        return outcomes;
    }
}
