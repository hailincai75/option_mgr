package com.optionmgr.testsupport.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.testsupport.E2eProfile;
import com.optionmgr.testsupport.service.DatabaseResetService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.web.OptionTradeController;
import com.optionmgr.trade.web.OptionTradeDtos.OpenTradeRequest;
import com.optionmgr.trade.web.OptionTradeDtos.TradeResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints for the Playwright suite. The bean exists only with the {@code e2e} profile; in normal
 * runs the paths are unknown and return 404 like any other unknown path.
 */
@RestController
@Profile(E2eProfile.NAME)
@RequestMapping("/api/test-support")
public class TestSupportController {

    private final DatabaseResetService resetService;
    private final OptionTradeService trades;

    public TestSupportController(DatabaseResetService resetService, OptionTradeService trades) {
        this.resetService = resetService;
        this.trades = trades;
    }

    /**
     * FEAT-039: an STO call stored as if entered before FEAT-039 (LEGACY: no coverage check), then evaluated by the
     * automatic inclusion. Same body and response as POST /api/option-trades.
     */
    @PostMapping("/legacy-option-trades")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse legacyOptionTrade(AccountRef account, @Valid @RequestBody OpenTradeRequest req) {
        if (req.action() != TradeAction.STO || req.optionType() != OptionType.CALL) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("action", "只能预置 STO call")));
        }
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        int multiplier = req.multiplier() == null
                ? OpenTradeTerms.DEFAULT_MULTIPLIER
                : req.multiplier().intValueExact();
        return OptionTradeController.toResponse(trades.open(account.id(), new OptionTradeService.OpenCommand(contract,
                req.action(), req.contracts().intValueExact(), multiplier, req.netAmount(), req.tradedAtLocal(),
                req.note()), true));
    }

    /** Deletes all business data and restores the freshly migrated state (ids restart). */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        resetService.reset();
    }
}
