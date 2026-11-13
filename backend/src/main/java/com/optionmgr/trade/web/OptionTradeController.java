package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionAssignmentService;
import com.optionmgr.trade.service.OptionExpiryService;
import com.optionmgr.trade.web.OptionTradeDtos.AssignRequest;
import com.optionmgr.trade.web.OptionTradeDtos.AssignmentResponse;
import com.optionmgr.trade.web.OptionTradeDtos.ExpireRequest;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.service.OptionTradeEditService;
import com.optionmgr.trade.web.OptionTradeDtos.EditTradeRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import com.optionmgr.trade.web.OptionTradeDtos.OpenTradeRequest;
import com.optionmgr.trade.web.OptionTradeDtos.CloseTradeRequest;
import com.optionmgr.trade.web.OptionTradeDtos.CoveredLotResponse;
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.web.OptionTradeDtos.TradePageResponse;
import com.optionmgr.trade.web.OptionTradeDtos.TradeResponse;
import com.optionmgr.trade.domain.TradeFilter;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Option trade API: record an opening (FEAT-001) or closing (FEAT-004) trade, read one back, list (FEAT-002). */
@RestController
@RequestMapping("/api/option-trades")
public class OptionTradeController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionTradeService service;
    private final OptionTradeEditService edits;
    private final OptionExpiryService expiries;
    private final OptionAssignmentService assignments;

    public OptionTradeController(OptionTradeService service, OptionTradeEditService edits,
                                 OptionExpiryService expiries, OptionAssignmentService assignments) {
        this.service = service;
        this.edits = edits;
        this.expiries = expiries;
        this.assignments = assignments;
    }

    /** FEAT-013: a short put is assigned (recorded at 16:00 New York of the assignment date). */
    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse assign(AccountRef account, @Valid @RequestBody AssignRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        return toResponse(assignments.assign(account.id(), new OptionAssignmentService.AssignCommand(contract,
                req.contracts().intValueExact(), req.assignmentDate(), req.assignmentFee(), req.note())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse open(AccountRef account, @Valid @RequestBody OpenTradeRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        int multiplier = req.multiplier() == null
                ? OpenTradeTerms.DEFAULT_MULTIPLIER
                : req.multiplier().intValueExact();
        return toResponse(service.open(account.id(), new OptionTradeService.OpenCommand(contract, req.action(),
                req.contracts().intValueExact(), multiplier, req.netAmount(), req.tradedAtLocal(), req.note())));
    }

    /** FEAT-004: record a closing trade (BTC / STC). */
    @PostMapping("/closes")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse close(AccountRef account, @Valid @RequestBody CloseTradeRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        return toResponse(service.close(account.id(), new OptionTradeService.CloseCommand(contract, req.action(),
                req.contracts().intValueExact(), req.netAmount(), req.tradedAtLocal(), req.note())));
    }

    /** FEAT-012: expire the whole position of a contract at its expiration cutoff (16:00 New York). */
    @PostMapping("/expirations")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse expire(AccountRef account, @Valid @RequestBody ExpireRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        return toResponse(expiries.expire(account.id(),
                new OptionExpiryService.ExpireCommand(contract, req.contracts().intValueExact())));
    }

    /** FEAT-002 trade list; all filters optional, see TradeListQuery. */
    @GetMapping
    public TradePageResponse list(
            AccountRef account,
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
                endDate, page, size);
        OptionTradeService.TradePage p = service.list(account.id(), filter, page, size);
        return new TradePageResponse(p.items().stream().map(OptionTradeController::toResponse).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages());
    }

    /** FEAT-005: edit the editable fields of a trade. */
    @PutMapping("/{id}")
    public TradeResponse update(AccountRef account, @PathVariable long id, @Valid @RequestBody EditTradeRequest req) {
        return toResponse(edits.update(account.id(), id, new OptionTradeEditService.EditCommand(req.contracts().intValueExact(),
                req.netAmount(), req.tradedAtLocal(),
                req.multiplier() == null ? null : req.multiplier().intValueExact(), req.note())));
    }

    /** FEAT-005: delete a trade and its ledger entry. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AccountRef account, @PathVariable long id) {
        edits.delete(account.id(), id);
    }

    @GetMapping("/{id}")
    public TradeResponse get(AccountRef account, @PathVariable long id) {
        return toResponse(service.get(account.id(), id));
    }

    private static TradeResponse toResponse(OptionTradeService.TradeView view) {
        OptionTradeEntity t = view.trade();
        ContractKey contract = t.contract();
        PositionDirection expiredDirection = t.getAction() == TradeAction.EXP
                ? view.closeMatch().map(m -> PositionDirection.openedBy(m.coveredLots().get(0).action())).orElse(null)
                : null;
        return new TradeResponse(t.getId(), t.getSymbol(), t.getExpirationDate(),
                ContractKey.strikeText(t.getStrike()), t.getOptionType(), t.getAction(), t.getContracts(),
                t.getMultiplier(), Money.of(t.getNetAmount()), t.getTradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(t.getTradedAt())), view.cashChange(), view.frozenChange(),
                contract.label(), t.getNote(), view.ledgerEntryId(), t.getCreatedAt(),
                view.closeMatch().map(m -> m.coveredLots().stream().map(OptionTradeController::toCovered).toList())
                        .orElse(null),
                view.closeMatch().map(CloseMatch::realizedPnl).orElse(null), t.hasAuditLog(), expiredDirection,
                assignment(view));
    }

    /** FEAT-013: figures of an ASG record, null for every other record. */
    static AssignmentResponse assignment(OptionTradeService.TradeView view) {
        return view.assignment().map(a -> new AssignmentResponse(
                MarketTime.toLocal(view.trade().getTradedAt()).toLocalDate(), Money.of(view.trade().getAssignmentFee()),
                a.premium(), a.shares(), a.purchaseAmount(), a.stockCost(), a.stockCostPerShare(),
                view.assignedLot() == null ? null : view.assignedLot().getId())).orElse(null);
    }

    private static CoveredLotResponse toCovered(PositionLot l) {
        return new CoveredLotResponse(l.tradeId(), l.action(), l.tradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(l.tradedAt())), l.originalContracts(), l.originalNetAmount(),
                l.originalFrozen());
    }
}
