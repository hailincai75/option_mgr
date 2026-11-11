package com.optionmgr.common.error;

import org.springframework.http.HttpStatus;

/**
 * Generic error codes shared by all endpoints. Each code maps to exactly one HTTP status.
 * Keep in sync with the table in docs/api/api-contract.md.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-007 (and later trades): a cash movement would make total or available cash negative at some point. */
    CASH_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-001 Q7: an opening trade goes against the contract's long/short position. */
    POSITION_DIRECTION_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-001 Q16: selling calls (STO call) is not supported in M1. */
    SELL_CALL_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-001 Q23: multiplier differs from the open position of the same contract. */
    MULTIPLIER_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
