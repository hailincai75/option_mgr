package com.optionmgr.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.optionmgr.account.service.AccountService;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.web.OptionTradeController;
import com.optionmgr.trade.web.OptionTradeDtos.OpenTradeRequest;
import com.optionmgr.trade.web.OptionTradeDtos.TradeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test sources only (never in the application): records an opening trade the way it was entered before FEAT-039, so
 * an STO call is stored LEGACY and needs no covering shares. Tests of earlier features (FEAT-012 / 013 / 038 / 041
 * ...) that sell calls without holding the stock use it through {@link #openUrl(JsonNode)}; every other check of
 * {@code POST /api/option-trades} still applies.
 */
@RestController
public class LegacyShortCallTestController {

    public static final String URL = "/api/test-only/legacy-option-trades";

    private final OptionTradeService trades;
    private final AccountService accounts;

    public LegacyShortCallTestController(OptionTradeService trades, AccountService accounts) {
        this.accounts = accounts;
        this.trades = trades;
    }

    /** The URL to post an opening trade to: the legacy path for an STO call, otherwise the real endpoint. */
    public static String openUrl(JsonNode body) {
        boolean shortCall = "STO".equals(body.path("action").asText()) && "CALL".equals(body.path("optionType").asText());
        return shortCall ? URL : "/api/option-trades";
    }

    @PostMapping(URL)
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse open(AccountRef account, @Valid @RequestBody OpenTradeRequest req) {
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        int multiplier = req.multiplier() == null
                ? OpenTradeTerms.DEFAULT_MULTIPLIER
                : req.multiplier().intValueExact();
        return OptionTradeController.toResponse(accounts.get(account.id()).getName(), trades.open(account.id(),
                new OptionTradeService.OpenCommand(contract,
                req.action(), req.contracts().intValueExact(), multiplier, req.netAmount(), req.tradedAtLocal(),
                req.note()), true));
    }
}
