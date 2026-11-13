package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
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
    public record ClosedTradeResponse(ClosedRecordType type, long id, Instant tradedAt, String tradedAtLocal,
                                      String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                      String contractLabel,
                                      /* BTC / STC / EXP; SELL on stock rows */
                                      String action, PositionDirection closedDirection, Integer contracts,
                                      Integer multiplier, Integer shares, BigDecimal openingNetTotal,
                                      BigDecimal openingNetPerContract, BigDecimal costPerShare,
                                      BigDecimal closeNetAmount, BigDecimal closeNetPerContract,
                                      BigDecimal pricePerShare, BigDecimal realizedPnl, String note,
                                      List<CoveredLotResponse> coveredLots, List<SoldLotResponse> soldLots,
                                      /* FEAT-013: ASG rows only (shares, stock cost, generated lot id) */
                                      AssignedStockResponse assignment,
                                      /* the closing trade's / sale's (id) stored flag: it has been edited */
                                      boolean hasAuditLog) {
    }

    /** FEAT-013: where an assignment's premium went: the generated stock lot. */
    public record AssignedStockResponse(int shares, BigDecimal stockCost, Long stockTradeId) {
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
            AccountRef account,
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
                "方向只能是 BTC、STC、EXP、ASG");
        ClosedTradeService.ClosedTradePage p = service.page(account.id(), filter, page, size);
        return new ClosedTradePageResponse(p.items().stream().map(ClosedTradeController::toResponse).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages(), p.totalItems(), p.filteredRealizedPnlTotal());
    }

    private static ClosedTradeResponse toResponse(ClosedRecordView v) {
        return switch (v) {
            case ClosedTradeView o -> toResponse(o);
            case StockSaleView s -> toResponse(s);
        };
    }

    private static ClosedTradeResponse toResponse(ClosedTradeView v) {
        OptionTradeEntity t = v.trade().trade();
        ClosedTrade f = v.figures();
        ContractKey c = t.contract();
        return new ClosedTradeResponse(ClosedRecordType.OPTION, t.getId(), t.getTradedAt(), local(t.getTradedAt()),
                t.getSymbol(), t.getExpirationDate(), ContractKey.strikeText(t.getStrike()), t.getOptionType(),
                c.label(), t.getAction().name(), f.closedDirection(), t.getContracts(), t.getMultiplier(), null,
                f.openingNetTotal(), f.openingNetPerContract(), null, f.closeNetAmount(), f.closeNetPerContract(),
                null, f.realizedPnl(), t.getNote(), f.coveredLots().stream().map(ClosedTradeController::toLot).toList(),
                null, v.trade().assignment().map(a -> new AssignedStockResponse(a.shares(), a.stockCost(),
                        v.trade().assignedLot() == null ? null : v.trade().assignedLot().getId())).orElse(null),
                t.hasAuditLog());
    }

    private static ClosedTradeResponse toResponse(StockSaleView v) {
        StockTradeEntity t = v.sale();
        ClosedStockSale f = v.figures();
        return new ClosedTradeResponse(ClosedRecordType.STOCK, t.getId(), t.getTradedAt(), local(t.getTradedAt()),
                t.getSymbol(), null, null, null, null, t.getType().name(), null, null, null, t.getShares(),
                f.openingNetTotal(), null, f.costPerShare(), f.closeNetAmount(), null, f.pricePerShare(),
                f.realizedPnl(), t.getNote(), null,
                f.match().lots().stream().map(ClosedTradeController::toSoldLot).toList(), null, t.hasAuditLog());
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
