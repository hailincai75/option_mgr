package com.optionmgr.trade.service;

import com.optionmgr.cash.domain.CashBalance;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.ClosedRecordFilter;
import com.optionmgr.trade.domain.ExpirationStatus;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.PnlSummary;
import com.optionmgr.trade.domain.TradeFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-008: realized P&amp;L summaries and the home overview. Built on {@link ClosedTradeService} (the same closed
 * records and realized P&amp;L as the closed-trades list; FEAT-040: option closes / expiries and stock sales),
 * {@link OptionPositionService}, {@link StockPositionService} and {@link CashLedgerService}, so every number
 * cross-checks with those endpoints. "Today" / "this month" come from the injected clock in New York time.
 */
@Service
public class PnlSummaryService {

    /** Q48: "expiring soon" = 0 &lt;= DTE &lt;= 7. */
    public static final int EXPIRING_WITHIN_DAYS = 7;

    private final ClosedTradeService closedTrades;
    private final OptionPositionService positions;
    private final StockPositionService stockPositions;
    private final CashLedgerService ledger;
    private final Clock clock;

    public PnlSummaryService(ClosedTradeService closedTrades, OptionPositionService positions,
                             StockPositionService stockPositions, CashLedgerService ledger, Clock clock) {
        this.closedTrades = closedTrades;
        this.positions = positions;
        this.stockPositions = stockPositions;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Home overview. Realized P&amp;L includes stock sales (FEAT-040); the option position figures count options only;
     * {@code stockPositionCount} = symbols with shares &gt; 0 (Q89).
     */
    public record Dashboard(LocalDate asOfDate, YearMonth currentMonth, CashBalance cash, PnlSummary.Group realized,
                            PnlSummary.Group currentMonthRealized, int positionCount, BigDecimal frozenTotal,
                            int expiringWithin7DaysCount, int expiredUnprocessedCount, int stockPositionCount) {
    }

    /**
     * Realized P&amp;L of the closed records (option closes / expiries and stock sales) whose New York close / sale
     * date is within [startDate, endDate] (either may be null).
     */
    @Transactional(readOnly = true)
    public PnlSummary summary(long accountId, LocalDate startDate, LocalDate endDate) {
        ClosedRecordFilter filter = new ClosedRecordFilter(
                new TradeFilter(null, null, null, null, Set.of(), startDate, endDate), null);
        List<PnlSummary.Entry> entries = closedTrades.all(accountId, filter).stream()
                .map(v -> new PnlSummary.Entry(v.key().type(), v.symbol(), v.key().closedAt(), v.realizedPnl()))
                .toList();
        return PnlSummary.of(entries);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(long accountId) {
        LocalDate today = MarketTime.toLocal(clock.instant()).toLocalDate();
        YearMonth month = YearMonth.from(today);
        PnlSummary all = summary(accountId, null, null);
        // No trade is later than now, so "from the 1st of this month" = this month so far.
        PnlSummary thisMonth = summary(accountId, month.atDay(1), null);
        OptionPositionService.PositionSummary ps = positions.currentPositions(accountId);
        int expiring = 0;
        int expired = 0;
        for (OptionPosition p : ps.positions()) {
            long dte = p.dte(ps.asOfDate());
            if (p.expirationStatus(ps.asOfDate()) == ExpirationStatus.EXPIRED) {
                expired++;
            } else if (dte <= EXPIRING_WITHIN_DAYS) {
                expiring++;
            }
        }
        return new Dashboard(today, month, ledger.currentBalance(accountId), all.total().all(),
                thisMonth.total().all(), ps.positions().size(), ps.frozenTotal(), expiring, expired,
                stockPositions.currentPositions(accountId).size());
    }
}
