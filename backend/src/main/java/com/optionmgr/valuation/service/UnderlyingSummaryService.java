package com.optionmgr.valuation.service;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.PnlSummary;
import com.optionmgr.trade.service.OptionPositionService;
import com.optionmgr.trade.service.PnlSummaryService;
import com.optionmgr.valuation.domain.UnderlyingSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
        return new Result(s.asOfDate(), summary(accountId, s, quotes.book()));
    }

    /** FEAT-047: the groups of several accounts, each tagged with its account. */
    public record ScopeResult(LocalDate asOfDate, List<AccountRows.Row<UnderlyingSummary.Group>> groups) {
    }

    /**
     * FEAT-047 (Q224 A, Q53): one group per account + symbol, each computed exactly as {@link #summary(long)} for its
     * own account (realized = that account's {@code pnl-summary.bySymbol} row); one clock reading and one
     * {@code QuoteBook} for all accounts. Sorted by symbol, then account id ascending (Q203 + Q214).
     *
     * @param accounts account id to its current name, for every account of the scope
     */
    @Transactional(readOnly = true)
    public ScopeResult summary(Map<Long, String> accounts) {
        List<Long> ids = accounts.keySet().stream().sorted().toList();
        List<OptionPositionService.PositionSummary> summaries = options.currentPositions(ids);
        QuoteViews.QuoteBook book = quotes.book();
        List<AccountRows.Row<UnderlyingSummary.Group>> rows = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            for (UnderlyingSummary.Group g : summary(id, summaries.get(i), book).groups()) {
                rows.add(new AccountRows.Row<>(id, accounts.get(id), g));
            }
        }
        LocalDate asOfDate = summaries.isEmpty() ? null : summaries.get(0).asOfDate();
        return new ScopeResult(asOfDate,
                AccountRows.sorted(rows, Comparator.comparing(UnderlyingSummary.Group::symbol)));
    }

    private UnderlyingSummary summary(long accountId, OptionPositionService.PositionSummary s,
                                      QuoteViews.QuoteBook book) {
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
        return UnderlyingSummary.of(optionRows, stockRows, realized::get);
    }
}
