package com.optionmgr.trade.web;

import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ClosedTrade;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeFilter;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.service.ClosedTradeService;
import com.optionmgr.trade.web.OptionTradeDtos.CoveredLotResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-006 closed trades with realized P&amp;L (read-only). */
@RestController
@RequestMapping("/api/closed-trades")
public class ClosedTradeController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final ClosedTradeService service;

    public ClosedTradeController(ClosedTradeService service) {
        this.service = service;
    }

    public record ClosedTradeResponse(long id, Instant tradedAt, String tradedAtLocal, String symbol,
                                      LocalDate expirationDate, String strike, OptionType optionType,
                                      String contractLabel, TradeAction action, PositionDirection closedDirection,
                                      int contracts, int multiplier, BigDecimal openingNetTotal,
                                      BigDecimal openingNetPerContract, BigDecimal closeNetAmount,
                                      BigDecimal closeNetPerContract, BigDecimal realizedPnl, String note,
                                      List<CoveredLotResponse> coveredLots) {
    }

    public record ClosedTradePageResponse(List<ClosedTradeResponse> items, int page, int size, long totalItems,
                                          int totalPages, long filteredCount, BigDecimal filteredRealizedPnlTotal) {
    }

    @GetMapping
    public ClosedTradePageResponse list(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String expirationDate,
            @RequestParam(required = false) String strike,
            @RequestParam(required = false) String optionType,
            @RequestParam(name = "action", required = false) List<String> actions,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TradeFilter filter = TradeListQuery.parse(symbol, expirationDate, strike, optionType, actions, startDate,
                endDate, page, size, EnumSet.of(TradeAction.BTC, TradeAction.STC), "方向只能是 BTC、STC");
        ClosedTradeService.ClosedTradePage p = service.page(filter, page, size);
        return new ClosedTradePageResponse(p.items().stream().map(ClosedTradeController::toResponse).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages(), p.totalItems(), p.filteredRealizedPnlTotal());
    }

    private static ClosedTradeResponse toResponse(ClosedTradeService.ClosedTradeView v) {
        OptionTradeEntity t = v.trade().trade();
        ClosedTrade f = v.figures();
        ContractKey c = t.contract();
        return new ClosedTradeResponse(t.getId(), t.getTradedAt(), LOCAL_SECONDS.format(MarketTime.toLocal(t.getTradedAt())),
                t.getSymbol(), t.getExpirationDate(), ContractKey.strikeText(t.getStrike()), t.getOptionType(),
                c.label(), t.getAction(), f.closedDirection(), t.getContracts(), t.getMultiplier(),
                f.openingNetTotal(), f.openingNetPerContract(), f.closeNetAmount(), f.closeNetPerContract(),
                f.realizedPnl(), t.getNote(), f.coveredLots().stream().map(ClosedTradeController::toLot).toList());
    }

    private static CoveredLotResponse toLot(PositionLot l) {
        return new CoveredLotResponse(l.tradeId(), l.action(), l.tradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(l.tradedAt())), l.originalContracts(), l.originalNetAmount(),
                l.originalFrozen());
    }
}
