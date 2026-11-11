package com.optionmgr.trade.web;

import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.web.OptionTradeDtos.OpenTradeRequest;
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

/** Option trade API: record an opening trade (FEAT-001), read one back, list with filters (FEAT-002). */
@RestController
@RequestMapping("/api/option-trades")
public class OptionTradeController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionTradeService service;

    public OptionTradeController(OptionTradeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse open(@Valid @RequestBody OpenTradeRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        int multiplier = req.multiplier() == null
                ? OpenTradeTerms.DEFAULT_MULTIPLIER
                : req.multiplier().intValueExact();
        return toResponse(service.open(new OptionTradeService.OpenCommand(contract, req.action(),
                req.contracts().intValueExact(), multiplier, req.netAmount(), req.tradedAtLocal(), req.note())));
    }

    /** FEAT-002 trade list; all filters optional, see TradeListQuery. */
    @GetMapping
    public TradePageResponse list(
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
        OptionTradeService.TradePage p = service.list(filter, page, size);
        return new TradePageResponse(p.items().stream().map(OptionTradeController::toResponse).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages());
    }

    @GetMapping("/{id}")
    public TradeResponse get(@PathVariable long id) {
        return toResponse(service.get(id));
    }

    private static TradeResponse toResponse(OptionTradeService.TradeView view) {
        OptionTradeEntity t = view.trade();
        CashLedgerEntryEntity e = view.ledgerEntry();
        ContractKey contract = t.contract();
        return new TradeResponse(t.getId(), t.getSymbol(), t.getExpirationDate(),
                ContractKey.strikeText(t.getStrike()), t.getOptionType(), t.getAction(), t.getContracts(),
                t.getMultiplier(), t.getNetAmount(), t.getTradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(t.getTradedAt())), e.getAmount(), e.getFrozenChange(),
                contract.label(), t.getNote(), e.getId(), t.getCreatedAt());
    }
}
