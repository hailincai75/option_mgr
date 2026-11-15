package com.optionmgr.trade.web;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.account.web.AccountScope;
import com.optionmgr.trade.domain.ClosedRecordKey;
import java.util.ArrayList;
import java.util.Comparator;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.LotSale;
import com.optionmgr.stock.domain.StockTimes;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.trade.domain.ClosedRecordFilter;
import com.optionmgr.trade.domain.ClosedRecordType;
import com.optionmgr.trade.domain.ClosedStockSale;
import com.optionmgr.trade.domain.ClosedTrade;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.service.ClosedTradeService;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.service.ClosedTradeService.ClosedRecordView;
import com.optionmgr.trade.service.ClosedTradeService.ClosedTradeView;
import com.optionmgr.trade.service.ClosedTradeService.StockSaleView;
import com.optionmgr.trade.web.OptionTradeDtos.CoveredLotResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-006 closed trades with realized P&amp;L (read-only); FEAT-040 adds stock sales. */
@RestController
@RequestMapping("/api/closed-trades")
public class ClosedTradeController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final ClosedTradeService service;
    public ClosedTradeController(ClosedTradeService service) {
        this.service = service;
    }

    /**
     * One closed record. Option-only fields are null on stock rows, stock-only fields null on option rows (FEAT-040).
     * A row is identified by {@code type} + {@code id}.
     */
    public record ClosedTradeResponse(/* FEAT-046: the row's account (current name), also for one account */
                                      long accountId, String accountName,
                                      ClosedRecordType type, long id, Instant tradedAt, String tradedAtLocal,
                                      String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                      String contractLabel,
                                      /* BTC / STC / EXP / ASG; SELL / ASSIGN_SELL on stock rows */
                                      String action, PositionDirection closedDirection, Integer contracts,
                                      Integer multiplier, Integer shares, BigDecimal openingNetTotal,
                                      BigDecimal openingNetPerContract, BigDecimal costPerShare,
                                      BigDecimal closeNetAmount, BigDecimal closeNetPerContract,
                                      BigDecimal pricePerShare, BigDecimal realizedPnl, String note,
                                      List<CoveredLotResponse> coveredLots, List<SoldLotResponse> soldLots,
                                      /* FEAT-013: ASG rows only (shares, stock cost or (FEAT-041) proceeds, generated record id) */
                                      AssignedStockResponse assignment,
                                      /* FEAT-041: ASSIGN_SELL stock rows: the call assignment (option trade id); null otherwise */
                                      Long assignmentTradeId,
                                      /* FEAT-014: EXR rows only (shares, stock cost (call) or proceeds (put), generated record id) */
                                      AssignedStockResponse exercise,
                                      /* FEAT-014: EXERCISE_SELL stock rows: the exercise (option trade id); null otherwise */
                                      Long exerciseTradeId,
                                      /* the closing trade's / sale's (id) stored flag: it has been edited */
                                      boolean hasAuditLog) {
    }

    /** FEAT-013: where an assignment's premium went: the generated stock lot (put) or (FEAT-041) stock sale (call). */
    public record AssignedStockResponse(int shares, BigDecimal stockCost, BigDecimal proceeds, Long stockTradeId) {
    }

    /** A lot part taken by a stock sale; {@code netAmount} = -cost (cash sign). */
    public record SoldLotResponse(long tradeId, StockTradeType type, Instant tradedAt, String tradedAtLocal,
                                  LocalDate tradeDate, int shares, BigDecimal netAmount) {
    }

    public record ClosedTradePageResponse(List<ClosedTradeResponse> items, int page, int size, long totalItems,
                                          int totalPages, long filteredCount, BigDecimal filteredRealizedPnlTotal) {
    }

    @GetMapping
    public ClosedTradePageResponse list(
            AccountScope scope,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String expirationDate,
            @RequestParam(required = false) String strike,
            @RequestParam(required = false) String optionType,
            @RequestParam(name = "action", required = false) List<String> actions,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ClosedRecordFilter filter = TradeListQuery.parseClosed(type, symbol, expirationDate, strike, optionType,
                actions, startDate, endDate, page, size, ClosedTradeService.CLOSING_ACTIONS,
                "方向只能是 BTC、STC、EXP、ASG、EXR");
        // FEAT-046: each account's records computed on their own (FIFO per account, Q53), merged in the list order
        // (then account id, Q229); count, total and page are of the merged list (Q216, rule 5)
        List<List<ClosedRecordView>> perAccount = service.allByAccount(scope.ids(), filter);
        List<AccountRows.Row<ClosedRecordView>> rows = new ArrayList<>();
        for (int i = 0; i < perAccount.size(); i++) {
            AccountScope.Member m = scope.accounts().get(i);
            perAccount.get(i).forEach(v -> rows.add(new AccountRows.Row<>(m.id(), m.name(), v)));
        }
        List<AccountRows.Row<ClosedRecordView>> sorted = AccountRows.sorted(rows,
                Comparator.comparing(ClosedRecordView::key, ClosedRecordKey.NEWEST_FIRST));
        BigDecimal total = sorted.stream().map(r -> r.value().realizedPnl()).reduce(Money.ZERO, BigDecimal::add);
        AccountRows.Page<AccountRows.Row<ClosedRecordView>> p = AccountRows.page(sorted, page, size);
        return new ClosedTradePageResponse(p.items().stream().map(ClosedTradeController::toResponse).toList(),
                page, size, p.totalItems(), p.totalPages(), p.totalItems(), total);
    }

    private static ClosedTradeResponse toResponse(AccountRows.Row<ClosedRecordView> r) {
        ClosedRecordView v = r.value();
        return switch (v) {
            case ClosedTradeView o -> toResponse(r.accountId(), r.accountName(), o);
            case StockSaleView s -> toResponse(r.accountId(), r.accountName(), s);
        };
    }

    private static ClosedTradeResponse toResponse(long accountId, String accountName, ClosedTradeView v) {
        OptionTradeEntity t = v.trade().trade();
        ClosedTrade f = v.figures();
        ContractKey c = t.contract();
        boolean isExercise = t.getAction() == com.optionmgr.trade.domain.TradeAction.EXR;
        return new ClosedTradeResponse(accountId, accountName, ClosedRecordType.OPTION, t.getId(), t.getTradedAt(), local(t.getTradedAt()),
                t.getSymbol(), t.getExpirationDate(), ContractKey.strikeText(t.getStrike()), t.getOptionType(),
                c.label(), t.getAction().name(), f.closedDirection(), t.getContracts(), t.getMultiplier(), null,
                f.openingNetTotal(), f.openingNetPerContract(), null, f.closeNetAmount(), f.closeNetPerContract(),
                null, f.realizedPnl(), t.getNote(), f.coveredLots().stream().map(ClosedTradeController::toLot).toList(),
                null, isExercise ? null : assignedStock(v.trade()), null, isExercise ? assignedStock(v.trade()) : null,
                null, t.hasAuditLog());
    }

    /** FEAT-013 put: shares + stock cost; FEAT-041 call: shares + proceeds; null for other records. */
    private static AssignedStockResponse assignedStock(OptionTradeService.TradeView view) {
        Long stockTradeId = view.assignedLot() == null ? null : view.assignedLot().getId();
        return view.buyLeg()
                .map(a -> new AssignedStockResponse(a.shares(), a.stockCost(), null, stockTradeId))
                .or(() -> view.sellLeg()
                        .map(a -> new AssignedStockResponse(a.shares(), null, a.proceeds(), stockTradeId)))
                .orElse(null);
    }

    private static ClosedTradeResponse toResponse(long accountId, String accountName, StockSaleView v) {
        StockTradeEntity t = v.sale();
        ClosedStockSale f = v.figures();
        return new ClosedTradeResponse(accountId, accountName, ClosedRecordType.STOCK, t.getId(), t.getTradedAt(), local(t.getTradedAt()),
                t.getSymbol(), null, null, null, null, t.getType().name(), null, null, null, t.getShares(),
                f.openingNetTotal(), null, f.costPerShare(), f.closeNetAmount(), null, f.pricePerShare(),
                f.realizedPnl(), t.getNote(), null,
                f.match().lots().stream().map(ClosedTradeController::toSoldLot).toList(), null,
                t.getType() == StockTradeType.ASSIGN_SELL ? t.getOptionTradeId() : null, null,
                t.getType() == StockTradeType.EXERCISE_SELL ? t.getOptionTradeId() : null, t.hasAuditLog());
    }

    private static CoveredLotResponse toLot(PositionLot l) {
        return new CoveredLotResponse(l.tradeId(), l.action(), l.tradedAt(), local(l.tradedAt()),
                l.originalContracts(), l.originalNetAmount(), l.originalFrozen());
    }

    private static SoldLotResponse toSoldLot(LotSale l) {
        return new SoldLotResponse(l.sourceId(), l.sourceType(), l.tradedAt(), local(l.tradedAt()),
                l.sourceType() == StockTradeType.TRANSFER_IN ? StockTimes.marketDate(l.tradedAt()) : null,
                l.shares(), ClosedStockSale.lotNetAmount(l));
    }

    private static String local(Instant instant) {
        return LOCAL_SECONDS.format(MarketTime.toLocal(instant));
    }
}
