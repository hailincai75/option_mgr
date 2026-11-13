package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ExpirationStatus;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionPositionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-003 option positions (read-only). */
@RestController
@RequestMapping("/api/option-positions")
public class OptionPositionController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionPositionService service;

    public OptionPositionController(OptionPositionService service) {
        this.service = service;
    }

    public record PositionsResponse(LocalDate asOfDate, int positionCount, BigDecimal frozenTotal,
                                    List<PositionResponse> positions) {
    }

    public record PositionResponse(String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                   String contractLabel, PositionDirection direction, int contracts,
                                   int multiplier, BigDecimal openingNetTotal, BigDecimal averageNetPerContract,
                                   BigDecimal frozenCash, long dte, ExpirationStatus expirationStatus,
                                   long daysPastExpiration, int lotCount, List<LotResponse> lots,
                                   TradeAction closeAction, List<Integer> allowedCloseContracts,
                                   boolean closable) {
    }

    public record LotResponse(long tradeId, TradeAction action, Instant tradedAt, String tradedAtLocal,
                              int originalContracts, int remainingContracts, BigDecimal originalNetAmount,
                              BigDecimal remainingNetAmount, BigDecimal originalFrozen, BigDecimal remainingFrozen,
                              String note) {
    }

    @GetMapping
    public PositionsResponse positions(AccountRef account) {
        OptionPositionService.PositionSummary s = service.currentPositions(account.id());
        List<PositionResponse> positions = s.positions().stream().map(p -> toResponse(p, s.asOfDate())).toList();
        return new PositionsResponse(s.asOfDate(), positions.size(), s.frozenTotal(), positions);
    }

    private static PositionResponse toResponse(OptionPosition p, LocalDate today) {
        ContractKey c = p.contract();
        long dte = p.dte(today);
        return new PositionResponse(c.symbol(), c.expirationDate(), ContractKey.strikeText(c.strike()),
                c.optionType(), c.label(), p.direction(), p.contracts(), p.multiplier(), p.openingNetTotal(),
                p.averageNetPerContract(), p.frozenCash(), dte, p.expirationStatus(today), Math.max(0, -dte),
                p.lots().size(), p.lots().stream().map(OptionPositionController::toResponse).toList(),
                p.closeAction(), p.allowedCloseContracts(), p.closable(today));
    }

    private static LotResponse toResponse(PositionLot l) {
        return new LotResponse(l.tradeId(), l.action(), l.tradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(l.tradedAt())), l.originalContracts(),
                l.remainingContracts(), l.originalNetAmount(), l.remainingNetAmount(), l.originalFrozen(),
                l.remainingFrozen(), l.note());
    }
}
