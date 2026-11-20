package com.optionmgr.quote.service;

import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.QuoteLabels;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.quote.domain.UnderlyingState;
import com.optionmgr.quote.repository.MarketDataStatusEntity;
import com.optionmgr.quote.repository.MarketDataStatusRepository;
import com.optionmgr.quote.repository.OptionQuoteEntity;
import com.optionmgr.quote.repository.OptionQuoteRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteEntity;
import com.optionmgr.quote.repository.UnderlyingQuoteRepository;
import com.optionmgr.trade.domain.ContractKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-016: reads for the position rows and the market data status. */
@Service
public class QuoteQueryService {

    private final UnderlyingQuoteRepository underlyings;
    private final OptionQuoteRepository options;
    private final MarketDataStatusRepository statuses;
    private final HeldPositionsService held;
    private final TradingCalendarService calendar;
    private final QuoteProperties props;
    private final QuoteSettings settings;
    private final Clock clock;

    public QuoteQueryService(UnderlyingQuoteRepository underlyings, OptionQuoteRepository options,
                             MarketDataStatusRepository statuses, HeldPositionsService held,
                             TradingCalendarService calendar, QuoteProperties props, QuoteSettings settings,
                             Clock clock) {
        this.settings = settings;
        this.underlyings = underlyings;
        this.options = options;
        this.statuses = statuses;
        this.held = held;
        this.calendar = calendar;
        this.props = props;
        this.clock = clock;
    }

    /** All stored quotes (small tables: held symbols / contracts only), with what is tracked today (Q150). */
    @Transactional(readOnly = true)
    public QuoteViews.QuoteBook book() {
        java.time.LocalDate today = MarketTime.toLocal(clock.instant()).toLocalDate();
        HeldPositionsService.Tracked tracked = held.current().tracked(today, props::isIndex);
        Map<String, UnderlyingState> u = new HashMap<>();
        for (UnderlyingQuoteEntity e : underlyings.findAll()) {
            u.put(e.getSymbol(), e.toDomain());
        }
        Map<ContractKey, OptionState> o = new HashMap<>();
        for (OptionQuoteEntity e : options.findAll()) {
            o.put(e.contract(), e.toDomain());
        }
        return new QuoteViews.QuoteBook(Map.copyOf(u), Map.copyOf(o), props.indexSymbolSet(), today,
                tracked.symbols());
    }

    /** {@code GET /api/market-data/status}: stale / warning computed now (Q141). */
    @Transactional(readOnly = true)
    public QuoteViews.Status status() {
        Instant now = clock.instant();
        MarketDataStatusEntity row = statuses.findById((short) 1)
                .orElseThrow(() -> new IllegalStateException("market_data_status row missing"));
        int tracked = held.current().tracked(MarketTime.toLocal(now).toLocalDate(), props::isIndex).symbols().size();
        boolean tradingDay = calendar.calendar().isTradingDay(MarketTime.toLocal(now).toLocalDate());
        Optional<String> warning = QuoteLabels.warning(tracked > 0, row.getLastSuccessAt(), now, tradingDay);
        Instant refresh = row.getLastRefreshAt();
        Instant available = refresh == null ? null : refresh.plus(QuoteFetchService.REFRESH_COOLDOWN);
        return new QuoteViews.Status(warning.isPresent(), warning.orElse(null), row.getLastSuccessAt(),
                QuoteViews.local(row.getLastSuccessAt()), row.getLastAttemptAt(), QuoteViews.local(row.getLastAttemptAt()),
                refresh, QuoteViews.local(refresh), available, available == null || !now.isBefore(available), tracked,
                settings.optionSource(), QuotePrices.display(settings.riskFreeRatePercent()));
    }
}
