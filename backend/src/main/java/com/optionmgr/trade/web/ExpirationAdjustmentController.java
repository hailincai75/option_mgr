package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.service.ExpirationAdjustmentService;
import com.optionmgr.trade.web.OptionTradeDtos.AdjustExpirationRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-043: adjust a contract's expiration date when it falls on an ACTIVE ad-hoc closure. */
@RestController
@RequestMapping("/api/option-trades/expiration-adjustments")
public class ExpirationAdjustmentController {

    private final ExpirationAdjustmentService service;
    private final QuoteQueryService quotes;

    public ExpirationAdjustmentController(ExpirationAdjustmentService service, QuoteQueryService quotes) {
        this.service = service;
        this.quotes = quotes;
    }

    public record AdjustExpirationResponse(long auditId, String symbol, String strike, OptionType optionType,
                                           LocalDate oldExpirationDate, LocalDate newExpirationDate,
                                           String contractLabel, List<Long> tradeIds,
                                           OptionPositionController.PositionResponse position) {
    }

    @PostMapping
    public AdjustExpirationResponse adjust(AccountRef account, @Valid @RequestBody AdjustExpirationRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        ExpirationAdjustmentService.Result r = service.adjust(account.id(),
                new ExpirationAdjustmentService.Command(contract, req.newExpirationDate(), req.note()));
        ContractKey c = r.newContract();
        return new AdjustExpirationResponse(r.auditId(), c.symbol(), ContractKey.strikeText(c.strike()),
                c.optionType(), r.oldContract().expirationDate(), c.expirationDate(), c.label(), r.tradeIds(),
                r.position().map(p -> OptionPositionController.toResponse(p, r.summary(), quotes.book())).orElse(null));
    }
}
