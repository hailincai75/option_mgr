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

/** FEAT-008: home overview and realized P&amp;L summary (read-only). */
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

    public record DashboardResponse(String asOfDate, String currentMonth, CashPart cash, PnlPart realizedPnl,
                                    PnlPart currentMonthRealizedPnl, PositionsPart positions) {
    }

    public record SymbolRow(String symbol, int closedCount, BigDecimal realizedPnl) {
    }

    public record MonthRow(String month, int closedCount, BigDecimal realizedPnl) {
    }

    public record PnlSummaryResponse(String startDate, String endDate, int closedCount, BigDecimal realizedPnlTotal,
                                     List<SymbolRow> bySymbol, List<MonthRow> byMonth) {
    }

    @GetMapping("/api/dashboard")
    public DashboardResponse dashboard(AccountRef account) {
        PnlSummaryService.Dashboard d = service.dashboard(account.id());
        return new DashboardResponse(d.asOfDate().toString(), d.currentMonth().toString(),
                new CashPart(d.cash().total(), d.cash().frozen(), d.cash().available()),
                new PnlPart(d.realized().closedCount(), d.realized().realizedPnl()),
                new PnlPart(d.currentMonthRealized().closedCount(), d.currentMonthRealized().realizedPnl()),
                new PositionsPart(d.positionCount(), d.frozenTotal(), d.expiringWithin7DaysCount(),
                        d.expiredUnprocessedCount()));
    }

    @GetMapping("/api/pnl-summary")
    public PnlSummaryResponse summary(AccountRef account, @RequestParam(required = false) String startDate,
                                      @RequestParam(required = false) String endDate) {
        // Same parsing / validation / error shapes as FEAT-002 and FEAT-006 (only the date range is used).
        TradeFilter f = TradeListQuery.parse(null, null, null, null, null, startDate, endDate, 1, 20);
        PnlSummary s = service.summary(account.id(), f.startDate(), f.endDate());
        return new PnlSummaryResponse(f.startDate() == null ? null : f.startDate().toString(),
                f.endDate() == null ? null : f.endDate().toString(), s.total().closedCount(),
                s.total().realizedPnl(),
                s.bySymbol().stream().map(g -> new SymbolRow(g.symbol(), g.closedCount(), g.realizedPnl())).toList(),
                s.byMonth().stream().map(g -> new MonthRow(g.month().toString(), g.closedCount(), g.realizedPnl()))
                        .toList());
    }
}
