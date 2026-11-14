package com.optionmgr.quote.web;

import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.quote.domain.PriceSource;
import com.optionmgr.quote.domain.SourceResult;
import com.optionmgr.quote.domain.SymbolResult;
import com.optionmgr.quote.service.QuoteFetchService;
import com.optionmgr.quote.service.QuoteOverrideService;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.Symbols;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

/** FEAT-016 market data: status, "立即刷新", manual overrides. One set for all accounts (no X-Account-Id). */
@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

    static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    private final QuoteQueryService queries;
    private final QuoteFetchService fetches;
    private final QuoteOverrideService overrides;

    public MarketDataController(QuoteQueryService queries, QuoteFetchService fetches, QuoteOverrideService overrides) {
        this.queries = queries;
        this.fetches = fetches;
        this.overrides = overrides;
    }

    public record SymbolResultResponse(String symbol, SymbolResult result, SourceResult cboeResult,
                                       SourceResult finnhubResult, PriceSource underlyingPriceSource,
                                       String failureDetail) {
    }

    public record RefreshResponse(boolean performed, String reason, List<SymbolResultResponse> results,
                                  QuoteViews.Status status) {
    }

    public record UnderlyingOverrideRequest(BigDecimal price) {
    }

    public record OptionOverrideRequest(String symbol, LocalDate expirationDate, BigDecimal strike,
                                        OptionType optionType, BigDecimal mark) {
    }

    @GetMapping("/status")
    public QuoteViews.Status status() {
        return queries.status();
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh() {
        QuoteFetchService.RefreshOutcome r = fetches.refresh();
        return new RefreshResponse(r.performed(), r.reason(), results(r.results()), queries.status());
    }

    public static List<SymbolResultResponse> results(List<QuoteFetchService.SymbolOutcome> outcomes) {
        return outcomes.stream().map(o -> new SymbolResultResponse(o.symbol(), o.result(), o.cboeResult(),
                o.finnhubResult(), o.underlyingPriceSource(), o.failureDetail())).toList();
    }

    @PutMapping("/overrides/underlyings/{symbol}")
    public QuoteViews.UnderlyingQuote setUnderlying(@PathVariable String symbol,
                                                    @RequestBody UnderlyingOverrideRequest req) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        String s = Symbols.normalize(symbol);
        if (s == null || s.isBlank()) {
            errors.add(new ErrorResponse.FieldError("symbol", "请填写标的"));
        }
        checkPrice("price", req == null ? null : req.price(), errors);
        reject(errors);
        return overrides.setUnderlying(s, req.price());
    }

    @DeleteMapping("/overrides/underlyings/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearUnderlying(@PathVariable String symbol) {
        overrides.clearUnderlying(Symbols.normalize(symbol));
    }

    @PutMapping("/overrides/options")
    public QuoteViews.OptionQuote setOption(@RequestBody OptionOverrideRequest req) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        String symbol = req == null ? null : Symbols.normalize(req.symbol());
        if (symbol == null || symbol.isBlank()) {
            errors.add(new ErrorResponse.FieldError("symbol", "请填写标的"));
        }
        if (req == null || req.expirationDate() == null) {
            errors.add(new ErrorResponse.FieldError("expirationDate", "请填写到期日"));
        }
        if (req == null || req.strike() == null) {
            errors.add(new ErrorResponse.FieldError("strike", "请填写行权价"));
        } else if (req.strike().signum() <= 0) {
            errors.add(new ErrorResponse.FieldError("strike", "行权价必须大于 0"));
        }
        if (req == null || req.optionType() == null) {
            errors.add(new ErrorResponse.FieldError("optionType", "请选择 Call / Put"));
        }
        checkPrice("mark", req == null ? null : req.mark(), errors);
        reject(errors);
        return overrides.setOption(new ContractKey(symbol, req.expirationDate(), req.strike(), req.optionType()),
                req.mark());
    }

    @DeleteMapping("/overrides/options")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearOption(@RequestParam String symbol, @RequestParam LocalDate expirationDate,
                            @RequestParam BigDecimal strike, @RequestParam OptionType optionType) {
        if (strike.signum() <= 0) {
            return;
        }
        overrides.clearOption(new ContractKey(Symbols.normalize(symbol), expirationDate, strike, optionType));
    }

    static void checkPrice(String field, BigDecimal price, List<ErrorResponse.FieldError> errors) {
        if (price == null) {
            errors.add(new ErrorResponse.FieldError(field, "请输入价格"));
        } else if (price.signum() <= 0) {
            errors.add(new ErrorResponse.FieldError(field, "价格必须大于 0"));
        } else if (Math.max(price.stripTrailingZeros().scale(), 0) > 4) {
            errors.add(new ErrorResponse.FieldError(field, "价格最多 4 位小数"));
        } else if (price.compareTo(MAX_PRICE) >= 0) {
            errors.add(new ErrorResponse.FieldError(field, "价格过大"));
        }
    }

    private static void reject(List<ErrorResponse.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
    }
}
