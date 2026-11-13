package com.optionmgr.stock.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.LotSale;
import com.optionmgr.stock.domain.SaleMatch;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.domain.StockTimes;
import com.optionmgr.stock.domain.StockTradeFilter;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.service.StockTradeEditService;
import com.optionmgr.stock.service.StockTradeService;
import com.optionmgr.stock.web.StockTradeDtos.CreateStockTradeRequest;
import com.optionmgr.stock.web.StockTradeDtos.EditStockTradeRequest;
import com.optionmgr.stock.web.StockTradeDtos.SoldLotResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockTradePageResponse;
import com.optionmgr.stock.web.StockTradeDtos.StockTradeResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-011 stock trades: create (buy / sell / transfer-in), read, list, edit, delete. */
@RestController
@RequestMapping("/api/stock-trades")
public class StockTradeController {

    static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final StockTradeService service;
    private final StockTradeEditService edits;

    public StockTradeController(StockTradeService service, StockTradeEditService edits) {
        this.service = service;
        this.edits = edits;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockTradeResponse create(AccountRef account, @Valid @RequestBody CreateStockTradeRequest req) {
        return toResponse(service.create(account.id(), new StockTradeService.CreateCommand(req.type(), req.symbol(),
                req.shares().intValueExact(), req.amount(), req.tradedAtLocal(), req.tradeDate(), req.note())));
    }

    @GetMapping
    public StockTradePageResponse list(
            AccountRef account,
            @RequestParam(required = false) String symbol,
            @RequestParam(name = "type", required = false) List<String> types,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        StockTradeFilter filter = StockTradeListQuery.parse(symbol, types, startDate, endDate, page, size);
        StockTradeService.StockTradePage p = service.list(account.id(), filter, page, size);
        return new StockTradePageResponse(p.items().stream().map(StockTradeController::toResponse).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages());
    }

    @GetMapping("/{id}")
    public StockTradeResponse get(AccountRef account, @PathVariable long id) {
        return toResponse(service.get(account.id(), id));
    }

    @PutMapping("/{id}")
    public StockTradeResponse update(AccountRef account, @PathVariable long id,
                                     @Valid @RequestBody EditStockTradeRequest req) {
        return toResponse(edits.update(account.id(), id, new StockTradeEditService.EditCommand(
                req.shares().intValueExact(), req.amount(), req.tradedAtLocal(), req.tradeDate(), req.note(),
                req.symbol(), req.type())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AccountRef account, @PathVariable long id) {
        edits.delete(account.id(), id);
    }

    static String local(Instant instant) {
        return LOCAL_SECONDS.format(MarketTime.toLocal(instant));
    }

    static LocalDate transferDate(StockTradeType type, Instant tradedAt) {
        return type == StockTradeType.TRANSFER_IN ? StockTimes.marketDate(tradedAt) : null;
    }

    private static StockTradeResponse toResponse(StockTradeService.StockTradeView view) {
        StockTradeEntity t = view.trade();
        SaleMatch sale = view.sale().orElse(null);
        return new StockTradeResponse(t.getId(), t.getType(), t.getSymbol(), t.getShares(), t.getAmount(),
                view.cashChange(), StockPosition.perShare(t.getAmount(), t.getShares()), t.getTradedAt(),
                local(t.getTradedAt()), transferDate(t.getType(), t.getTradedAt()), t.getNote(), view.ledgerEntryId(),
                t.getCreatedAt(), t.hasAuditLog(),
                sale == null ? null : sale.soldCost(),
                sale == null ? null : sale.realizedPnl(),
                sale == null ? null : sale.lots().stream().map(StockTradeController::toSoldLot).toList(),
                t.getOptionTradeId(), t.getType() != StockTradeType.ASSIGN_BUY, t.getType() != StockTradeType.ASSIGN_BUY);
    }

    private static SoldLotResponse toSoldLot(LotSale l) {
        return new SoldLotResponse(l.sourceId(), l.sourceType(), l.tradedAt(), local(l.tradedAt()),
                transferDate(l.sourceType(), l.tradedAt()), l.shares(), l.cost());
    }
}
