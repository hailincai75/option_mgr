package com.optionmgr.valuation.service;

import com.optionmgr.quote.domain.ContractStatus;
import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.service.OptionPositionService;
import com.optionmgr.valuation.domain.GreeksSummary;
import com.optionmgr.valuation.domain.GreeksValuation;
import com.optionmgr.valuation.domain.RowGreeks;
import com.optionmgr.valuation.domain.RowValuation;
import com.optionmgr.valuation.domain.UnrealizedSummary;
import com.optionmgr.valuation.domain.UnrealizedValuation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-017: values position rows with the FEAT-016 displayed mark / price. The position endpoints call the static
 * row methods while building their rows; the summary endpoint uses {@link #summary(long)} — same code, so the totals
 * equal the sums of the OK rows.
 */
@Service
public class UnrealizedValuationService {

    private final OptionPositionService options;
    private final StockPositionService stocks;
    private final QuoteQueryService quotes;

    public UnrealizedValuationService(OptionPositionService options, StockPositionService stocks,
                                      QuoteQueryService quotes) {
        this.options = options;
        this.stocks = stocks;
        this.quotes = quotes;
    }

    /** One option row; {@code quote} is the row's {@code optionQuote}. */
    public static RowValuation option(OptionPosition p, LocalDate today, QuoteViews.OptionQuote quote) {
        return UnrealizedValuation.option(p.direction() == PositionDirection.LONG, p.contracts(), p.multiplier(),
                p.openingNetTotal(), p.contract().expirationDate(), today, quote == null ? null : quote.mark());
    }

    /**
     * FEAT-018: IV / Greeks of one option row. {@code quote} is the row's {@code optionQuote} (decides "never quoted",
     * index contracts included, and the manual flag / quote time); {@code stored} holds the stored CBOE values.
     */
    public static RowGreeks greeks(OptionPosition p, LocalDate today, QuoteViews.OptionQuote quote,
                                   OptionState stored) {
        GreeksValuation.Quote q = GreeksValuation.Quote.NONE;
        if (quote != null && quote.contractStatus() != ContractStatus.NEVER_QUOTED && stored != null) {
            q = new GreeksValuation.Quote(true, quote.manual(), quote.quoteTime(), stored.iv(), stored.delta(),
                    stored.gamma(), stored.theta(), stored.vega());
        } else if (quote != null) {
            q = new GreeksValuation.Quote(false, quote.manual(), quote.quoteTime(), null, null, null, null, null);
        }
        return GreeksValuation.option(p.direction() == PositionDirection.LONG, p.contracts(), p.multiplier(),
                p.contract().expirationDate(), today, q);
    }

    /** One stock row; {@code quote} is the row's {@code quote}. */
    public static RowValuation stock(StockPosition p, QuoteViews.UnderlyingQuote quote) {
        return UnrealizedValuation.stock(p.shares(), p.totalCost(), quote == null ? null : quote.price());
    }

    public record Summary(LocalDate asOfDate, UnrealizedSummary totals) {
    }

    /** All current positions of the account (no filter, Q158). */
    @Transactional(readOnly = true)
    public Summary summary(long accountId) {
        return summary(List.of(accountId));
    }

    /**
     * FEAT-035 (Q216): totals over several accounts = the totals of all their rows, each row valued exactly as in its
     * own account; one clock reading and one {@code QuoteBook} for all accounts.
     */
    @Transactional(readOnly = true)
    public Summary summary(List<Long> accountIds) {
        List<OptionPositionService.PositionSummary> summaries = options.currentPositions(accountIds);
        QuoteViews.QuoteBook book = quotes.book();
        List<RowValuation> optionRows = new ArrayList<>();
        List<RowValuation> stockRows = new ArrayList<>();
        for (int i = 0; i < accountIds.size(); i++) {
            OptionPositionService.PositionSummary s = summaries.get(i);
            s.positions().forEach(p -> optionRows.add(option(p, s.asOfDate(), book.option(p.contract()))));
            stocks.currentPositions(accountIds.get(i))
                    .forEach(p -> stockRows.add(stock(p, book.underlying(p.symbol()))));
        }
        LocalDate asOfDate = summaries.isEmpty() ? null : summaries.get(0).asOfDate();
        return new Summary(asOfDate, UnrealizedSummary.of(optionRows, stockRows));
    }

    public record GreeksSummaryResult(LocalDate asOfDate, GreeksSummary summary) {
    }

    /**
     * FEAT-019: Greeks per underlying and for the account. All current positions (no filter, Q174); each option row
     * goes through {@link #greeks} with one {@code QuoteBook}, exactly as the position rows do.
     */
    @Transactional(readOnly = true)
    public GreeksSummaryResult greeksSummary(long accountId) {
        return greeksSummary(List.of(accountId));
    }

    /**
     * FEAT-047 (Q224 Greeks B, Q233 A): Greeks per underlying over several accounts — every account's option rows
     * (each through the same {@link #greeks}) and the summed shares per symbol go into one {@link GreeksSummary}, so a
     * symbol held in several accounts is one group and its dollar exposure is the merged net delta times the price,
     * rounded once. One clock reading and one {@code QuoteBook} for all accounts.
     */
    @Transactional(readOnly = true)
    public GreeksSummaryResult greeksSummary(List<Long> accountIds) {
        List<OptionPositionService.PositionSummary> summaries = options.currentPositions(accountIds);
        QuoteViews.QuoteBook book = quotes.book();
        List<GreeksSummary.OptionRow> rows = new ArrayList<>();
        Map<String, Long> shares = new HashMap<>();
        for (int i = 0; i < accountIds.size(); i++) {
            OptionPositionService.PositionSummary s = summaries.get(i);
            s.positions().forEach(p -> rows.add(new GreeksSummary.OptionRow(p.contract().symbol(),
                    greeks(p, s.asOfDate(), book.option(p.contract()), book.stored(p.contract())))));
            for (StockPosition p : stocks.currentPositions(accountIds.get(i))) {
                shares.merge(p.symbol(), p.shares(), Long::sum);
            }
        }
        GreeksSummary summary = GreeksSummary.of(rows, shares, symbol -> {
            QuoteViews.UnderlyingQuote q = book.underlying(symbol);
            return q == null ? GreeksSummary.Underlying.NONE : new GreeksSummary.Underlying(q.price(), q.manual());
        });
        LocalDate asOfDate = summaries.isEmpty() ? null : summaries.get(0).asOfDate();
        return new GreeksSummaryResult(asOfDate, summary);
    }
}
