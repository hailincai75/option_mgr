package com.optionmgr.calendar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.AdhocClosureDetector;
import com.optionmgr.calendar.domain.ExchangeHoliday;
import com.optionmgr.calendar.domain.FetchResult;
import com.optionmgr.calendar.domain.FetchStatus;
import com.optionmgr.calendar.domain.HolidayRules;
import com.optionmgr.calendar.repository.AdhocClosureEntity;
import com.optionmgr.calendar.repository.AdhocClosureRepository;
import com.optionmgr.calendar.repository.FetchStatusEntity;
import com.optionmgr.calendar.repository.FetchStatusRepository;
import com.optionmgr.calendar.repository.MarketHolidayEntity;
import com.optionmgr.calendar.repository.MarketHolidayRepository;
import com.optionmgr.common.time.MarketTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FEAT-009 rules 2 and 5: fetches the Finnhub market holidays and stores them. On success the years present in the
 * response replace the stored years whole (Q127), other stored years are kept; on any failure the stored holidays
 * are untouched and only the status is recorded. The network call runs outside the database transaction. The key
 * is never logged or stored. FEAT-042: a successful fetch also detects ad-hoc closures against the data stored
 * before its merge (same transaction); a failed one changes nothing.
 */
@Service
public class HolidayFetchService {

    private static final Logger log = LoggerFactory.getLogger(HolidayFetchService.class);

    private final FinnhubProperties props;
    private final FinnhubClient client;
    private final FinnhubHolidayParser parser;
    private final MarketHolidayRepository holidays;
    private final FetchStatusRepository statuses;
    private final AdhocClosureRepository adhocClosures;
    private final HolidayRules rules;
    private final TransactionTemplate tx;
    private final Clock clock;

    public HolidayFetchService(FinnhubProperties props, FinnhubClient client, ObjectMapper mapper,
                               MarketHolidayRepository holidays, FetchStatusRepository statuses,
                               AdhocClosureRepository adhocClosures, HolidayRules rules,
                               PlatformTransactionManager txManager, Clock clock) {
        this.adhocClosures = adhocClosures;
        this.rules = rules;
        this.props = props;
        this.client = client;
        this.parser = new FinnhubHolidayParser(mapper);
        this.holidays = holidays;
        this.statuses = statuses;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** One real fetch: resolve the key, call Finnhub (unless there is no key), then {@link #process}. */
    public FetchStatus fetchNow() {
        Map<String, String> env = props.readSystemEnv() ? System.getenv() : Map.of();
        List<Path> files = props.envFiles().stream().filter(s -> !s.isBlank()).map(Path::of).toList();
        Optional<String> key = FinnhubKeyResolver.resolve(props.apiKey(), env, files);
        FetchAttempt attempt = key.isEmpty() ? new FetchAttempt.NoKey() : client.fetch(key.get());
        return process(attempt);
    }

    /**
     * Applies the outcome of a request (real, or simulated by the e2e test support) at the current clock time:
     * parse, merge, record the status - all in one transaction.
     */
    public FetchStatus process(FetchAttempt attempt) {
        FetchStatus result = tx.execute(s -> apply(attempt));
        if (result.lastAttemptResult() == FetchResult.SUCCESS) {
            log.info("Market holidays updated from Finnhub (years {})", coveredYearsAfterSuccess());
        } else {
            log.warn("Market holiday fetch failed: {}{}", result.lastAttemptResult(),
                    result.lastFailureDetail() == null ? "" : " (" + result.lastFailureDetail() + ")");
        }
        return result;
    }

    private FetchStatus apply(FetchAttempt attempt) {
        FetchStatusEntity row = statuses.lockSingleRow()
                .orElseThrow(() -> new IllegalStateException("market_holiday_fetch_status row missing"));
        Instant now = clock.instant();
        FetchStatus before = row.toDomain();
        FetchStatus after = switch (attempt) {
            case FetchAttempt.NoKey k -> before.after(now, FetchResult.NO_KEY, null);
            case FetchAttempt.NetworkError e -> before.after(now, FetchResult.NETWORK_ERROR, e.detail());
            case FetchAttempt.Response r when r.status() != 200 ->
                    before.after(now, FetchResult.HTTP_ERROR, "HTTP " + r.status());
            case FetchAttempt.Response r -> parseAndMerge(before, r.body(), now);
        };
        row.update(after);
        statuses.save(row);
        return after;
    }

    private FetchStatus parseAndMerge(FetchStatus before, String body, Instant now) {
        List<ExchangeHoliday> parsed;
        try {
            parsed = parser.parse(body);
        } catch (FinnhubHolidayParser.InvalidResponseException e) {
            return before.after(now, FetchResult.INVALID_RESPONSE, e.getMessage());
        }
        detectAdhocClosures(parsed, now);
        SortedSet<Integer> years = new TreeSet<>();
        parsed.forEach(h -> years.add(h.date().getYear()));
        for (int year : years) {
            holidays.deleteBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        holidays.saveAll(parsed.stream().map(h -> new MarketHolidayEntity(h, now)).toList());
        return before.after(now, FetchResult.SUCCESS, null);
    }

    /** FEAT-042 rules 1-2, Q127: runs before the merge, so the stored data is still the previous fetch's. */
    private void detectAdhocClosures(List<ExchangeHoliday> parsed, Instant now) {
        List<ExchangeHoliday> storedBefore = holidays.findAll().stream().map(MarketHolidayEntity::toDomain).toList();
        Map<LocalDate, AdhocClosureEntity> rows = new HashMap<>();
        Map<LocalDate, AdhocClosure> existing = new HashMap<>();
        for (AdhocClosureEntity e : adhocClosures.findAll()) {
            AdhocClosure c = e.toDomain();
            rows.put(c.date(), e);
            existing.put(c.date(), c);
        }
        LocalDate today = MarketTime.toLocal(now).toLocalDate();
        for (AdhocClosure c : AdhocClosureDetector.changes(parsed, storedBefore, rules, existing, today)) {
            AdhocClosureEntity row = rows.get(c.date());
            if (row == null) {
                adhocClosures.save(new AdhocClosureEntity(c));
                log.warn("Ad-hoc market closure detected: {} {}", c.date(), c.eventName());
            } else {
                row.update(c);
                adhocClosures.save(row);
                log.info("Ad-hoc market closure {} is now {}", c.date(), c.status());
            }
        }
    }

    private SortedSet<Integer> coveredYearsAfterSuccess() {
        SortedSet<Integer> years = new TreeSet<>();
        holidays.findAll().forEach(h -> years.add(h.getHolidayDate().getYear()));
        return years;
    }
}
