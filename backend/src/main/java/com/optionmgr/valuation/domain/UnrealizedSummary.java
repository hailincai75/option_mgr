package com.optionmgr.valuation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * FEAT-017 account totals (Q157 / Q160): sums of the {@link ValuationStatus#OK} rows for options, stocks and both,
 * plus how many rows were left out and why. Pure Java.
 */
public record UnrealizedSummary(Group option, Group stock, Group total, String excludedLabel) {

    /** One group of totals; amounts scale 2. */
    public record Group(BigDecimal marketValue, BigDecimal unrealizedPnl, int includedCount, int noQuoteCount,
                        int expiredPendingCount) {

        static Group empty() {
            BigDecimal zero = BigDecimal.ZERO.setScale(UnrealizedValuation.SCALE, RoundingMode.UNNECESSARY);
            return new Group(zero, zero, 0, 0, 0);
        }

        Group add(RowValuation row) {
            return switch (row.status()) {
                case OK -> new Group(marketValue.add(row.marketValue()), unrealizedPnl.add(row.unrealizedPnl()),
                        includedCount + 1, noQuoteCount, expiredPendingCount);
                case NO_QUOTE -> new Group(marketValue, unrealizedPnl, includedCount, noQuoteCount + 1,
                        expiredPendingCount);
                case EXPIRED_PENDING -> new Group(marketValue, unrealizedPnl, includedCount, noQuoteCount,
                        expiredPendingCount + 1);
            };
        }

        Group plus(Group o) {
            return new Group(marketValue.add(o.marketValue), unrealizedPnl.add(o.unrealizedPnl),
                    includedCount + o.includedCount, noQuoteCount + o.noQuoteCount,
                    expiredPendingCount + o.expiredPendingCount);
        }
    }

    public static UnrealizedSummary of(Iterable<RowValuation> options, Iterable<RowValuation> stocks) {
        Group option = Group.empty();
        for (RowValuation r : options) {
            option = option.add(r);
        }
        Group stock = Group.empty();
        for (RowValuation r : stocks) {
            stock = stock.add(r);
        }
        Group total = option.plus(stock);
        return new UnrealizedSummary(option, stock, total,
                excludedLabel(total.noQuoteCount(), total.expiredPendingCount()));
    }

    /** {@code N 个持仓无报价、M 个持仓已到期待处理，未计入}, either part omitted when 0; {@code null} when both are 0. */
    static String excludedLabel(int noQuote, int expiredPending) {
        if (noQuote == 0 && expiredPending == 0) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        if (noQuote > 0) {
            s.append(noQuote).append(" 个持仓无报价");
        }
        if (expiredPending > 0) {
            if (!s.isEmpty()) {
                s.append('、');
            }
            s.append(expiredPending).append(" 个持仓已到期待处理");
        }
        return s.append("，未计入").toString();
    }
}
