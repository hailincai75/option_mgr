package com.optionmgr.valuation.web;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.account.web.AccountScope;
import com.optionmgr.trade.domain.PnlSummary;
import com.optionmgr.trade.web.PnlSummaryController;
import com.optionmgr.valuation.domain.UnderlyingSummary;
import com.optionmgr.valuation.service.UnderlyingSummaryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEAT-023 per-underlying P&amp;L summary of the account (strategy page cards); FEAT-047: with
 * {@code X-Account-Id: ALL} one group per account + symbol (Q224 A), every group carrying its account.
 */
@RestController
@RequestMapping("/api/positions/underlying-summary")
public class UnderlyingSummaryController {

    private final UnderlyingSummaryService service;

    public UnderlyingSummaryController(UnderlyingSummaryService service) {
        this.service = service;
    }

    /** Same shape as a {@code pnl-summary.bySymbol[]} row without {@code symbol}. */
    public record RealizedResponse(int closedCount, BigDecimal realizedPnl, PnlSummaryController.TypePart option,
                                   PnlSummaryController.TypePart stock) {

        static RealizedResponse of(PnlSummary.Breakdown b) {
            return new RealizedResponse(b.all().closedCount(), b.all().realizedPnl(),
                    new PnlSummaryController.TypePart(b.option().closedCount(), b.option().realizedPnl()),
                    new PnlSummaryController.TypePart(b.stock().closedCount(), b.stock().realizedPnl()));
        }
    }

    public record GroupResponse(long accountId, String accountName, String symbol, RealizedResponse realized,
                                BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal totalPnl,
                                BigDecimal netPremium, int optionPositionCount, boolean hasStockPosition,
                                int includedCount, int noQuoteCount, int expiredPendingCount, String excludedLabel) {

        static GroupResponse of(AccountRows.Row<UnderlyingSummary.Group> row) {
            UnderlyingSummary.Group g = row.value();
            return new GroupResponse(row.accountId(), row.accountName(), g.symbol(),
                    RealizedResponse.of(g.realized()), g.marketValue(),
                    g.unrealizedPnl(), g.totalPnl(), g.netPremium(), g.optionPositionCount(), g.hasStockPosition(),
                    g.includedCount(), g.noQuoteCount(), g.expiredPendingCount(), g.excludedLabel());
        }
    }

    public record SummaryResponse(LocalDate asOfDate, List<GroupResponse> groups) {
    }

    @GetMapping
    public SummaryResponse summary(AccountScope scope) {
        UnderlyingSummaryService.ScopeResult r = service.summary(scope.names());
        return new SummaryResponse(r.asOfDate(), r.groups().stream().map(GroupResponse::of).toList());
    }
}
