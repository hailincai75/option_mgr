package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.AssignmentTerms;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ExpirationStatus;
import com.optionmgr.trade.domain.ExpirationTimes;
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
                                   /* FEAT-012: now < 16:00 New York on the expiration date */
                                   boolean closable,
                                   /* FEAT-012: 16:00 <= now < 17:30 on the expiration date ("到期待确认") */
                                   boolean expiryPending,
                                   /* FEAT-012: now >= 17:30 New York on the expiration date */
                                   boolean expirable,
                                   /* FEAT-012: contracts at the cutoff = the expiry size to send back */
                                   int expiryContracts,
                                   Instant expiryAt, String expiryAtLocal,
                                   /* FEAT-013: short put, any time (Q95) */
                                   boolean assignable, List<AssignChoice> assignChoices,
                                   LocalDate assignmentDateMax, LocalDate assignmentDateDefault) {
    }

    /** FEAT-013: one selectable assignment size (earliest whole lots) and its shares. */
    public record AssignChoice(int contracts, int shares) {
    }

    public record LotResponse(long tradeId, TradeAction action, Instant tradedAt, String tradedAtLocal,
                              int originalContracts, int remainingContracts, BigDecimal originalNetAmount,
                              BigDecimal remainingNetAmount, BigDecimal originalFrozen, BigDecimal remainingFrozen,
                              String note) {
    }

    @GetMapping
    public PositionsResponse positions(AccountRef account) {
        OptionPositionService.PositionSummary s = service.currentPositions(account.id());
        List<PositionResponse> positions = s.positions().stream().map(p -> toResponse(p, s)).toList();
        return new PositionsResponse(s.asOfDate(), positions.size(), s.frozenTotal(), positions);
    }

    private static PositionResponse toResponse(OptionPosition p, OptionPositionService.PositionSummary s) {
        ContractKey c = p.contract();
        LocalDate today = s.asOfDate();
        Instant now = s.now();
        long dte = p.dte(today);
        Instant cutoff = ExpirationTimes.tradingCutoff(c.expirationDate());
        boolean assignable = p.direction() == PositionDirection.SHORT && c.optionType() == OptionType.PUT;
        LocalDate latest = assignable ? AssignmentTerms.latestDate(c.expirationDate(), now) : null;
        return new PositionResponse(c.symbol(), c.expirationDate(), ContractKey.strikeText(c.strike()),
                c.optionType(), c.label(), p.direction(), p.contracts(), p.multiplier(), p.openingNetTotal(),
                p.averageNetPerContract(), p.frozenCash(), dte, p.expirationStatus(today), Math.max(0, -dte),
                p.lots().size(), p.lots().stream().map(OptionPositionController::toResponse).toList(),
                p.closeAction(), p.allowedCloseContracts(), p.closable(now), p.expiryPending(now), p.expirable(now),
                s.expiryContracts(p), cutoff, LOCAL_SECONDS.format(MarketTime.toLocal(cutoff)), assignable,
                assignable
                        ? p.allowedCloseContracts().stream().map(n -> new AssignChoice(n, n * p.multiplier())).toList()
                        : List.of(),
                latest, latest);
    }

    private static LotResponse toResponse(PositionLot l) {
        return new LotResponse(l.tradeId(), l.action(), l.tradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(l.tradedAt())), l.originalContracts(),
                l.remainingContracts(), l.originalNetAmount(), l.remainingNetAmount(), l.originalFrozen(),
                l.remainingFrozen(), l.note());
    }
}
