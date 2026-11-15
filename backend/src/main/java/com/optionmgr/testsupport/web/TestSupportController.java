package com.optionmgr.testsupport.web;

import com.optionmgr.account.service.AccountService;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.testsupport.E2eProfile;
import com.optionmgr.testsupport.service.DatabaseResetService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.service.OptionTradeService;
import com.optionmgr.trade.web.OptionTradeController;
import com.optionmgr.trade.web.OptionTradeDtos.OpenTradeRequest;
import com.optionmgr.trade.web.OptionTradeDtos.TradeResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.service.FetchAttempt;
import com.optionmgr.calendar.service.HolidayFetchService;
import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.calendar.web.TradingCalendarController;
import com.optionmgr.config.SettableClock;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final AccountService accounts;
    private final HolidayFetchService holidayFetches;
    private final TradingCalendarService calendar;
    private final SettableClock clock;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<com.optionmgr.quote.service.InMemoryQuoteTransport> quoteSources;

    public TestSupportController(DatabaseResetService resetService, OptionTradeService trades,
                                 HolidayFetchService holidayFetches, TradingCalendarService calendar,
                                 SettableClock clock, ObjectMapper mapper,
                                 org.springframework.beans.factory.ObjectProvider<com.optionmgr.quote.service.InMemoryQuoteTransport> quoteSources,
                                 AccountService accounts) {
        this.accounts = accounts;
        this.quoteSources = quoteSources;
        this.resetService = resetService;
        this.trades = trades;
        this.holidayFetches = holidayFetches;
        this.calendar = calendar;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** FEAT-009: a simulated Finnhub response: {httpStatus, body} or {noKey: true}. */
    public record HolidayFetchRequest(Integer httpStatus, JsonNode body, Boolean noKey) {
    }

    /** FEAT-009: a fixed "now". */
    public record ClockRequest(Instant now) {
    }

    /**
     * FEAT-009: runs the same processing as a real fetch (parse, Q127 merge, status) on a simulated response, at the
     * current clock time. A textual {@code body} is used verbatim (to simulate a non-JSON body).
     */
    @PostMapping("/trading-calendar/fetch")
    public TradingCalendarController.StatusResponse fetchHolidays(@RequestBody HolidayFetchRequest req) {
        FetchAttempt attempt;
        if (Boolean.TRUE.equals(req.noKey())) {
            attempt = new FetchAttempt.NoKey();
        } else if (req.httpStatus() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("httpStatus", "httpStatus 或 noKey 必填")));
        } else {
            attempt = new FetchAttempt.Response(req.httpStatus(), bodyText(req.body()));
        }
        holidayFetches.process(attempt);
        return TradingCalendarController.toResponse(calendar.status(), calendar.calendar().coveredYears());
    }

    private String bodyText(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return null;
        }
        if (body.isTextual()) {
            return body.asText();
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** FEAT-009: fixes the backend clock at {@code now} (all time-dependent logic). */
    @PutMapping("/clock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setClock(@RequestBody ClockRequest req) {
        if (req.now() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("now", "now 必填")));
        }
        clock.set(req.now());
    }

    /** FEAT-009: back to the system clock. */
    @DeleteMapping("/clock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetClock() {
        clock.reset();
    }

    /**
     * FEAT-039: an STO call stored as if entered before FEAT-039 (LEGACY: no coverage check), then evaluated by the
     * automatic inclusion. FEAT-043: also any other open (BTO / STO, call / put) stored without the Q126 trading-day
     * check (e.g. an option expiring on 2025-01-09). Same body and response as POST /api/option-trades.
     */
    @PostMapping("/legacy-option-trades")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse legacyOptionTrade(AccountRef account, @Valid @RequestBody OpenTradeRequest req) {
        if (req.action() == null || !req.action().isOpening()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError("action", "只能预置开仓")));
        }
        ContractKey contract = new ContractKey(req.symbol(), req.expirationDate(), req.strike(), req.optionType());
        int multiplier = req.multiplier() == null
                ? OpenTradeTerms.DEFAULT_MULTIPLIER
                : req.multiplier().intValueExact();
        return OptionTradeController.toResponse(accounts.get(account.id()).getName(), trades.open(account.id(),
                new OptionTradeService.OpenCommand(contract,
                req.action(), req.contracts().intValueExact(), multiplier, req.netAmount(), req.tradedAtLocal(),
                req.note()), true));
    }

    /** Deletes all business data and restores the freshly migrated state (ids restart; FEAT-009: holidays and fetch status cleared, system clock restored). */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        resetService.reset();
        clock.reset();
        // FEAT-016: fake quote sources back to empty, request log cleared (quote tables: by the reset snapshot)
        quoteSources.ifAvailable(com.optionmgr.quote.service.InMemoryQuoteTransport::reset);
    }
}
