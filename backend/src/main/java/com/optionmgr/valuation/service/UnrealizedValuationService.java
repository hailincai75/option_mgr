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
import com.optionmgr.valuation.domain.GreeksValuation;
import com.optionmgr.valuation.domain.RowGreeks;
import com.optionmgr.valuation.domain.RowValuation;
import com.optionmgr.valuation.domain.UnrealizedSummary;
import com.optionmgr.valuation.domain.UnrealizedValuation;
import java.time.LocalDate;
import java.util.List;
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
        OptionPositionService.PositionSummary s = options.currentPositions(accountId);
        QuoteViews.QuoteBook book = quotes.book();
        List<RowValuation> optionRows = s.positions().stream()
                .map(p -> option(p, s.asOfDate(), book.option(p.contract()))).toList();
        List<RowValuation> stockRows = stocks.currentPositions(accountId).stream()
                .map(p -> stock(p, book.underlying(p.symbol()))).toList();
        return new Summary(s.asOfDate(), UnrealizedSummary.of(optionRows, stockRows));
    }
}
