package com.optionmgr.trade.web;

import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.service.OptionExpiryService.BatchExpirationRejectedException;
import com.optionmgr.trade.web.OptionTradeDtos.BatchExpireErrorResponse;
import com.optionmgr.trade.web.OptionTradeDtos.BatchExpireFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * FEAT-015: renders a rejected batch expiry as the common error body plus {@code failures} (one per failed item,
 * processing order, with the single-expire code and message).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BatchExpirationErrorHandler {

    private final Clock clock;

    public BatchExpirationErrorHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BatchExpirationRejectedException.class)
    public ResponseEntity<BatchExpireErrorResponse> handle(BatchExpirationRejectedException ex,
                                                           HttpServletRequest req) {
        ErrorCode code = ErrorCode.BATCH_EXPIRATION_REJECTED;
        List<BatchExpireFailure> failures = ex.failures().stream().map(f -> {
            ContractKey k = f.contract();
            return new BatchExpireFailure(f.index(), k.symbol(), k.expirationDate(), ContractKey.strikeText(k.strike()),
                    k.optionType(), k.label(), f.code().name(), f.message());
        }).toList();
        return ResponseEntity.status(code.status()).body(new BatchExpireErrorResponse(code.name(), ex.getMessage(),
                code.status().value(), req.getRequestURI(), clock.instant(), List.of(), failures));
    }
}
