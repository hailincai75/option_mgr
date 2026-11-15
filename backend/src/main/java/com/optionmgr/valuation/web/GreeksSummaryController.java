package com.optionmgr.valuation.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.valuation.domain.GreeksSummary;
import com.optionmgr.valuation.service.UnrealizedValuationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-019 Greeks per underlying and for the whole account (docs/api/FEAT-019.md). */
@RestController
@RequestMapping("/api/positions/greeks-summary")
public class GreeksSummaryController {

    private final UnrealizedValuationService service;

    public GreeksSummaryController(UnrealizedValuationService service) {
        this.service = service;
    }

    public record GroupResponse(String symbol, BigDecimal optionDelta, BigDecimal stockDelta, BigDecimal netDelta,
                                BigDecimal gamma, BigDecimal theta, BigDecimal vega, BigDecimal underlyingPrice,
                                boolean underlyingPriceManual, String underlyingPriceLabel, BigDecimal dollarExposure,
                                GreeksSummary.ExposureStatus exposureStatus, String exposureLabel,
                                String manualPriceNote, int optionRowCount, int includedCount, int noQuoteCount,
                                int expiredPendingCount, int deltaMissingCount, int gammaMissingCount,
                                int thetaMissingCount, int vegaMissingCount, String excludedLabel,
                                String missingLabel) {

        static GroupResponse of(GreeksSummary.Group g) {
            GreeksSummary.Missing m = g.missing();
            return new GroupResponse(g.symbol(), g.optionDelta(), g.stockDelta(), g.netDelta(), g.gamma(), g.theta(),
                    g.vega(), g.underlyingPrice(), g.underlyingPriceManual(), g.underlyingPriceLabel(),
                    g.dollarExposure(), g.exposureStatus(), g.exposureLabel(), g.manualPriceNote(),
                    g.optionRowCount(), g.includedCount(), g.noQuoteCount(), g.expiredPendingCount(), m.delta(),
                    m.gamma(), m.theta(), m.vega(), g.excludedLabel(), g.missingLabel());
        }
    }

    public record PortfolioResponse(BigDecimal dollarExposure, BigDecimal theta, BigDecimal vega, int noQuoteCount,
                                    int expiredPendingCount, int noUnderlyingPriceCount, int deltaMissingCount,
                                    int thetaMissingCount, int vegaMissingCount, String excludedLabel,
                                    String noUnderlyingPriceLabel, String missingLabel) {

        static PortfolioResponse of(GreeksSummary.Portfolio p) {
            GreeksSummary.Missing m = p.missing();
            return new PortfolioResponse(p.dollarExposure(), p.theta(), p.vega(), p.noQuoteCount(),
                    p.expiredPendingCount(), p.noUnderlyingPriceCount(), m.delta(), m.theta(), m.vega(),
                    p.excludedLabel(), p.noUnderlyingPriceLabel(), p.missingLabel());
        }
    }

    public record SummaryResponse(LocalDate asOfDate, List<GroupResponse> groups, PortfolioResponse portfolio) {
    }

    @GetMapping
    public SummaryResponse summary(AccountRef account) {
        UnrealizedValuationService.GreeksSummaryResult r = service.greeksSummary(account.id());
        return new SummaryResponse(r.asOfDate(), r.summary().groups().stream().map(GroupResponse::of).toList(),
                PortfolioResponse.of(r.summary().portfolio()));
    }
}
