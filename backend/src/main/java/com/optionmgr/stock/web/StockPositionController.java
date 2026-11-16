package com.optionmgr.stock.web;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.account.web.AccountScope;
import com.optionmgr.stock.domain.StockLot;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.stock.web.StockTradeDtos.StockLotResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockPositionResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockPositionsResponse;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.trade.service.CoverageService;
import com.optionmgr.valuation.domain.RowValuation;
import com.optionmgr.valuation.service.UnrealizedValuationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEAT-011 current stock positions (with FIFO lots) of the account; FEAT-035: of all accounts with
 * {@code X-Account-Id: ALL} (one row per account + symbol, by symbol then account id; {@code positionCount} counts
 * distinct symbols).
 */
@RestController
@RequestMapping("/api/stock-positions")
public class StockPositionController {

    private final StockPositionService positions;
    private final CoverageService coverage;
    private final QuoteQueryService quotes;

    public StockPositionController(StockPositionService positions, CoverageService coverage, QuoteQueryService quotes) {
        this.positions = positions;
        this.coverage = coverage;
        this.quotes = quotes;
    }

    @GetMapping
    public StockPositionsResponse list(AccountScope scope) {
        QuoteViews.QuoteBook book = quotes.book();
        List<AccountRows.Row<StockPosition>> rows = new ArrayList<>();
        for (AccountScope.Member m : scope.accounts()) {
            positions.currentPositions(m.id()).forEach(p -> rows.add(new AccountRows.Row<>(m.id(), m.name(), p)));
        }
        List<StockPositionResponse> items = AccountRows.sorted(rows, StockPosition.DISPLAY_ORDER).stream()
                .map(r -> toResponse(r, coverage.coveredShares(r.accountId(), r.value().symbol()), book)).toList();
        // FEAT-035: positionCount = distinct symbols (the same symbol held in two accounts counts 1; rows stay split).
        int symbolCount = (int) items.stream().map(StockPositionResponse::symbol).distinct().count();
        return new StockPositionsResponse(symbolCount, items);
    }

    private static StockPositionResponse toResponse(AccountRows.Row<StockPosition> row, long covered,
                                                    QuoteViews.QuoteBook book) {
        StockPosition p = row.value();
        QuoteViews.UnderlyingQuote quote = book.underlying(p.symbol());
        RowValuation v = UnrealizedValuationService.stock(p, quote);
        List<RowValuation> lotValues = UnrealizedValuationService.stockLots(p, v, quote);
        List<StockLotResponse> lots = new ArrayList<>(p.lots().size());
        for (int i = 0; i < p.lots().size(); i++) {
            lots.add(toLot(p.lots().get(i), lotValues.get(i)));
        }
        return new StockPositionResponse(row.accountId(), row.accountName(), p.symbol(), p.shares(), covered,
                p.shares() - covered, p.totalCost(), p.averageCost(), p.lots().size(),
                lots, quote,
                v.marketValue(), v.unrealizedPnl(), v.percent(), v.status(), v.label());
    }

    private static StockLotResponse toLot(StockLot l, RowValuation v) {
        return new StockLotResponse(l.sourceId(), l.type(), l.tradedAt(), StockTradeController.local(l.tradedAt()),
                StockTradeController.transferDate(l.type(), l.tradedAt()), l.originalShares(), l.remainingShares(),
                l.originalCost(), l.remainingCost(), l.note(), v.marketValue(), v.unrealizedPnl(), v.percent(),
                v.status(), v.label());
    }
}
