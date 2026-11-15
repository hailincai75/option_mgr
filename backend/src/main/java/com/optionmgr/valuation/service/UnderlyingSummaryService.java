package com.optionmgr.valuation.service;

import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.PnlSummary;
import com.optionmgr.trade.service.OptionPositionService;
import com.optionmgr.trade.service.PnlSummaryService;
import com.optionmgr.valuation.domain.UnderlyingSummary;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-023: per-underlying summary of the account's current positions. Rows are valued with the FEAT-017 row methods
 * of {@link UnrealizedValuationService} (one {@code QuoteBook}, the positions' own as-of date), and realized P&amp;L
 * is the by-symbol breakdown of {@link PnlSummaryService#summary} without dates — the same code as
 * {@code GET /api/pnl-summary} — so every number cross-checks with those endpoints by construction.
 */
@Service
public class UnderlyingSummaryService {

    private final OptionPositionService options;
    private final StockPositionService stocks;
    private final QuoteQueryService quotes;
    private final PnlSummaryService pnl;

    public UnderlyingSummaryService(OptionPositionService options, StockPositionService stocks,
                                    QuoteQueryService quotes, PnlSummaryService pnl) {
        this.options = options;
        this.stocks = stocks;
        this.quotes = quotes;
        this.pnl = pnl;
    }

    public record Result(LocalDate asOfDate, UnderlyingSummary summary) {
    }

    /** All current positions of the account (no expiration filter, Q158). */
    @Transactional(readOnly = true)
    public Result summary(long accountId) {
        OptionPositionService.PositionSummary s = options.currentPositions(accountId);
        QuoteViews.QuoteBook book = quotes.book();
        List<UnderlyingSummary.OptionRow> optionRows = s.positions().stream()
                .map(p -> new UnderlyingSummary.OptionRow(p.contract().symbol(), p.openingNetTotal(),
                        UnrealizedValuationService.option(p, s.asOfDate(), book.option(p.contract()))))
                .toList();
        List<UnderlyingSummary.StockRow> stockRows = stocks.currentPositions(accountId).stream()
                .map(p -> new UnderlyingSummary.StockRow(p.symbol(),
                        UnrealizedValuationService.stock(p, book.underlying(p.symbol()))))
                .toList();
        Map<String, PnlSummary.Breakdown> realized = new HashMap<>();
        for (PnlSummary.SymbolGroup g : pnl.summary(accountId, null, null).bySymbol()) {
            realized.put(g.symbol(), g.breakdown());
        }
        return new Result(s.asOfDate(), UnderlyingSummary.of(optionRows, stockRows, realized::get));
    }
}
