package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.trade.domain.PnlSummary;
import com.optionmgr.trade.domain.TradeFilter;
import com.optionmgr.trade.service.PnlSummaryService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-008: home overview and realized P&amp;L summary (read-only); FEAT-040 includes stock sales. */
@RestController
public class PnlSummaryController {

    private final PnlSummaryService service;

    public PnlSummaryController(PnlSummaryService service) {
        this.service = service;
    }

    public record CashPart(BigDecimal totalCash, BigDecimal frozenCash, BigDecimal availableCash) {
    }

    public record PnlPart(int closedCount, BigDecimal total) {
    }

    public record PositionsPart(int positionCount, BigDecimal frozenTotal, int expiringWithin7DaysCount,
                                int expiredUnprocessedCount) {
    }

    /** FEAT-040 (Q89): stock positions = symbols with shares &gt; 0. */
    public record StockPositionsPart(int positionCount) {
    }

    public record DashboardResponse(String asOfDate, String currentMonth, CashPart cash, PnlPart realizedPnl,
                                    PnlPart currentMonthRealizedPnl, PositionsPart positions,
                                    StockPositionsPart stockPositions) {
    }

    /** FEAT-040 (Q91): count and realized P&amp;L of one record type. */
    public record TypePart(int closedCount, BigDecimal realizedPnl) {
        static TypePart of(PnlSummary.Group g) {
            return new TypePart(g.closedCount(), g.realizedPnl());
        }
    }

    public record SymbolRow(String symbol, int closedCount, BigDecimal realizedPnl, TypePart option, TypePart stock) {
    }

    public record MonthRow(String month, int closedCount, BigDecimal realizedPnl, TypePart option, TypePart stock) {
    }

    public record PnlSummaryResponse(String startDate, String endDate, int closedCount, BigDecimal realizedPnlTotal,
                                     TypePart option, TypePart stock, List<SymbolRow> bySymbol,
                                     List<MonthRow> byMonth) {
    }

    @GetMapping("/api/dashboard")
    public DashboardResponse dashboard(AccountRef account) {
        PnlSummaryService.Dashboard d = service.dashboard(account.id());
        return new DashboardResponse(d.asOfDate().toString(), d.currentMonth().toString(),
                new CashPart(d.cash().total(), d.cash().frozen(), d.cash().available()),
                new PnlPart(d.realized().closedCount(), d.realized().realizedPnl()),
                new PnlPart(d.currentMonthRealized().closedCount(), d.currentMonthRealized().realizedPnl()),
                new PositionsPart(d.positionCount(), d.frozenTotal(), d.expiringWithin7DaysCount(),
                        d.expiredUnprocessedCount()),
                new StockPositionsPart(d.stockPositionCount()));
    }

    @GetMapping("/api/pnl-summary")
    public PnlSummaryResponse summary(AccountRef account, @RequestParam(required = false) String startDate,
                                      @RequestParam(required = false) String endDate) {
        // Same parsing / validation / error shapes as FEAT-002 and FEAT-006 (only the date range is used).
        TradeFilter f = TradeListQuery.parse(null, null, null, null, null, startDate, endDate, 1, 20);
        PnlSummary s = service.summary(account.id(), f.startDate(), f.endDate());
        PnlSummary.Breakdown t = s.total();
        return new PnlSummaryResponse(f.startDate() == null ? null : f.startDate().toString(),
                f.endDate() == null ? null : f.endDate().toString(), t.all().closedCount(), t.all().realizedPnl(),
                TypePart.of(t.option()), TypePart.of(t.stock()),
                s.bySymbol().stream().map(g -> new SymbolRow(g.symbol(), g.breakdown().all().closedCount(),
                        g.breakdown().all().realizedPnl(), TypePart.of(g.breakdown().option()),
                        TypePart.of(g.breakdown().stock()))).toList(),
                s.byMonth().stream().map(g -> new MonthRow(g.month().toString(), g.breakdown().all().closedCount(),
                        g.breakdown().all().realizedPnl(), TypePart.of(g.breakdown().option()),
                        TypePart.of(g.breakdown().stock()))).toList());
    }
}
