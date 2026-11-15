package com.optionmgr.valuation.web;

import com.optionmgr.account.web.AccountScope;
import com.optionmgr.valuation.domain.UnrealizedSummary;
import com.optionmgr.valuation.service.UnrealizedValuationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-017 unrealized P&L totals of the account (options / stocks / both). */
@RestController
@RequestMapping("/api/positions/unrealized-summary")
public class UnrealizedSummaryController {

    private final UnrealizedValuationService service;

    public UnrealizedSummaryController(UnrealizedValuationService service) {
        this.service = service;
    }

    public record GroupResponse(BigDecimal marketValue, BigDecimal unrealizedPnl, int includedCount, int noQuoteCount,
                                int expiredPendingCount) {

        static GroupResponse of(UnrealizedSummary.Group g) {
            return new GroupResponse(g.marketValue(), g.unrealizedPnl(), g.includedCount(), g.noQuoteCount(),
                    g.expiredPendingCount());
        }
    }

    public record SummaryResponse(LocalDate asOfDate, GroupResponse option, GroupResponse stock, GroupResponse total,
                                  String excludedLabel) {
    }

    @GetMapping
    public SummaryResponse summary(AccountScope account) {
        UnrealizedValuationService.Summary s = service.summary(account.ids());
        UnrealizedSummary t = s.totals();
        return new SummaryResponse(s.asOfDate(), GroupResponse.of(t.option()), GroupResponse.of(t.stock()),
                GroupResponse.of(t.total()), t.excludedLabel());
    }
}
