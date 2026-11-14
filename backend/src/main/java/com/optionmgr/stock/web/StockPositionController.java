package com.optionmgr.stock.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.stock.domain.StockLot;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.stock.web.StockTradeDtos.StockLotResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockPositionResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockPositionsResponse;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.trade.service.CoverageService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-011 current stock positions (with FIFO lots) of the account. */
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
    public StockPositionsResponse list(AccountRef account) {
        QuoteViews.QuoteBook book = quotes.book();
        List<StockPositionResponse> items = positions.currentPositions(account.id()).stream()
                .map(p -> toResponse(p, coverage.coveredShares(account.id(), p.symbol()), book)).toList();
        return new StockPositionsResponse(items.size(), items);
    }

    private static StockPositionResponse toResponse(StockPosition p, long covered, QuoteViews.QuoteBook book) {
        return new StockPositionResponse(p.symbol(), p.shares(), covered, p.shares() - covered, p.totalCost(),
                p.averageCost(), p.lots().size(),
                p.lots().stream().map(StockPositionController::toLot).toList(), book.underlying(p.symbol()));
    }

    private static StockLotResponse toLot(StockLot l) {
        return new StockLotResponse(l.sourceId(), l.type(), l.tradedAt(), StockTradeController.local(l.tradedAt()),
                StockTradeController.transferDate(l.type(), l.tradedAt()), l.originalShares(), l.remainingShares(),
                l.originalCost(), l.remainingCost(), l.note());
    }
}
